package net.woggioni.gbcs.configuration

import net.woggioni.gbcs.api.Role
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Duration

@ConsistentCopyVisibility
data class Configuration private constructor(
    val host: String,
    val port: Int,
    val serverPath: String?,
    val users: Map<String, User>,
    val groups: Map<String, Group>,
    val cache: Cache,
    val authentication : Authentication?,
    val tls: Tls?,
    val useVirtualThread: Boolean
) {

    data class Group(val name: String, val roles: Set<Role>) {
        override fun hashCode(): Int {
            return name.hashCode()
        }
    }

    data class User(val name: String, val password: String?, val groups: Set<Group>) {
        override fun hashCode(): Int {
            return name.hashCode()
        }

        val roles : Set<Role>
            get() = groups.asSequence().flatMap { it.roles }.toSet()
    }

    fun interface UserExtractor {
        fun extract(cert :X509Certificate) : User
    }

    fun interface GroupExtractor {
        fun extract(cert :X509Certificate) : Group
    }

    data class Tls(
        val keyStore: KeyStore?,
        val trustStore: TrustStore?,
        val verifyClients: Boolean,
    )

    data class KeyStore(
        val file: Path,
        val password: String?,
        val keyAlias: String,
        val keyPassword: String?
    )

    data class TrustStore(
        val file: Path,
        val password: String?,
        val checkCertificateStatus: Boolean
    )


    data class TlsCertificateExtractor(val rdnType : String, val pattern : String)

    interface Authentication

    class BasicAuthentication : Authentication

    data class ClientCertificateAuthentication(
        val userExtractor: TlsCertificateExtractor?,
        val groupExtractor: TlsCertificateExtractor?) : Authentication


    interface Cache

    data class FileSystemCache(val root: Path, val maxAge: Duration) : Cache

    companion object {

        fun of(
            host: String,
            port: Int,
            serverPath: String?,
            users: Map<String, User>,
            groups: Map<String, Group>,
            cache: Cache,
            authentication : Authentication?,
            tls: Tls?,
            useVirtualThread: Boolean
        ) = Configuration(
            host,
            port,
            serverPath?.takeIf { it.isNotEmpty() && it != "/" },
            users,
            groups,
            cache,
            authentication,
            tls,
            useVirtualThread
        )

//        fun parse(document: Document): Configuration {
//            val cacheSerializers = ServiceLoader.load(Configuration::class.java.module.layer, CacheSerializer::class.java)
//                .asSequence()
//                .map {
//                    "${it.xmlType}:${it.xmlNamespace}" to it
//                }.toMap()
//            val root = document.documentElement
//            var cache: Cache? = null
//            var host = "127.0.0.1"
//            var port = 11080
//            var users = emptyMap<String, User>()
//            var groups = emptyMap<String, Group>()
//            var tls: Tls? = null
//            val serverPath = root.getAttribute("path")
//            val useVirtualThread = root.getAttribute("useVirtualThreads")
//                .takeIf(String::isNotEmpty)
//                ?.let(String::toBoolean) ?: false
//            var authentication : Authentication? = null
//            for (child in root.asIterable()) {
//                when (child.nodeName) {
//                    "authorization" -> {
//                        for (gchild in child.asIterable()) {
//                            when (child.nodeName) {
//                                "users" -> {
//                                    users = parseUsers(child)
//                                }
//
//                                "groups" -> {
//                                    val pair = parseGroups(child, users)
//                                    users = pair.first
//                                    groups = pair.second
//                                }
//                            }
//                        }
//                    }
//
//                    "bind" -> {
//                        host = child.getAttribute("host")
//                        port = Integer.parseInt(child.getAttribute("port"))
//                    }
//
//                    "cache" -> {
//                        val type = child.getAttribute("xs:type")
//                        val serializer = cacheSerializers.get(type) ?: throw NotImplementedError()
//                        cache = serializer.deserialize(child)
//
//                        when(child.getAttribute("xs:type")) {
//                            "gbcs:fileSystemCacheType" -> {
//                                val cacheFolder = child.getAttribute("path")
//                                    .takeIf(String::isNotEmpty)
//                                    ?.let(Paths::get)
//                                    ?: Paths.get(System.getProperty("user.home")).resolve(".gbcs")
//                                val maxAge = child.getAttribute("max-age")
//                                    .takeIf(String::isNotEmpty)
//                                    ?.let(Duration::parse)
//                                    ?: Duration.ofDays(1)
//                                cache = FileSystemCache(cacheFolder, maxAge)
//                            }
//                        }
////                        for (gchild in child.asIterable()) {
////                            when (gchild.nodeName) {
////                                "file-system-cache" -> {
////                                    val cacheFolder = gchild.getAttribute("path")
////                                        .takeIf(String::isNotEmpty)
////                                        ?.let(Paths::get)
////                                        ?: Paths.get(System.getProperty("user.home")).resolve(".gbcs")
////                                    val maxAge = gchild.getAttribute("max-age")
////                                        .takeIf(String::isNotEmpty)
////                                        ?.let(Duration::parse)
////                                        ?: Duration.ofDays(1)
////                                    cache = FileSystemCache(cacheFolder, maxAge)
////                                }
////                            }
////                        }
//                    }
//
//                    "authentication" -> {
//                        for (gchild in child.asIterable()) {
//                            when (gchild.nodeName) {
//                                "basic" -> {
//                                    authentication = BasicAuthentication()
//                                }
//
//                                "client-certificate" -> {
//                                    var tlsExtractorUser : TlsCertificateExtractor? = null
//                                    var tlsExtractorGroup : TlsCertificateExtractor? = null
//                                    for (gchild in child.asIterable()) {
//                                        when (gchild.nodeName) {
//                                            "group-extractor" -> {
//                                                val attrName = gchild.getAttribute("attribute-name")
//                                                val pattern = gchild.getAttribute("pattern")
//                                                tlsExtractorGroup = TlsCertificateExtractor(attrName, pattern)
//                                            }
//
//                                            "user-extractor" -> {
//                                                val attrName = gchild.getAttribute("attribute-name")
//                                                val pattern = gchild.getAttribute("pattern")
//                                                tlsExtractorUser = TlsCertificateExtractor(attrName, pattern)
//                                            }
//                                        }
//                                    }
//                                    authentication = ClientCertificateAuthentication(tlsExtractorUser, tlsExtractorGroup)
//                                }
//                            }
//                        }
//                    }
//
//                    "tls" -> {
//                        val verifyClients = child.getAttribute("verify-clients")
//                            .takeIf(String::isNotEmpty)
//                            ?.let(String::toBoolean) ?: false
//                        var keyStore: KeyStore? = null
//                        var trustStore: TrustStore? = null
//                        for (granChild in child.asIterable()) {
//                            when (granChild.nodeName) {
//                                "keystore" -> {
//                                    val keyStoreFile = Paths.get(granChild.getAttribute("file"))
//                                    val keyStorePassword = granChild.getAttribute("password")
//                                        .takeIf(String::isNotEmpty)
//                                    val keyAlias = granChild.getAttribute("key-alias")
//                                    val keyPassword = granChild.getAttribute("key-password")
//                                        .takeIf(String::isNotEmpty)
//                                    keyStore = KeyStore(
//                                        keyStoreFile,
//                                        keyStorePassword,
//                                        keyAlias,
//                                        keyPassword
//                                    )
//                                }
//
//                                "truststore" -> {
//                                    val trustStoreFile = Paths.get(granChild.getAttribute("file"))
//                                    val trustStorePassword = granChild.getAttribute("password")
//                                        .takeIf(String::isNotEmpty)
//                                    val checkCertificateStatus = granChild.getAttribute("check-certificate-status")
//                                        .takeIf(String::isNotEmpty)
//                                        ?.let(String::toBoolean)
//                                        ?: false
//                                    trustStore = TrustStore(
//                                        trustStoreFile,
//                                        trustStorePassword,
//                                        checkCertificateStatus
//                                    )
//                                }
//                            }
//                        }
//                        tls = Tls(keyStore, trustStore, verifyClients)
//                    }
//                }
//            }
//            return of(host, port, serverPath, users, groups, cache!!, authentication, tls, useVirtualThread)
//        }
//
//        private fun parseRoles(root: Element) = root.asIterable().asSequence().map {
//            when (it.nodeName) {
//                "reader" -> Role.Reader
//                "writer" -> Role.Writer
//                else -> throw UnsupportedOperationException("Illegal node '${it.nodeName}'")
//            }
//        }.toSet()
//
//        private fun parseUserRefs(root: Element) = root.asIterable().asSequence().filter {
//            it.nodeName == "user"
//        }.map {
//            it.getAttribute("ref")
//        }.toSet()
//
//        private fun parseUsers(root: Element): Map<String, User> {
//            return root.asIterable().asSequence().filter {
//                it.nodeName == "user"
//            }.map { el ->
//                val username = el.getAttribute("name")
//                val password = el.getAttribute("password").takeIf(String::isNotEmpty)
//                username to User(username, password, emptySet())
//            }.toMap()
//        }
//
//        private fun parseGroups(root: Element, knownUsers : Map<String, User>): Pair<Map<String, User>, Map<String, Group>> {
//            val userGroups = mutableMapOf<String, MutableSet<String>>()
//            val groups =  root.asIterable().asSequence().filter {
//                it.nodeName == "group"
//            }.map { el ->
//                val groupName = el.getAttribute("name")
//                var roles = emptySet<Role>()
//                for (child in el.asIterable()) {
//                    when (child.nodeName) {
//                        "users" -> {
//                            parseUserRefs(child).mapNotNull(knownUsers::get).forEach { user ->
//                                userGroups.computeIfAbsent(user.name) {
//                                    mutableSetOf()
//                                }.add(groupName)
//                            }
//                        }
//                        "roles" -> {
//                            roles = parseRoles(child)
//                        }
//                    }
//                }
//                groupName to Group(groupName, roles)
//            }.toMap()
//            val users = knownUsers.map { (name, user) ->
//                name to User(name, user.password, userGroups[name]?.mapNotNull { groups[it] }?.toSet() ?: emptySet())
//            }.toMap()
//            return users to groups
//        }
    }
}
