package net.woggioni.gbcs.configuration

import net.woggioni.gbcs.api.CacheProvider
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.base.GBCS
import net.woggioni.gbcs.base.Xml
import org.w3c.dom.Document

object Serializer {

    fun serialize(conf : Configuration) : Document {

        val schemaLocations = CacheSerializers.index.values.asSequence().map {
            it.xmlNamespace to it.xmlSchemaLocation
        }.toMap()
        return Xml.of(GBCS.GBCS_NAMESPACE_URI, GBCS.GBCS_PREFIX + ":server") {
            attr("useVirtualThreads", conf.isUseVirtualThread.toString())
//            attr("xmlns:xs", GradleBuildCacheServer.XML_SCHEMA_NAMESPACE_URI)
            val value = schemaLocations.asSequence().map { (k, v) -> "$k $v" }.joinToString(" ")
            attr("xs:schemaLocation", value , namespaceURI = GBCS.XML_SCHEMA_NAMESPACE_URI)

            conf.serverPath
                ?.takeIf(String::isNotEmpty)
                ?.let { serverPath ->
                attr("path", serverPath)
            }
            node("bind") {
                attr("host", conf.host)
                attr("port", conf.port.toString())
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
                        }
                    }
                }
            }
        }
    }
}