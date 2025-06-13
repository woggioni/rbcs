package net.woggioni.rbcs.client.impl

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.temporal.ChronoUnit
import net.woggioni.rbcs.api.exception.ConfigurationException
import net.woggioni.rbcs.client.Configuration
import net.woggioni.rbcs.common.Xml.Companion.asIterable
import net.woggioni.rbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document

object Parser {

    fun parse(document: Document): Configuration {
        val root = document.documentElement
        val profiles = mutableMapOf<String, Configuration.Profile>()

        for (child in root.asIterable()) {
            val tagName = child.localName
            when (tagName) {
                "profile" -> {
                    val name =
                        child.renderAttribute("name") ?: throw ConfigurationException("name attribute is required")
                    val uri = child.renderAttribute("base-url")?.let(::URI)
                        ?: throw ConfigurationException("base-url attribute is required")
                    var authentication: Configuration.Authentication? = null
                    var retryPolicy: Configuration.RetryPolicy? = null
                    var connection : Configuration.Connection = Configuration.Connection(
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        false
                    )
                    var trustStore : Configuration.TrustStore? = null
                    for (gchild in child.asIterable()) {
                        when (gchild.localName) {
                            "tls-client-auth" -> {
                                val keyStoreFile = gchild.renderAttribute("key-store-file")
                                val keyStorePassword =
                                    gchild.renderAttribute("key-store-password")
                                val keyAlias = gchild.renderAttribute("key-alias")
                                val keyPassword = gchild.renderAttribute("key-password")

                                val keystore = KeyStore.getInstance("PKCS12").apply {
                                    Files.newInputStream(Path.of(keyStoreFile)).use {
                                        load(it, keyStorePassword?.toCharArray())
                                    }
                                }
                                val key = keystore.getKey(keyAlias, keyPassword?.toCharArray()) as PrivateKey
                                val certChain = keystore.getCertificateChain(keyAlias).asSequence()
                                    .map { it as X509Certificate }
                                    .toList()
                                    .toTypedArray()
                                authentication =
                                    Configuration.Authentication.TlsClientAuthenticationCredentials(
                                        key,
                                        certChain
                                    )
                            }

                            "basic-auth" -> {
                                val username = gchild.renderAttribute("user")
                                    ?: throw ConfigurationException("username attribute is required")
                                val password = gchild.renderAttribute("password")
                                    ?: throw ConfigurationException("password attribute is required")
                                authentication =
                                    Configuration.Authentication.BasicAuthenticationCredentials(
                                        username,
                                        password
                                    )
                            }

                            "retry-policy" -> {
                                val maxAttempts =
                                    gchild.renderAttribute("max-attempts")
                                        ?.let(String::toInt)
                                        ?: throw ConfigurationException("max-attempts attribute is required")
                                val initialDelay =
                                    gchild.renderAttribute("initial-delay")
                                        ?.let(Duration::parse)
                                        ?: Duration.ofSeconds(1)
                                val exp =
                                    gchild.renderAttribute("exp")
                                        ?.let(String::toDouble)
                                        ?: 2.0f
                                retryPolicy = Configuration.RetryPolicy(
                                    maxAttempts,
                                    initialDelay.toMillis(),
                                    exp.toDouble()
                                )
                            }

                            "connection" -> {
                                val idleTimeout = gchild.renderAttribute("idle-timeout")
                                    ?.let(Duration::parse) ?: Duration.of(30, ChronoUnit.SECONDS)
                                val readIdleTimeout = gchild.renderAttribute("read-idle-timeout")
                                    ?.let(Duration::parse) ?: Duration.of(60, ChronoUnit.SECONDS)
                                val writeIdleTimeout = gchild.renderAttribute("write-idle-timeout")
                                    ?.let(Duration::parse) ?: Duration.of(60, ChronoUnit.SECONDS)
                                val requestPipelining = gchild.renderAttribute("request-pipelining")
                                    ?.let(String::toBoolean) ?: false
                                connection = Configuration.Connection(
                                    readIdleTimeout,
                                    writeIdleTimeout,
                                    idleTimeout,
                                    requestPipelining
                                )
                            }

                            "tls-trust-store" -> {
                                val file = gchild.renderAttribute("file")
                                    ?.let(Path::of)
                                val password = gchild.renderAttribute("password")
                                val checkCertificateStatus = gchild.renderAttribute("check-certificate-status")
                                    ?.let(String::toBoolean) ?: false
                                val verifyServerCertificate = gchild.renderAttribute("verify-server-certificate")
                                    ?.let(String::toBoolean) ?: true
                                trustStore = Configuration.TrustStore(file, password, checkCertificateStatus, verifyServerCertificate)
                            }
                        }
                    }
                    val maxConnections = child.renderAttribute("max-connections")
                        ?.let(String::toInt)
                        ?: 50
                    val connectionTimeout = child.renderAttribute("connection-timeout")
                        ?.let(Duration::parse)
                    val compressionEnabled = child.renderAttribute("enable-compression")
                        ?.let(String::toBoolean)
                        ?: true

                    profiles[name] = Configuration.Profile(
                        uri,
                        connection,
                        authentication,
                        connectionTimeout,
                        maxConnections,
                        compressionEnabled,
                        retryPolicy,
                        trustStore
                    )
                }
            }
        }
        return Configuration(profiles)
    }
}