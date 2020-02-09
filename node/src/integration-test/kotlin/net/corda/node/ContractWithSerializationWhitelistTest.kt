package net.corda.node

import net.corda.client.rpc.CordaRPCClient
import net.corda.contracts.serialization.whitelist.WhitelistData
import net.corda.core.contracts.TransactionVerificationException.ContractRejection
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.flows.serialization.whitelist.WhitelistFlow
import net.corda.node.internal.cordapp.NotCordappWhitelist
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.driver.internal.incrementalPortAllocation
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.User
import net.corda.testing.node.internal.cordappWithPackages
import org.assertj.core.api.Assertions.assertThat
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertFailsWith

@Suppress("FunctionName")
class ContractWithSerializationWhitelistTest {
    companion object {
        const val DATA = 123456L

        @JvmField
        val contractCordapp = cordappWithPackages("net.corda.contracts.serialization.whitelist").signed()

        @JvmField
        val workflowCordapp = cordappWithPackages("net.corda.flows.serialization.whitelist").signed()

        fun parametersFor(runInProcess: Boolean): DriverParameters {
            return DriverParameters(
                portAllocation = incrementalPortAllocation(),
                startNodesInProcess = runInProcess,
                notarySpecs = listOf(NotarySpec(DUMMY_NOTARY_NAME, validating = true)),
                cordappsForAllNodes = listOf(contractCordapp, workflowCordapp)
            )
        }

        @BeforeClass
        @JvmStatic
        fun checkData() {
            assertNotCordaSerializable<WhitelistData>()
        }
    }

    @Test
    fun `test serialization whitelist out-of-process`() {
        val user = User("u", "p", setOf(Permissions.all()))
        driver(parametersFor(runInProcess = false)) {
            val badData = WhitelistData(DATA)
            val alice = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            val ex = assertFailsWith<ContractRejection> {
                CordaRPCClient(hostAndPort = alice.rpcAddress)
                    .start(user.username, user.password)
                    .use { client ->
                        client.proxy.startFlow(::WhitelistFlow, badData)
                            .returnValue
                            .getOrThrow()
                    }
            }
            assertThat(ex)
                .hasMessageContaining("WhitelistData $badData exceeds maximum value!")
        }
    }

    @Test
    fun `test serialization whitelist in-process`() {
        assertFailsWith<NotCordappWhitelist> {
            driver(parametersFor(runInProcess = true)) {}
        }
    }
}