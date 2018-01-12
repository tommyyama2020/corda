package net.corda.node.services.config

import com.typesafe.config.*
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignatureScheme
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRenderOptions
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.nodeapi.internal.config.toProperties
import net.corda.nodeapi.internal.createDevKeyStores
import net.corda.nodeapi.internal.crypto.*
import org.slf4j.LoggerFactory
import java.nio.file.Path

fun configOf(vararg pairs: Pair<String, Any?>): Config = ConfigFactory.parseMap(mapOf(*pairs))
operator fun Config.plus(overrides: Map<String, Any?>): Config = ConfigFactory.parseMap(overrides).withFallback(this)

object ConfigHelper {
    private val log = LoggerFactory.getLogger(javaClass)
    fun loadConfig(baseDirectory: Path,
                   configFile: Path = baseDirectory / "node.conf",
                   allowMissingConfig: Boolean = false,
                   configOverrides: Config = ConfigFactory.empty()): Config {
        val parseOptions = ConfigParseOptions.defaults()
        val defaultConfig = ConfigFactory.parseResources("reference.conf", parseOptions.setAllowMissing(false))
        val appConfig = ConfigFactory.parseFile(configFile.toFile(), parseOptions.setAllowMissing(allowMissingConfig))
        val databaseConfig = ConfigFactory.parseResources(System.getProperty("databaseProvider")+".conf", parseOptions.setAllowMissing(true))

        //typesafe workaround: a system property with placeholder is passed as value (inside quotes),
        //undo adding the quotes for a fixed placeholder ${nodeOrganizationName}
        //https://github.com/lightbend/config/issues/265
        var systemUnquotedPlaceholders: Config = ConfigFactory.empty()
        ConfigFactory.systemProperties().toProperties().forEach { name, value ->
            if (value.toString().contains("\${nodeOrganizationName}")) {
                var unquotedPlaceholder = "\"" + value.toString().replace("\${nodeOrganizationName}","\"\${nodeOrganizationName}\"") + "\""
                systemUnquotedPlaceholders = systemUnquotedPlaceholders.withFallback(ConfigFactory.parseString(name.toString() + " = " + unquotedPlaceholder))
            }
        }
        val finalConfig = configOverrides
                // Add substitution values here
                .withFallback(systemUnquotedPlaceholders)
                .withFallback(configOf("nodeOrganizationName" to baseDirectory.fileName.toString().replace(" ","").replace("-","_")))
                .withFallback(ConfigFactory.systemProperties())
                .withFallback( configOf("baseDirectory" to baseDirectory.toString()))
                .withFallback(databaseConfig)
                .withFallback(appConfig)
                .withFallback(defaultConfig)
                .resolve()
        log.info("Config:\n${finalConfig.root().render(ConfigRenderOptions.defaults())}")
        return finalConfig
    }
}

/**
 * Strictly for dev only automatically construct a server certificate/private key signed from
 * the CA certs in Node resources. Then provision KeyStores into certificates folder under node path.
 */
// TODO Move this to KeyStoreConfigHelpers
fun NodeConfiguration.configureWithDevSSLCertificate() = configureDevKeyAndTrustStores(myLegalName)

// TODO Move this to KeyStoreConfigHelpers
fun SSLConfiguration.configureDevKeyAndTrustStores(myLegalName: CordaX500Name) {
    certificatesDirectory.createDirectories()
    if (!trustStoreFile.exists()) {
        loadKeyStore(javaClass.classLoader.getResourceAsStream("certificates/cordatruststore.jks"), "trustpass").save(trustStoreFile, trustStorePassword)
    }
    if (!sslKeystore.exists() || !nodeKeystore.exists()) {
        createDevKeyStores(myLegalName)

        // Move distributed service composite key (generated by IdentityGenerator.generateToDisk) to keystore if exists.
        val distributedServiceKeystore = certificatesDirectory / "distributedService.jks"
        if (distributedServiceKeystore.exists()) {
            val serviceKeystore = loadKeyStore(distributedServiceKeystore, "cordacadevpass")
            val cordaNodeKeystore = loadKeyStore(nodeKeystore, keyStorePassword)

            serviceKeystore.aliases().iterator().forEach {
                if (serviceKeystore.isKeyEntry(it)) {
                    cordaNodeKeystore.setKeyEntry(it, serviceKeystore.getKey(it, "cordacadevkeypass".toCharArray()), keyStorePassword.toCharArray(), serviceKeystore.getCertificateChain(it))
                } else {
                    cordaNodeKeystore.setCertificateEntry(it, serviceKeystore.getCertificate(it))
                }
            }
            cordaNodeKeystore.save(nodeKeystore, keyStorePassword)
        }
    }
}
