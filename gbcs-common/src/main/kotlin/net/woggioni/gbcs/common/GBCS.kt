package net.woggioni.gbcs.common

import net.woggioni.jwo.JWO
import java.net.URI
import java.net.URL
import java.security.MessageDigest

object GBCS {
    fun String.toUrl() : URL = URL.of(URI(this), null)

    const val GBCS_NAMESPACE_URI: String = "urn:net.woggioni.gbcs.server"
    const val GBCS_PREFIX: String = "gbcs"
    const val XML_SCHEMA_NAMESPACE_URI = "http://www.w3.org/2001/XMLSchema-instance"

    fun digest(
        data: ByteArray,
        md: MessageDigest = MessageDigest.getInstance("MD5")
    ): ByteArray {
        md.update(data)
        return md.digest()
    }

    fun digestString(
        data: ByteArray,
        md: MessageDigest = MessageDigest.getInstance("MD5")
    ): String {
        return JWO.bytesToHex(digest(data, md))
    }
}