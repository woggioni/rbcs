package net.woggioni.gbcs.base

import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordSecurity {
    private const val KEY_LENGTH = 256

    private fun concat(arr1: ByteArray, arr2: ByteArray): ByteArray {
        val result = ByteArray(arr1.size + arr2.size)
        var j = 0
        for(element in arr1) {
            result[j] = element
            j += 1
        }
        for(element in arr2) {
            result[j] = element
            j += 1
        }
        return result
    }

    fun hashPassword(password : String, salt : String? = null) : String {
        val actualSalt = salt?.let(Base64.getDecoder()::decode) ?: SecureRandom().run {
            val result = ByteArray(16)
            nextBytes(result)
            result
        }
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), actualSalt, 10, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val hash = factory.generateSecret(spec).encoded
        return String(Base64.getEncoder().encode(concat(hash, actualSalt)))
    }

    fun decodePasswordHash(passwordHash : String) : Pair<ByteArray, ByteArray> {
        val decoded = Base64.getDecoder().decode(passwordHash)
        val hash = ByteArray(KEY_LENGTH / 8)
        val salt = ByteArray(decoded.size - KEY_LENGTH / 8)
        System.arraycopy(decoded, 0, hash, 0, hash.size)
        System.arraycopy(decoded, hash.size, salt, 0, salt.size)
        return hash to salt
    }
}