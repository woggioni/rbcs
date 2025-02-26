package net.woggioni.rbcs.server.configuration

import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.api.Configuration.Authentication
import net.woggioni.rbcs.api.Configuration.BasicAuthentication
import net.woggioni.rbcs.api.Configuration.Cache
import net.woggioni.rbcs.api.Configuration.ClientCertificateAuthentication
import net.woggioni.rbcs.api.Configuration.Group
import net.woggioni.rbcs.api.Configuration.KeyStore
import net.woggioni.rbcs.api.Configuration.Tls
import net.woggioni.rbcs.api.Configuration.TlsCertificateExtractor
import net.woggioni.rbcs.api.Configuration.TrustStore
import net.woggioni.rbcs.api.Configuration.User
import net.woggioni.rbcs.api.Role
import net.woggioni.rbcs.api.exception.ConfigurationException
import net.woggioni.rbcs.common.Xml.Companion.asIterable
import net.woggioni.rbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.TypeInfo
import java.nio.file.Paths
import java.time.Duration
import java.time.temporal.ChronoUnit

object Parser {
    fun parse(document: Document): Configuration {
        val root = document.documentElement
        val anonymousUser = User("", null, emptySet(), null)
        var connection: Configuration.Connection = Configuration.Connection(
            Duration.of(60, ChronoUnit.SECONDS),
            Duration.of(30, ChronoUnit.SECONDS),
            Duration.of(30, ChronoUnit.SECONDS),
            67108864
        )
        var eventExecutor: Configuration.EventExecutor = Configuration.EventExecutor(true)
        var cache: Cache? = null
        var host = "127.0.0.1"
        var port = 11080
        var users: Map<String, User> = mapOf(anonymousUser.name to anonymousUser)
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
                            ?: throw IllegalArgumentException("Cache provider for namespace '$typeNamespace' with name '$typeName' not found")
                    }.deserialize(child)
                }

                "connection" -> {
                    val idleTimeout = child.renderAttribute("idle-timeout")
                        ?.let(Duration::parse) ?: Duration.of(30, ChronoUnit.SECONDS)
                    val readIdleTimeout = child.renderAttribute("read-idle-timeout")
                        ?.let(Duration::parse) ?: Duration.of(60, ChronoUnit.SECONDS)
                    val writeIdleTimeout = child.renderAttribute("write-idle-timeout")
                        ?.let(Duration::parse) ?: Duration.of(60, ChronoUnit.SECONDS)
                    val maxRequestSize = child.renderAttribute("max-request-size")
                        ?.let(Integer::decode) ?: 0x4000000
                    connection = Configuration.Connection(
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
                                val requireClientCertificate = child.renderAttribute("require-client-certificate")
                                    ?.let(String::toBoolean) ?: false

                                trustStore = TrustStore(
                                    trustStoreFile,
                                    trustStorePassword,
                                    checkCertificateStatus,
                                    requireClientCertificate
                                )
                            }
                        }
                    }
                    tls = Tls(keyStore, trustStore)
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
            "healthcheck" -> Role.Healthcheck
            else -> throw UnsupportedOperationException("Illegal node '${it.localName}'")
        }
    }.toSet()

    private fun parseUserRefs(root: Element) = root.asIterable().asSequence().map {
        when (it.localName) {
            "user" -> it.renderAttribute("ref")
            "anonymous" -> ""
            else -> ConfigurationException("Unrecognized tag '${it.localName}'")
        }
    }

    private fun parseQuota(el: Element): Configuration.Quota {
        val calls = el.renderAttribute("calls")
            ?.let(String::toLong)
            ?: throw ConfigurationException("Missing attribute 'calls'")
        val maxAvailableCalls = el.renderAttribute("max-available-calls")
            ?.let(String::toLong)
            ?: calls
        val initialAvailableCalls = el.renderAttribute("initial-available-calls")
            ?.let(String::toLong)
            ?: maxAvailableCalls
        val period = el.renderAttribute("period")
            ?.let(Duration::parse)
            ?: throw ConfigurationException("Missing attribute 'period'")
        return Configuration.Quota(calls, period, initialAvailableCalls, maxAvailableCalls)
    }

    private fun parseUsers(root: Element): Sequence<User> {
        return root.asIterable().asSequence().mapNotNull { child ->
            when (child.localName) {
                "user" -> {
                    val username = child.renderAttribute("name")
                    val password = child.renderAttribute("password")
                    var quota: Configuration.Quota? = null
                    for (gchild in child.asIterable()) {
                        if (gchild.localName == "quota") {
                            quota = parseQuota(gchild)
                        }
                    }
                    User(username, password, emptySet(), quota)
                }
                "anonymous" -> {
                    var quota: Configuration.Quota? = null
                    for (gchild in child.asIterable()) {
                        if (gchild.localName == "quota") {
                            quota= parseQuota(gchild)
                        }
                    }
                    User("", null, emptySet(), quota)
                }
                else -> null
            }
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
            var userQuota: Configuration.Quota? = null
            var groupQuota: Configuration.Quota? = null
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
                    "group-quota" -> {
                        userQuota = parseQuota(child)
                    }
                    "user-quota" -> {
                        groupQuota = parseQuota(child)
                    }
                }
            }
            groupName to Group(groupName, roles, userQuota, groupQuota)
        }.toMap()
        val users = knownUsersMap.map { (name, user) ->
            name to User(name, user.password, userGroups[name]?.mapNotNull { groups[it] }?.toSet() ?: emptySet(), user.quota)
        }.toMap()
        return users to groups
    }
}