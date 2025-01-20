package net.woggioni.gbcs.server.configuration

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
import net.woggioni.gbcs.api.exception.ConfigurationException
import net.woggioni.gbcs.common.Xml.Companion.asIterable
import net.woggioni.gbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.TypeInfo
import java.nio.file.Paths
import java.time.Duration
import java.time.temporal.ChronoUnit

object Parser {
    fun parse(document: Document): Configuration {
        val root = document.documentElement
        val anonymousUser = User("", null, emptySet())
        var connection: Configuration.Connection = Configuration.Connection(
            Duration.of(10, ChronoUnit.SECONDS),
            Duration.of(10, ChronoUnit.SECONDS),
            Duration.of(60, ChronoUnit.SECONDS),
            Duration.of(30, ChronoUnit.SECONDS),
            Duration.of(30, ChronoUnit.SECONDS),
            67108864
        )
        var eventExecutor: Configuration.EventExecutor = Configuration.EventExecutor(true)
        var cache: Cache? = null
        var host = "127.0.0.1"
        var port = 11080
        var users : Map<String, User> = mapOf(anonymousUser.name to anonymousUser)
        var groups = emptyMap<String, Group>()
        var tls: Tls? = null
        val serverPath = root.renderAttribute("path")
        var incomingConnectionsBacklogSize = 1024
        var authentication: Authentication? = null
        for (child in root.asIterable()) {
            val tagName = child.localName
            when (tagName) {
                "authentication" -> {
                    for (gchild in child.asIterable()) {
                        when (gchild.localName) {
                            "basic" -> {
                                authentication = BasicAuthentication()
                            }

                            "client-certificate" -> {
                                var tlsExtractorUser: TlsCertificateExtractor? = null
                                var tlsExtractorGroup: TlsCertificateExtractor? = null
                                for (ggchild in gchild.asIterable()) {
                                    when (ggchild.localName) {
                                        "group-extractor" -> {
                                            val attrName = ggchild.renderAttribute("attribute-name")
                                            val pattern = ggchild.renderAttribute("pattern")
                                            tlsExtractorGroup = TlsCertificateExtractor(attrName, pattern)
                                        }

                                        "user-extractor" -> {
                                            val attrName = ggchild.renderAttribute("attribute-name")
                                            val pattern = ggchild.renderAttribute("pattern")
                                            tlsExtractorUser = TlsCertificateExtractor(attrName, pattern)
                                        }
                                    }
                                }
                                authentication = ClientCertificateAuthentication(tlsExtractorUser, tlsExtractorGroup)
                            }
                        }
                    }
                }

                "authorization" -> {
                    var knownUsers = sequenceOf(anonymousUser)
                    for (gchild in child.asIterable()) {
                        when (gchild.localName) {
                            "users" -> {
                                knownUsers += parseUsers(gchild)
                            }
                            "groups" -> {
                                val pair = parseGroups(gchild, knownUsers)
                                users = pair.first
                                groups = pair.second
                            }
                        }
                    }
                }

                "bind" -> {
                    host = child.renderAttribute("host") ?: throw ConfigurationException("host attribute is required")
                    port = Integer.parseInt(child.renderAttribute("port"))
                    incomingConnectionsBacklogSize = child.renderAttribute("incoming-connections-backlog-size")
                        ?.let(Integer::parseInt)
                        ?: 1024
                }

                "cache" -> {
                    cache = (child as TypeInfo).let { tf ->
                        val typeNamespace = tf.typeNamespace
                        val typeName = tf.typeName
                        CacheSerializers.index[typeNamespace to typeName]
                            ?: throw IllegalArgumentException("Cache provider for namespace '$typeNamespace' not found")
                    }.deserialize(child)
                }

                "connection" -> {
                    val writeTimeout = child.renderAttribute("write-timeout")
                        ?.let(Duration::parse) ?: Duration.of(10, ChronoUnit.SECONDS)
                    val readTimeout = child.renderAttribute("read-timeout")
                        ?.let(Duration::parse) ?: Duration.of(10, ChronoUnit.SECONDS)
                    val idleTimeout = child.renderAttribute("idle-timeout")
                        ?.let(Duration::parse) ?: Duration.of(30, ChronoUnit.SECONDS)
                    val readIdleTimeout = child.renderAttribute("read-idle-timeout")
                        ?.let(Duration::parse) ?: Duration.of(60, ChronoUnit.SECONDS)
                    val writeIdleTimeout = child.renderAttribute("write-idle-timeout")
                        ?.let(Duration::parse) ?: Duration.of(60, ChronoUnit.SECONDS)
                    val maxRequestSize = child.renderAttribute("max-request-size")
                        ?.let(String::toInt) ?: 67108864
                    connection = Configuration.Connection(
                        readTimeout,
                        writeTimeout,
                        idleTimeout,
                        readIdleTimeout,
                        writeIdleTimeout,
                        maxRequestSize
                    )
                }
                "event-executor" -> {
                    val useVirtualThread = root.renderAttribute("use-virtual-threads")
                        ?.let(String::toBoolean) ?: true
                    eventExecutor = Configuration.EventExecutor(useVirtualThread)
                }
                "tls" -> {
                    val verifyClients = child.renderAttribute("verify-clients")
                        ?.let(String::toBoolean) ?: false
                    var keyStore: KeyStore? = null
                    var trustStore: TrustStore? = null
                    for (granChild in child.asIterable()) {
                        when (granChild.localName) {
                            "keystore" -> {
                                val keyStoreFile = Paths.get(granChild.renderAttribute("file"))
                                val keyStorePassword = granChild.renderAttribute("password")
                                val keyAlias = granChild.renderAttribute("key-alias")
                                val keyPassword = granChild.renderAttribute("key-password")
                                keyStore = KeyStore(
                                    keyStoreFile,
                                    keyStorePassword,
                                    keyAlias,
                                    keyPassword
                                )
                            }

                            "truststore" -> {
                                val trustStoreFile = Paths.get(granChild.renderAttribute("file"))
                                val trustStorePassword = granChild.renderAttribute("password")
                                val checkCertificateStatus = granChild.renderAttribute("check-certificate-status")
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
        return Configuration.of(
            host,
            port,
            incomingConnectionsBacklogSize,
            serverPath,
            eventExecutor,
            connection,
            users,
            groups,
            cache!!,
            authentication,
            tls,
        )
    }

    private fun parseRoles(root: Element) = root.asIterable().asSequence().map {
        when (it.localName) {
            "reader" -> Role.Reader
            "writer" -> Role.Writer
            else -> throw UnsupportedOperationException("Illegal node '${it.localName}'")
        }
    }.toSet()

    private fun parseUserRefs(root: Element) = root.asIterable().asSequence().map {
        when(it.localName) {
            "user" -> it.renderAttribute("ref")
            "anonymous" -> ""
            else -> ConfigurationException("Unrecognized tag '${it.localName}'")
        }
    }

    private fun parseUsers(root: Element): Sequence<User> {
        return root.asIterable().asSequence().filter {
            it.localName == "user"
        }.map { el ->
            val username = el.renderAttribute("name")
            val password = el.renderAttribute("password")
            User(username, password, emptySet())
        }
    }

    private fun parseGroups(root: Element, knownUsers: Sequence<User>): Pair<Map<String, User>, Map<String, Group>> {
        val knownUsersMap = knownUsers.associateBy(User::getName)
        val userGroups = mutableMapOf<String, MutableSet<String>>()
        val groups = root.asIterable().asSequence().filter {
            it.localName == "group"
        }.map { el ->
            val groupName = el.renderAttribute("name") ?: throw ConfigurationException("Group name is required")
            var roles = emptySet<Role>()
            for (child in el.asIterable()) {
                when (child.localName) {
                    "users" -> {
                        parseUserRefs(child).mapNotNull(knownUsersMap::get).forEach { user ->
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
        val users = knownUsersMap.map { (name, user) ->
            name to User(name, user.password, userGroups[name]?.mapNotNull { groups[it] }?.toSet() ?: emptySet())
        }.toMap()
        return users to groups
    }
}