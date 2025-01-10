package net.woggioni.gbcs.configuration

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.Configuration.Authentication
import net.woggioni.gbcs.api.Configuration.BasicAuthentication
import net.woggioni.gbcs.api.Configuration.Cache
import net.woggioni.gbcs.api.Configuration.ClientCertificateAuthentication
import net.woggioni.gbcs.api.Configuration.Group
import net.woggioni.gbcs.api.Configuration.KeyStore
import net.woggioni.gbcs.api.Configuration.Tls
import net.woggioni.gbcs.api.Configuration.TlsCertificateExtractor
import net.woggioni.gbcs.api.Configuration.TrustStore
import net.woggioni.gbcs.api.Configuration.User
import net.woggioni.gbcs.api.Role
import net.woggioni.gbcs.base.Xml.Companion.asIterable
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.TypeInfo
import java.lang.IllegalArgumentException
import java.nio.file.Paths

object Parser {

    fun parse(document: Document): Configuration {
        val root = document.documentElement
        var cache: Cache? = null
        var host = "127.0.0.1"
        var port = 11080
        var users = emptyMap<String, User>()
        var groups = emptyMap<String, Group>()
        var tls: Tls? = null
        val serverPath = root.getAttribute("path")
        val useVirtualThread = root.getAttribute("useVirtualThreads")
            .takeIf(String::isNotEmpty)
            ?.let(String::toBoolean) ?: true
        var authentication: Authentication? = null
        for (child in root.asIterable()) {
            when (child.localName) {
                "authorization" -> {
                    for (gchild in child.asIterable()) {
                        when (child.localName) {
                            "users" -> {
                                users = parseUsers(child)
                            }

                            "groups" -> {
                                val pair = parseGroups(child, users)
                                users = pair.first
                                groups = pair.second
                            }
                        }
                    }
                }

                "bind" -> {
                    host = child.getAttribute("host")
                    port = Integer.parseInt(child.getAttribute("port"))
                }

                "cache" -> {
                    cache = (child as TypeInfo).let { tf ->
                        val typeNamespace = tf.typeNamespace
                        val typeName = tf.typeName
                        CacheSerializers.index[typeNamespace to typeName]
                            ?: throw IllegalArgumentException("Cache provider for namespace '$typeNamespace' not found")
                    }.deserialize(child)
                }

                "authentication" -> {
                    for (gchild in child.asIterable()) {
                        when (gchild.localName) {
                            "basic" -> {
                                authentication = BasicAuthentication()
                            }

                            "client-certificate" -> {
                                var tlsExtractorUser: TlsCertificateExtractor? = null
                                var tlsExtractorGroup: TlsCertificateExtractor? = null
                                for (gchild in child.asIterable()) {
                                    when (gchild.localName) {
                                        "group-extractor" -> {
                                            val attrName = gchild.getAttribute("attribute-name")
                                            val pattern = gchild.getAttribute("pattern")
                                            tlsExtractorGroup = TlsCertificateExtractor(attrName, pattern)
                                        }

                                        "user-extractor" -> {
                                            val attrName = gchild.getAttribute("attribute-name")
                                            val pattern = gchild.getAttribute("pattern")
                                            tlsExtractorUser = TlsCertificateExtractor(attrName, pattern)
                                        }
                                    }
                                }
                                authentication = ClientCertificateAuthentication(tlsExtractorUser, tlsExtractorGroup)
                            }
                        }
                    }
                }

                "tls" -> {
                    val verifyClients = child.getAttribute("verify-clients")
                        .takeIf(String::isNotEmpty)
                        ?.let(String::toBoolean) ?: false
                    var keyStore: KeyStore? = null
                    var trustStore: TrustStore? = null
                    for (granChild in child.asIterable()) {
                        when (granChild.localName) {
                            "keystore" -> {
                                val keyStoreFile = Paths.get(granChild.getAttribute("file"))
                                val keyStorePassword = granChild.getAttribute("password")
                                    .takeIf(String::isNotEmpty)
                                val keyAlias = granChild.getAttribute("key-alias")
                                val keyPassword = granChild.getAttribute("key-password")
                                    .takeIf(String::isNotEmpty)
                                keyStore = KeyStore(
                                    keyStoreFile,
                                    keyStorePassword,
                                    keyAlias,
                                    keyPassword
                                )
                            }

                            "truststore" -> {
                                val trustStoreFile = Paths.get(granChild.getAttribute("file"))
                                val trustStorePassword = granChild.getAttribute("password")
                                    .takeIf(String::isNotEmpty)
                                val checkCertificateStatus = granChild.getAttribute("check-certificate-status")
                                    .takeIf(String::isNotEmpty)
                                    ?.let(String::toBoolean)
                                    ?: false
                                trustStore = TrustStore(
                                    trustStoreFile,
                                    trustStorePassword,
                                    checkCertificateStatus
                                )
                            }
                        }
                    }
                    tls = Tls(keyStore, trustStore, verifyClients)
                }
            }
        }
        return Configuration(host, port, serverPath, users, groups, cache!!, authentication, tls, useVirtualThread)
    }

    private fun parseRoles(root: Element) = root.asIterable().asSequence().map {
        when (it.localName) {
            "reader" -> Role.Reader
            "writer" -> Role.Writer
            else -> throw UnsupportedOperationException("Illegal node '${it.localName}'")
        }
    }.toSet()

    private fun parseUserRefs(root: Element) = root.asIterable().asSequence().filter {
        it.localName == "user"
    }.map {
        it.getAttribute("ref")
    }.toSet()

    private fun parseUsers(root: Element): Map<String, User> {
        return root.asIterable().asSequence().filter {
            it.localName == "user"
        }.map { el ->
            val username = el.getAttribute("name")
            val password = el.getAttribute("password").takeIf(String::isNotEmpty)
            username to User(username, password, emptySet())
        }.toMap()
    }

    private fun parseGroups(root: Element, knownUsers: Map<String, User>): Pair<Map<String, User>, Map<String, Group>> {
        val userGroups = mutableMapOf<String, MutableSet<String>>()
        val groups = root.asIterable().asSequence().filter {
            it.localName == "group"
        }.map { el ->
            val groupName = el.getAttribute("name")
            var roles = emptySet<Role>()
            for (child in el.asIterable()) {
                when (child.localName) {
                    "users" -> {
                        parseUserRefs(child).mapNotNull(knownUsers::get).forEach { user ->
                            userGroups.computeIfAbsent(user.name) {
                                mutableSetOf()
                            }.add(groupName)
                        }
                    }

                    "roles" -> {
                        roles = parseRoles(child)
                    }
                }
            }
            groupName to Group(groupName, roles)
        }.toMap()
        val users = knownUsers.map { (name, user) ->
            name to User(name, user.password, userGroups[name]?.mapNotNull { groups[it] }?.toSet() ?: emptySet())
        }.toMap()
        return users to groups
    }
}