package net.woggioni.gbcs.configuration

import net.woggioni.gbcs.Xml
import org.w3c.dom.Document

object Serializer {

    private const val GBCS_NAMESPACE: String = "urn:net.woggioni.gbcs"
    private const val GBCS_PREFIX: String = "gbcs"

    fun serialize(conf : Configuration) : Document {
        return Xml.of(GBCS_NAMESPACE, GBCS_PREFIX + ":server") {
            attr("userVirtualThreads", conf.useVirtualThread.toString())
            conf.serverPath?.let { serverPath ->
                attr("path", serverPath)
            }
            node("bind") {
                attr("host", conf.host)
                attr("port", conf.port.toString())
            }
            node("cache") {
                when(val cache = conf.cache) {
                    is Configuration.FileSystemCache -> {
                        node("file-system-cache") {
                            attr("path", cache.root.toString())
                            attr("max-age", cache.maxAge.toString())
                        }
                    }
                    else -> throw NotImplementedError()
                }
            }
            node("authorization") {
                node("users") {
                    for(user in conf.users.values) {
                        node("user") {
                            attr("name", user.name)
                            user.password?.let { password ->
                                attr("password", password)
                            }
                        }
                    }
                }
                node("groups") {
                    val groups = conf.users.values.asSequence()
                        .flatMap {
                                user -> user.groups.map { it to user }
                        }.groupBy(Pair<Configuration.Group, Configuration.User>::first, Pair<Configuration.Group, Configuration.User>::second)
                    for(pair in groups) {
                        val group = pair.key
                        val users = pair.value
                        node("group") {
                            attr("name", group.name)
                            if(users.isNotEmpty()) {
                                node("users") {
                                    for(user in users) {
                                        node("user") {
                                            attr("ref", user.name)
                                        }
                                    }
                                }
                            }
                            if(group.roles.isNotEmpty()) {
                                node("roles") {
                                    for(role in group.roles) {
                                        node(role.toString().lowercase())
                                    }
                                }
                            }
                        }
                    }
                }
            }

            conf.authentication?.let { authentication ->
                node("authentication") {
                    when(authentication) {
                        is Configuration.BasicAuthentication -> {
                            node("basic")
                        }
                        is Configuration.ClientCertificateAuthentication -> {
                            node("client-certificate") {
                                authentication.userExtractor?.let { extractor ->
                                    node("user-extractor") {
                                        attr("attribute-name", extractor.rdnType)
                                        attr("pattern", extractor.pattern)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            conf.tls?.let { tlsConfiguration ->
                node("tls") {
                    tlsConfiguration.keyStore?.let { keyStore ->
                        node("keystore") {
                            attr("file", keyStore.file.toString())
                            keyStore.password?.let { keyStorePassword ->
                                attr("password", keyStorePassword)
                            }
                            attr("key-alias", keyStore.keyAlias)
                            keyStore.keyPassword?.let { keyPassword ->
                                attr("key-password", keyPassword)
                            }
                        }
                    }

                    tlsConfiguration.trustStore?.let { trustStore ->
                        node("truststore") {
                            attr("file", trustStore.file.toString())
                            trustStore.password?.let { password ->
                                attr("password", password)
                            }
                            attr("check-certificate-status", trustStore.checkCertificateStatus.toString())
                        }
                    }
                }
            }
        }
    }
}