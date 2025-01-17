package net.woggioni.gbcs.server.memcached

import net.rubyeye.xmemcached.transcoders.CompressionMode
import net.woggioni.gbcs.api.CacheProvider
import net.woggioni.gbcs.api.exception.ConfigurationException
import net.woggioni.gbcs.common.GBCS
import net.woggioni.gbcs.common.HostAndPort
import net.woggioni.gbcs.common.Xml
import net.woggioni.gbcs.common.Xml.Companion.asIterable
import net.woggioni.gbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Duration

class MemcachedCacheProvider : CacheProvider<MemcachedCacheConfiguration> {
    override fun getXmlSchemaLocation() = "jpms://net.woggioni.gbcs.server.memcached/net/woggioni/gbcs/server/memcached/schema/gbcs-memcached.xsd"

    override fun getXmlType() = "memcachedCacheType"

    override fun getXmlNamespace() = "urn:net.woggioni.gbcs.server.memcached"

    val xmlNamespacePrefix : String
        get() = "gbcs-memcached"

    override fun deserialize(el: Element): MemcachedCacheConfiguration {
        val servers = mutableListOf<HostAndPort>()
        val maxAge = el.renderAttribute("max-age")
            ?.let(Duration::parse)
            ?: Duration.ofDays(1)
        val maxSize = el.renderAttribute("max-size")
            ?.let(String::toInt)
            ?: 0x100000
        val compressionMode = el.renderAttribute("compression-mode")
            ?.let {
                when (it) {
                    "gzip" -> CompressionMode.GZIP
                    "zip" -> CompressionMode.ZIP
                    else -> CompressionMode.ZIP
                }
            }
            ?: CompressionMode.ZIP
        val digestAlgorithm = el.renderAttribute("digest")
        for (child in el.asIterable()) {
            when (child.nodeName) {
                "server" -> {
                    val host = child.renderAttribute("host") ?: throw ConfigurationException("host attribute is required")
                    val port = child.renderAttribute("port")?.toInt() ?: throw ConfigurationException("port attribute is required")
                    servers.add(HostAndPort(host, port))
                }
            }
        }

        return MemcachedCacheConfiguration(
            servers,
            maxAge,
            maxSize,
            digestAlgorithm,
            compressionMode,
        )
    }

    override fun serialize(doc: Document, cache: MemcachedCacheConfiguration) = cache.run {
        val result = doc.createElement("cache")
        Xml.of(doc, result) {
            attr("xmlns:${xmlNamespacePrefix}", xmlNamespace, namespaceURI = "http://www.w3.org/2000/xmlns/")

            attr("xs:type", "${xmlNamespacePrefix}:$xmlType", GBCS.XML_SCHEMA_NAMESPACE_URI)
            for (server in servers) {
                node("server") {
                    attr("host", server.host)
                    attr("port", server.port.toString())
                }
            }
            attr("max-age", maxAge.toString())
            attr("max-size", maxSize.toString())
            digestAlgorithm?.let { digestAlgorithm ->
                attr("digest", digestAlgorithm)
            }
            attr(
                "compression-mode", when (compressionMode) {
                    CompressionMode.GZIP -> "gzip"
                    CompressionMode.ZIP -> "zip"
                }
            )
        }
        result
    }
}
