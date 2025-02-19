package net.woggioni.rbcs.server.memcache

import net.woggioni.rbcs.api.CacheProvider
import net.woggioni.rbcs.api.exception.ConfigurationException
import net.woggioni.rbcs.common.RBCS
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.common.Xml.Companion.asIterable
import net.woggioni.rbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Duration
import java.time.temporal.ChronoUnit


class MemcacheCacheProvider : CacheProvider<MemcacheCacheConfiguration> {
    override fun getXmlSchemaLocation() = "jpms://net.woggioni.rbcs.server.memcache/net/woggioni/rbcs/server/memcache/schema/rbcs-memcache.xsd"

    override fun getXmlType() = "memcacheCacheType"

    override fun getXmlNamespace() = "urn:net.woggioni.rbcs.server.memcache"

    val xmlNamespacePrefix : String
        get() = "rbcs-memcache"

    override fun deserialize(el: Element): MemcacheCacheConfiguration {
        val servers = mutableListOf<MemcacheCacheConfiguration.Server>()
        val maxAge = el.renderAttribute("max-age")
            ?.let(Duration::parse)
            ?: Duration.ofDays(1)
        val chunkSize = el.renderAttribute("chunk-size")
            ?.let(Integer::decode)
            ?: 0x10000
        val compressionLevel = el.renderAttribute("compression-level")
            ?.let(Integer::decode)
            ?: -1
        val compressionMode = el.renderAttribute("compression-mode")
            ?.let {
                when (it) {
                    "deflate" -> MemcacheCacheConfiguration.CompressionMode.DEFLATE
                    else -> MemcacheCacheConfiguration.CompressionMode.DEFLATE
                }
            }
        val digestAlgorithm = el.renderAttribute("digest")
        for (child in el.asIterable()) {
            when (child.nodeName) {
                "server" -> {
                    val host = child.renderAttribute("host") ?: throw ConfigurationException("host attribute is required")
                    val port = child.renderAttribute("port")?.toInt() ?: throw ConfigurationException("port attribute is required")
                    val maxConnections = child.renderAttribute("max-connections")?.toInt() ?: 1
                    val connectionTimeout = child.renderAttribute("connection-timeout")
                        ?.let(Duration::parse)
                        ?.let(Duration::toMillis)
                        ?.let(Long::toInt)
                        ?: 10000
                    servers.add(MemcacheCacheConfiguration.Server(HostAndPort(host, port), connectionTimeout, maxConnections))
                }
            }
        }

        return MemcacheCacheConfiguration(
            servers,
            maxAge,
            digestAlgorithm,
            compressionMode,
            compressionLevel,
            chunkSize
        )
    }

    override fun serialize(doc: Document, cache: MemcacheCacheConfiguration) = cache.run {
        val result = doc.createElement("cache")
        Xml.of(doc, result) {
            attr("xmlns:${xmlNamespacePrefix}", xmlNamespace, namespaceURI = "http://www.w3.org/2000/xmlns/")
            attr("xs:type", "${xmlNamespacePrefix}:$xmlType", RBCS.XML_SCHEMA_NAMESPACE_URI)
            for (server in servers) {
                node("server") {
                    attr("host", server.endpoint.host)
                    attr("port", server.endpoint.port.toString())
                    server.connectionTimeoutMillis?.let { connectionTimeoutMillis ->
                        attr("connection-timeout", Duration.of(connectionTimeoutMillis.toLong(), ChronoUnit.MILLIS).toString())
                    }
                    attr("max-connections", server.maxConnections.toString())
                }
            }
            attr("max-age", maxAge.toString())
            attr("chunk-size", chunkSize.toString())
            digestAlgorithm?.let { digestAlgorithm ->
                attr("digest", digestAlgorithm)
            }
            compressionMode?.let { compressionMode ->
                attr(
                    "compression-mode", when (compressionMode) {
                        MemcacheCacheConfiguration.CompressionMode.DEFLATE -> "deflate"
                    }
                )
            }
            attr("compression-level", compressionLevel.toString())
        }
        result
    }
}
