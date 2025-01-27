package net.woggioni.gbcs.client.impl

import net.woggioni.gbcs.api.exception.ConfigurationException
import net.woggioni.gbcs.common.Xml.Companion.asIterable
import net.woggioni.gbcs.common.Xml.Companion.renderAttribute
import net.woggioni.gbcs.client.GradleBuildCacheClient
import org.w3c.dom.Document
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration

object Parser {

    fun parse(document: Document): GradleBuildCacheClient.Configuration {
        val root = document.documentElement
        val profiles = mutableMapOf<String, GradleBuildCacheClient.Configuration.Profile>()

        for (child in root.asIterable()) {
            val tagName = child.localName
            when (tagName) {
                "profile" -> {
                    val name =
                        child.renderAttribute("name") ?: throw ConfigurationException("name attribute is required")
                    val uri = child.renderAttribute("base-url")?.let(::URI)
                        ?: throw ConfigurationException("base-url attribute is required")
                    var authentication: GradleBuildCacheClient.Configuration.Authentication? = null
                    var retryPolicy: GradleBuildCacheClient.Configuration.RetryPolicy? = null
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
                                    GradleBuildCacheClient.Configuration.Authentication.TlsClientAuthenticationCredentials(
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
                                    GradleBuildCacheClient.Configuration.Authentication.BasicAuthenticationCredentials(
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
                                retryPolicy = GradleBuildCacheClient.Configuration.RetryPolicy(
                                    maxAttempts,
                                    initialDelay.toMillis(),
                                    exp.toDouble()
                                )
                            }
                        }
                    }
                    val maxConnections = child.renderAttribute("max-connections")
                        ?.let(String::toInt)
                        ?: 50
                    val connectionTimeout = child.renderAttribute("connection-timeout")
                        ?.let(Duration::parse)
                    profiles[name] = GradleBuildCacheClient.Configuration.Profile(
                        uri,
                        authentication,
                        connectionTimeout,
                        maxConnections,
                        retryPolicy
                    )
                }
            }
        }
        return GradleBuildCacheClient.Configuration(profiles)
    }
}