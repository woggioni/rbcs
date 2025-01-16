package net.woggioni.gbcs.client.impl

import net.woggioni.gbcs.base.Xml.Companion.asIterable
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
                    val name = child.getAttribute("name")
                    val uri = child.getAttribute("base-url").let(::URI)
                    var authentication: GbcsClient.Configuration.Authentication? = null
                    for (gchild in child.asIterable()) {
                        when (gchild.localName) {
                            "tls-client-auth" -> {
                                val keyStoreFile = gchild.getAttribute("key-store-file")
                                val keyStorePassword =
                                    gchild.getAttribute("key-store-password").takeIf(String::isNotEmpty)
                                val keyAlias = gchild.getAttribute("key-alias")
                                val keyPassword = gchild.getAttribute("key-password").takeIf(String::isNotEmpty)

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
                                val username = gchild.getAttribute("user")
                                val password = gchild.getAttribute("password")
                                authentication =
                                    GbcsClient.Configuration.Authentication.BasicAuthenticationCredentials(username, password)
                            }
                        }
                    }
                    profiles[name] = GbcsClient.Configuration.Profile(uri, authentication)
                }
            }
        }
        return GbcsClient.Configuration(profiles)
    }
}