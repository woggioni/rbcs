package net.woggioni.rbcs.common

import net.woggioni.jwo.JWO
import java.net.URI
import java.net.URL
import java.security.MessageDigest

object RBCS {
    fun String.toUrl() : URL = URL.of(URI(this), null)

    const val RBCS_NAMESPACE_URI: String = "urn:net.woggioni.rbcs.server"
    const val RBCS_PREFIX: String = "rbcs"
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