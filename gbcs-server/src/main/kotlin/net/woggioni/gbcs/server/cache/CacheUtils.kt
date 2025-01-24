package net.woggioni.gbcs.server.cache

import net.woggioni.jwo.JWO
import java.security.MessageDigest

object CacheUtils {
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