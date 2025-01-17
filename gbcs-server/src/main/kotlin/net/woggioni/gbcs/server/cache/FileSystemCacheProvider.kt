package net.woggioni.gbcs.server.cache

import net.woggioni.gbcs.api.CacheProvider
import net.woggioni.gbcs.common.GBCS
import net.woggioni.gbcs.common.Xml
import net.woggioni.gbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import java.time.Duration
import java.util.zip.Deflater

class FileSystemCacheProvider : CacheProvider<FileSystemCacheConfiguration> {

    override fun getXmlSchemaLocation() = "classpath:net/woggioni/gbcs/server/schema/gbcs.xsd"

    override fun getXmlType() = "fileSystemCacheType"

    override fun getXmlNamespace() = "urn:net.woggioni.gbcs.server"

    override fun deserialize(el: Element): FileSystemCacheConfiguration {
        val path = el.renderAttribute("path")
            ?.let(Path::of)
        val maxAge = el.renderAttribute("max-age")
            ?.let(Duration::parse)
            ?: Duration.ofDays(1)
        val enableCompression = el.renderAttribute("enable-compression")
            ?.let(String::toBoolean)
            ?: true
        val compressionLevel = el.renderAttribute("compression-level")
            ?.let(String::toInt)
            ?: Deflater.DEFAULT_COMPRESSION
        val digestAlgorithm = el.renderAttribute("digest") ?: "MD5"

        return FileSystemCacheConfiguration(
            path,
            maxAge,
            digestAlgorithm,
            enableCompression,
            compressionLevel
        )
    }

    override fun serialize(doc: Document, cache : FileSystemCacheConfiguration) = cache.run {
        val result = doc.createElement("cache")
        Xml.of(doc, result) {
            val prefix = doc.lookupPrefix(GBCS.GBCS_NAMESPACE_URI)
            attr("xs:type", "${prefix}:fileSystemCacheType", GBCS.XML_SCHEMA_NAMESPACE_URI)
            attr("path", root.toString())
            attr("max-age", maxAge.toString())
            digestAlgorithm?.let { digestAlgorithm ->
                attr("digest", digestAlgorithm)
            }
            attr("enable-compression", compressionEnabled.toString())
            compressionLevel.takeIf {
                it != Deflater.DEFAULT_COMPRESSION
            }?.let {
                attr("compression-level", it.toString())
            }
        }
        result
    }
}
