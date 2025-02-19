package net.woggioni.rbcs.server.cache

import net.woggioni.rbcs.api.CacheProvider
import net.woggioni.rbcs.common.RBCS
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.common.Xml.Companion.renderAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import java.time.Duration
import java.util.zip.Deflater

class FileSystemCacheProvider : CacheProvider<FileSystemCacheConfiguration> {

    override fun getXmlSchemaLocation() = "classpath:net/woggioni/rbcs/server/schema/rbcs.xsd"

    override fun getXmlType() = "fileSystemCacheType"

    override fun getXmlNamespace() = "urn:net.woggioni.rbcs.server"

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
        val chunkSize = el.renderAttribute("chunk-size")
            ?.let(Integer::decode)
            ?: 0x10000

        return FileSystemCacheConfiguration(
            path,
            maxAge,
            digestAlgorithm,
            enableCompression,
            compressionLevel,
            chunkSize
        )
    }

    override fun serialize(doc: Document, cache : FileSystemCacheConfiguration) = cache.run {
        val result = doc.createElement("cache")
        Xml.of(doc, result) {
            val prefix = doc.lookupPrefix(RBCS.RBCS_NAMESPACE_URI)
            attr("xs:type", "${prefix}:fileSystemCacheType", RBCS.XML_SCHEMA_NAMESPACE_URI)
            root?.let {
                attr("path", it.toString())
            }
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
            attr("chunk-size", chunkSize.toString())
        }
        result
    }
}
