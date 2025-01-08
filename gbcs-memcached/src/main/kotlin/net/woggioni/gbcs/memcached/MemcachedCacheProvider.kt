package net.woggioni.gbcs.memcached

import net.rubyeye.xmemcached.transcoders.CompressionMode
import net.woggioni.gbcs.api.CacheProvider
import net.woggioni.gbcs.base.GBCS
import net.woggioni.gbcs.base.HostAndPort
import net.woggioni.gbcs.base.Xml
import net.woggioni.gbcs.base.Xml.Companion.asIterable
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Duration
import java.util.zip.Deflater

class MemcachedCacheProvider : CacheProvider<MemcachedCacheConfiguration> {
    override fun getXmlSchemaLocation() = "classpath:net/woggioni/gbcs/memcached/schema/gbcs-memcached.xsd"

    override fun getXmlType() = "memcachedCacheType"

    override fun getXmlNamespace()= "urn:net.woggioni.gbcs-memcached"

    override fun deserialize(el: Element): MemcachedCacheConfiguration {
        val servers = mutableListOf<HostAndPort>()
        val maxAge = el.getAttribute("max-age")
            .takeIf(String::isNotEmpty)
            ?.let(Duration::parse)
            ?: Duration.ofDays(1)
        val maxSize = el.getAttribute("max-size")
            .takeIf(String::isNotEmpty)
            ?.let(String::toInt)
            ?: 0x100000
        val enableCompression = el.getAttribute("enable-compression")
            .takeIf(String::isNotEmpty)
            ?.let(String::toBoolean)
            ?: false
        val compressionMode = el.getAttribute("compression-mode")
            .takeIf(String::isNotEmpty)
            ?.let {
                when(it) {
                    "gzip" -> CompressionMode.GZIP
                    "zip" -> CompressionMode.ZIP
                    else -> CompressionMode.ZIP
                }
            }
            ?: CompressionMode.ZIP
        val digestAlgorithm = el.getAttribute("digest").takeIf(String::isNotEmpty)
        for (child in el.asIterable()) {
            when (child.nodeName) {
                "server" -> {
                    servers.add(HostAndPort(child.getAttribute("host"), child.getAttribute("port").toInt()))
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

    override fun serialize(doc: Document, cache : MemcachedCacheConfiguration) = cache.run {
        val result = doc.createElementNS(xmlNamespace,"cache")
        Xml.of(doc, result) {
            attr("xs:type", xmlType, GBCS.XML_SCHEMA_NAMESPACE_URI)
            for (server in servers) {
                node("server", xmlNamespace) {
                    attr("host", server.host)
                    attr("port", server.port.toString())
                }
            }
            attr("max-age", maxAge.toString())
            attr("max-size", maxSize.toString())
            digestAlgorithm?.let { digestAlgorithm ->
                attr("digest", digestAlgorithm)
            }
            attr("compression-mode", when(compressionMode) {
                CompressionMode.GZIP -> "gzip"
                CompressionMode.ZIP -> "zip"
            })
        }
        result
    }
}
