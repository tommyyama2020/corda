package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.Suspendable
import com.codahale.metrics.Gauge
import com.codahale.metrics.Histogram
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.Reservoir
import com.codahale.metrics.SlidingTimeWindowArrayReservoir
import com.codahale.metrics.SlidingTimeWindowReservoir
import net.corda.core.internal.concurrent.thenMatch
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.internal.CheckpointSerializationContext
import net.corda.core.serialization.internal.checkpointSerialize
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.trace
import net.corda.node.services.api.CheckpointStorage
import net.corda.node.services.api.ServiceHubInternal
import net.corda.nodeapi.internal.persistence.contextDatabase
import net.corda.nodeapi.internal.persistence.contextTransaction
import net.corda.nodeapi.internal.persistence.contextTransactionOrNull
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * This is the bottom execution engine of flow side-effects.
 */
class ActionExecutorImpl(
        private val services: ServiceHubInternal,
        private val checkpointStorage: CheckpointStorage,
        private val flowMessaging: FlowMessaging,
        private val stateMachineManager: StateMachineManagerInternal,
        private val checkpointSerializationContext: CheckpointSerializationContext,
        metrics: MetricRegistry
) : ActionExecutor {

    private companion object {
        val log = contextLogger()
    }

    /**
     * This [Gauge] just reports the sum of the bytes checkpointed during the last second.
     */
    private class LatchedGauge(private val reservoir: Reservoir) : Gauge<Long> {
        override fun getValue(): Long {
            return reservoir.snapshot.values.sum()
        }
    }

    private val checkpointingMeter = metrics.meter("Flows.Checkpointing Rate")
    private val checkpointSizesThisSecond = SlidingTimeWindowReservoir(1, TimeUnit.SECONDS)
    private val lastBandwidthUpdate = AtomicLong(0)
    private val checkpointBandwidthHist = metrics.register("Flows.CheckpointVolumeBytesPerSecondHist", Histogram(SlidingTimeWindowArrayReservoir(1, TimeUnit.DAYS)))
    private val checkpointBandwidth = metrics.register("Flows.CheckpointVolumeBytesPerSecondCurrent", LatchedGauge(checkpointSizesThisSecond))

    @Suspendable
    override fun executeAction(fiber: FlowFiber, action: Action) {
        log.trace { "Flow ${fiber.id} executing $action" }
        return when (action) {
            is Action.TrackTransaction -> executeTrackTransaction(fiber, action)
            is Action.PersistCheckpoint -> executePersistCheckpoint(action)
            is Action.PersistDeduplicationFacts -> executePersistDeduplicationIds(action)
            is Action.AcknowledgeMessages -> executeAcknowledgeMessages(action)
            is Action.PropagateErrors -> executePropagateErrors(action)
            is Action.ScheduleEvent -> executeScheduleEvent(fiber, action)
            is Action.SleepUntil -> executeSleepUntil(action)
            is Action.RemoveCheckpoint -> executeRemoveCheckpoint(action)
            is Action.SendInitial -> executeSendInitial(action)
            is Action.SendExisting -> executeSendExisting(action)
            is Action.AddSessionBinding -> executeAddSessionBinding(action)
            is Action.RemoveSessionBindings -> executeRemoveSessionBindings(action)
            is Action.SignalFlowHasStarted -> executeSignalFlowHasStarted(action)
            is Action.RemoveFlow -> executeRemoveFlow(action)
            is Action.CreateTransaction -> executeCreateTransaction()
            is Action.RollbackTransaction -> executeRollbackTransaction()
            is Action.CommitTransaction -> executeCommitTransaction()
            is Action.ExecuteAsyncOperation -> executeAsyncOperation(fiber, action)
            is Action.ReleaseSoftLocks -> executeReleaseSoftLocks(action)
            is Action.RetryFlowFromSafePoint -> executeRetryFlowFromSafePoint(action)
            is Action.ScheduleFlowTimeout -> scheduleFlowTimeout(action)
            is Action.CancelFlowTimeout -> cancelFlowTimeout(action)
        }
    }
    private fun executeReleaseSoftLocks(action: Action.ReleaseSoftLocks) {
        if (action.uuid != null) services.vaultService.softLockRelease(action.uuid)
    }

    @Suspendable
    private fun executeTrackTransaction(fiber: FlowFiber, action: Action.TrackTransaction) {
        services.validatedTransactions.trackTransaction(action.hash).thenMatch(
                success = { transaction ->
                    fiber.scheduleEvent(Event.TransactionCommitted(transaction))
                },
                failure = { exception ->
                    fiber.scheduleEvent(Event.Error(exception))
                }
        )
    }

    @Suspendable
    private fun executePersistCheckpoint(action: Action.PersistCheckpoint) {
        val checkpointBytes = serializeCheckpoint(action.checkpoint)
        if (action.isCheckpointUpdate) {
            checkpointStorage.updateCheckpoint(action.id, checkpointBytes)
        } else {
            checkpointStorage.addCheckpoint(action.id, checkpointBytes)
        }
        checkpointingMeter.mark()
        checkpointSizesThisSecond.update(checkpointBytes.size.toLong())
        var lastUpdateTime = lastBandwidthUpdate.get()
        while (System.nanoTime() - lastUpdateTime > TimeUnit.SECONDS.toNanos(1)) {
            if (lastBandwidthUpdate.compareAndSet(lastUpdateTime, System.nanoTime())) {
                val checkpointVolume = checkpointSizesThisSecond.snapshot.values.sum()
                checkpointBandwidthHist.update(checkpointVolume)
            }
            lastUpdateTime = lastBandwidthUpdate.get()
        }
    }

    @Suspendable
    private fun executePersistDeduplicationIds(action: Action.PersistDeduplicationFacts) {
        for (handle in action.deduplicationHandlers) {
            handle.insideDatabaseTransaction()
        }
    }

    @Suppress("TooGenericExceptionCaught") // this is fully intentional here, see comment in the catch clause
    @Suspendable
    private fun executeAcknowledgeMessages(action: Action.AcknowledgeMessages) {
        action.deduplicationHandlers.forEach {
            try {
                it.afterDatabaseTransaction()
            } catch (e: Exception) {
                // Catch all exceptions that occur in the [DeduplicationHandler]s (although errors should be unlikely)
                // It is deemed safe for errors to occur here
                // Therefore the current transition should not fail if something does go wrong
                log.info(
                        "An error occurred executing a deduplication post-database commit handler. Continuing, as it is safe to do so.",
                        e
                )
            }
        }
    }

    @Suspendable
    private fun executePropagateErrors(action: Action.PropagateErrors) {
        action.errorMessages.forEach { (exception) ->
            log.warn("Propagating error", exception)
        }
        for (sessionState in action.sessions) {
            // We cannot propagate if the session isn't live.
            if (sessionState.initiatedState !is InitiatedSessionState.Live) {
                continue
            }
            // Don't propagate errors to the originating session
            for (errorMessage in action.errorMessages) {
                val sinkSessionId = sessionState.initiatedState.peerSinkSessionId
                val existingMessage = ExistingSessionMessage(sinkSessionId, errorMessage)
                val deduplicationId = DeduplicationId.createForError(errorMessage.errorId, sinkSessionId)
                flowMessaging.sendSessionMessage(sessionState.peerParty, existingMessage, SenderDeduplicationId(deduplicationId, action.senderUUID))
            }
        }
    }

    @Suspendable
    private fun executeScheduleEvent(fiber: FlowFiber, action: Action.ScheduleEvent) {
        fiber.scheduleEvent(action.event)
    }

    @Suspendable
    private fun executeSleepUntil(action: Action.SleepUntil) {
        // TODO introduce explicit sleep state + wakeup event instead of relying on Fiber.sleep. This is so shutdown
        // conditions may "interrupt" the sleep instead of waiting until wakeup.
        val duration = Duration.between(services.clock.instant(), action.time)
        Fiber.sleep(duration.toNanos(), TimeUnit.NANOSECONDS)
    }

    @Suspendable
    private fun executeRemoveCheckpoint(action: Action.RemoveCheckpoint) {
        checkpointStorage.removeCheckpoint(action.id)
    }

    @Suspendable
    private fun executeSendInitial(action: Action.SendInitial) {
        flowMessaging.sendSessionMessage(action.destination, action.initialise, action.deduplicationId)
    }

    @Suspendable
    private fun executeSendExisting(action: Action.SendExisting) {
        flowMessaging.sendSessionMessage(action.peerParty, action.message, action.deduplicationId)
    }

    @Suspendable
    private fun executeAddSessionBinding(action: Action.AddSessionBinding) {
        stateMachineManager.addSessionBinding(action.flowId, action.sessionId)
    }

    @Suspendable
    private fun executeRemoveSessionBindings(action: Action.RemoveSessionBindings) {
        stateMachineManager.removeSessionBindings(action.sessionIds)
    }

    @Suspendable
    private fun executeSignalFlowHasStarted(action: Action.SignalFlowHasStarted) {
        stateMachineManager.signalFlowHasStarted(action.flowId)
    }

    @Suspendable
    private fun executeRemoveFlow(action: Action.RemoveFlow) {
        stateMachineManager.removeFlow(action.flowId, action.removalReason, action.lastState)
    }

    @Suspendable
    private fun executeCreateTransaction() {
        if (contextTransactionOrNull != null) {
            throw IllegalStateException("Refusing to create a second transaction")
        }
        contextDatabase.newTransaction()
    }

    @Suspendable
    private fun executeRollbackTransaction() {
        contextTransactionOrNull?.close()
    }

    @Suspendable
    private fun executeCommitTransaction() {
        try {
            contextTransaction.commit()
        } finally {
            contextTransaction.close()
            contextTransactionOrNull = null
        }
    }

    @Suppress("TooGenericExceptionCaught") // this is fully intentional here, see comment in the catch clause
    @Suspendable
    private fun executeAsyncOperation(fiber: FlowFiber, action: Action.ExecuteAsyncOperation) {
        try {
            val operationFuture = action.operation.execute(action.deduplicationId)
            operationFuture.thenMatch(
                    success = { result ->
                        fiber.scheduleEvent(Event.AsyncOperationCompletion(result))
                    },
                    failure = { exception ->
                        fiber.scheduleEvent(Event.AsyncOperationThrows(exception))
                    }
            )
        } catch (e: Exception) {
            // Catch and wrap any unexpected exceptions from the async operation
            // Wrapping the exception allows it to be better handled by the flow hospital
            throw AsyncOperationTransitionException(e)
        }
    }

    private fun executeRetryFlowFromSafePoint(action: Action.RetryFlowFromSafePoint) {
        stateMachineManager.retryFlowFromSafePoint(action.currentState)
    }

    private fun serializeCheckpoint(checkpoint: Checkpoint): SerializedBytes<Checkpoint> {
        return checkpoint.checkpointSerialize(context = checkpointSerializationContext)
    }

    private fun cancelFlowTimeout(action: Action.CancelFlowTimeout) {
        stateMachineManager.cancelFlowTimeout(action.flowId)
    }

    private fun scheduleFlowTimeout(action: Action.ScheduleFlowTimeout) {
        stateMachineManager.scheduleFlowTimeout(action.flowId)
    }
}
