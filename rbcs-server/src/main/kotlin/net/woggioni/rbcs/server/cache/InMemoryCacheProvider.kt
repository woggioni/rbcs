package net.woggioni.rbcs.server.cache

import net.woggioni.rbcs.api.CacheProvider
import net.woggioni.rbcs.common.RBCS
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Duration
import java.util.zip.Deflater

class InMemoryCacheProvider : CacheProvider<InMemoryCacheConfiguration> {

    override fun getXmlSchemaLocation() = "classpath:net/woggioni/rbcs/server/schema/rbcs.xsd"

    override fun getXmlType() = "inMemoryCacheType"

    override fun getXmlNamespace() = "urn:net.woggioni.rbcs.server"

    override fun deserialize(el: Element): InMemoryCacheConfiguration {
        val maxAge = el.renderAttribute("max-age")
            ?.let(Duration::parse)
            ?: Duration.ofDays(1)
        val maxSize = el.renderAttribute("max-size")
            ?.let(java.lang.Long::decode)
            ?: 0x1000000
        val enableCompression = el.renderAttribute("enable-compression")
            ?.let(String::toBoolean)
            ?: true
        val compressionLevel = el.renderAttribute("compression-level")
            ?.let(String::toInt)
            ?: Deflater.DEFAULT_COMPRESSION
        val digestAlgorithm = el.renderAttribute("digest") ?: "MD5"
        val chunkSize = el.renderAttribute("chunk-size")
            ?.let(Integer::decode)
            ?: 0x10000
        return InMemoryCacheConfiguration(
            maxAge,
            maxSize,
            digestAlgorithm,
            enableCompression,
            compressionLevel,
            chunkSize
        )
    }

    override fun serialize(doc: Document, cache : InMemoryCacheConfiguration) = cache.run {
        val result = doc.createElement("cache")
        Xml.of(doc, result) {
            val prefix = doc.lookupPrefix(RBCS.RBCS_NAMESPACE_URI)
            attr("xs:type", "${prefix}:inMemoryCacheType", RBCS.XML_SCHEMA_NAMESPACE_URI)
            attr("max-age", maxAge.toString())
            attr("max-size", maxSize.toString())
            digestAlgorithm?.let { digestAlgorithm ->
                attr("digest", digestAlgorithm)
            }
            attr("enable-compression", compressionEnabled.toString())
            compressionLevel.takeIf {
                it != Deflater.DEFAULT_COMPRESSION
            }?.let {
                attr("compression-level", it.toString())
            }
            attr("chunk-size", chunkSize.toString())
        }
        result
    }
}
