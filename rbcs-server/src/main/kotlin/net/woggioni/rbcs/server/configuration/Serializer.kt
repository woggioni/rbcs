package net.woggioni.rbcs.server.configuration

import net.woggioni.rbcs.api.CacheProvider
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.RBCS
import net.woggioni.rbcs.common.Xml
import org.w3c.dom.Document

object Serializer {

    private fun Xml.serializeQuota(quota : Configuration.Quota) {
        attr("calls", quota.calls.toString())
        attr("period", quota.period.toString())
        attr("max-available-calls", quota.maxAvailableCalls.toString())
        attr("initial-available-calls", quota.initialAvailableCalls.toString())
    }

    fun serialize(conf : Configuration) : Document {
        val schemaLocations = CacheSerializers.index.values.asSequence().map {
            it.xmlNamespace to it.xmlSchemaLocation
        }.toMap()
        return Xml.of(RBCS.RBCS_NAMESPACE_URI, RBCS.RBCS_PREFIX + ":server") {
//            attr("xmlns:xs", GradleBuildCacheServer.XML_SCHEMA_NAMESPACE_URI)
            val value = schemaLocations.asSequence().map { (k, v) -> "$k $v" }.joinToString(" ")
            attr("xs:schemaLocation", value , namespaceURI = RBCS.XML_SCHEMA_NAMESPACE_URI)

            conf.serverPath
                ?.takeIf(String::isNotEmpty)
                ?.let { serverPath ->
                attr("path", serverPath)
            }
            node("bind") {
                attr("host", conf.host)
                attr("port", conf.port.toString())
                attr("incoming-connections-backlog-size", conf.incomingConnectionsBacklogSize.toString())
            }
            node("connection") {
                conf.connection.let { connection ->
                    attr("idle-timeout", connection.idleTimeout.toString())
                    attr("read-idle-timeout", connection.readIdleTimeout.toString())
                    attr("write-idle-timeout", connection.writeIdleTimeout.toString())
                    attr("max-request-size", connection.maxRequestSize.toString())
                    attr("chunk-size", connection.chunkSize.toString())
                }
            }
            node("event-executor") {
                attr("use-virtual-threads", conf.eventExecutor.isUseVirtualThreads.toString())
            }
            node("rate-limiter") {
                attr("delay-response", conf.rateLimiter.isDelayRequest.toString())
                attr("max-queued-messages", conf.rateLimiter.maxQueuedMessages.toString())
                attr("message-buffer-size", conf.rateLimiter.messageBufferSize.toString())
            }
            val cache = conf.cache
            val serializer : CacheProvider<Configuration.Cache> =
                (CacheSerializers.index[cache.namespaceURI to cache.typeName] as? CacheProvider<Configuration.Cache>) ?: throw NotImplementedError()
            element.appendChild(serializer.serialize(doc, cache))
            node("authorization") {
                node("users") {
                    for(user in conf.users.values) {
                        if(user.name.isNotEmpty()) {
                            node("user") {
                                attr("name", user.name)
                                user.password?.let { password ->
                                    attr("password", password)
                                }
                                user.quota?.let { quota ->
                                    node("quota") {
                                        serializeQuota(quota)
                                    }
                                }
                            }
                        }
                    }
                    conf.users[""]
                        ?.let { anonymousUser ->
                            anonymousUser.quota?.let { quota ->
                                node("anonymous") {
                                    node("quota") {
                                        serializeQuota(quota)
                                    }
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
                                    var anonymousUser : Configuration.User? = null
                                    for(user in users) {
                                        if(user.name.isNotEmpty()) {
                                            node("user") {
                                                attr("ref", user.name)
                                            }
                                        } else {
                                            anonymousUser = user
                                        }
                                    }
                                    if(anonymousUser != null) {
                                        node("anonymous")
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
                            group.userQuota?.let { quota ->
                                node("user-quota") {
                                    serializeQuota(quota)
                                }
                            }
                            group.groupQuota?.let { quota ->
                                node("group-quota") {
                                    serializeQuota(quota)
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
                                authentication.groupExtractor?.let { extractor ->
                                    node("group-extractor") {
                                        attr("attribute-name", extractor.rdnType)
                                        attr("pattern", extractor.pattern)
                                    }
                                }
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
                            attr("check-certificate-status", trustStore.isCheckCertificateStatus.toString())
                            attr("require-client-certificate", trustStore.isRequireClientCertificate.toString())
                        }
                    }
                }
            }
        }
    }
}