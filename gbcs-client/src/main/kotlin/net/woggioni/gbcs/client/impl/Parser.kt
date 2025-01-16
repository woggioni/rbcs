package net.woggioni.gbcs.client.impl

import net.woggioni.gbcs.api.exception.ConfigurationException
import net.woggioni.gbcs.base.Xml.Companion.asIterable
import net.woggioni.gbcs.base.Xml.Companion.renderAttribute
import net.woggioni.gbcs.client.GbcsClient
import org.w3c.dom.Document
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object Parser {

    fun parse(document: Document): GbcsClient.Configuration {
        val root = document.documentElement

        val profiles = mutableMapOf<String, GbcsClient.Configuration.Profile>()

        for (child in root.asIterable()) {
            val tagName = child.localName
            when (tagName) {
                "profile" -> {
                    val name = child.renderAttribute("name") ?: throw ConfigurationException("name attribute is required")
                    val uri = child.renderAttribute("base-url")?.let(::URI) ?: throw ConfigurationException("base-url attribute is required")
                    var authentication: GbcsClient.Configuration.Authentication? = null
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
                                    GbcsClient.Configuration.Authentication.TlsClientAuthenticationCredentials(key, certChain)
                            }

                            "basic-auth" -> {
                                val username = gchild.renderAttribute("user") ?: throw ConfigurationException("username attribute is required")
                                val password = gchild.renderAttribute("password") ?: throw ConfigurationException("password attribute is required")
                                authentication =
                                    GbcsClient.Configuration.Authentication.BasicAuthenticationCredentials(username, password)
                            }
                        }
                    }
                    val maxConnections = child.renderAttribute("max-connections")
                        ?.let(String::toInt)
                        ?: 50
                    profiles[name] = GbcsClient.Configuration.Profile(uri, authentication, maxConnections)
                }
            }
        }
        return GbcsClient.Configuration(profiles)
    }
}