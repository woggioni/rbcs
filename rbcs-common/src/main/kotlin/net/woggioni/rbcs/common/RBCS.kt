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

    fun ByteArray.toInt(index : Int = 0) : Long {
        if(index + 4 > size) throw IllegalArgumentException("Not enough bytes to decode a 32 bits integer")
        var value : Long = 0
        for (b in index until index + 4) {
            value = (value shl 8) + (get(b).toInt() and 0xFF)
        }
        return value
    }

    fun ByteArray.toLong(index : Int = 0) : Long {
        if(index + 8 > size) throw IllegalArgumentException("Not enough bytes to decode a 64 bits long integer")
        var value : Long = 0
        for (b in index until index + 8) {
            value = (value shl 8) + (get(b).toInt() and 0xFF)
        }
        return value
    }

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

    fun processCacheKey(key: String, digestAlgorithm: String?) = digestAlgorithm
        ?.let(MessageDigest::getInstance)
        ?.let { md ->
            digest(key.toByteArray(), md)
        } ?: key.toByteArray(Charsets.UTF_8)

    fun Long.toIntOrNull(): Int? {
        return if (this >= Int.MIN_VALUE && this <= Int.MAX_VALUE) {
            toInt()
        } else {
            null
        }
    }
}