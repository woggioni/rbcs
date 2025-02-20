package net.woggioni.rbcs.common

import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordSecurity {

    enum class Algorithm(
        val codeName : String,
        val keyLength : Int,
        val iterations : Int) {
        PBEWithHmacSHA512_224AndAES_256("PBEWithHmacSHA512/224AndAES_256", 64, 1),
        PBEWithHmacSHA1AndAES_256("PBEWithHmacSHA1AndAES_256",64, 1),
        PBEWithHmacSHA384AndAES_128("PBEWithHmacSHA384AndAES_128", 64,1),
        PBEWithHmacSHA384AndAES_256("PBEWithHmacSHA384AndAES_256",64,1),
        PBKDF2WithHmacSHA512("PBKDF2WithHmacSHA512",512, 1),
        PBKDF2WithHmacSHA384("PBKDF2WithHmacSHA384",384, 1);
    }

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

    fun hashPassword(password : String, salt : String? = null, algorithm : Algorithm = Algorithm.PBKDF2WithHmacSHA512) : String {
        val actualSalt = salt?.let(Base64.getDecoder()::decode) ?: SecureRandom().run {
            val result = ByteArray(16)
            nextBytes(result)
            result
        }
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), actualSalt, algorithm.iterations, algorithm.keyLength)
        val factory = SecretKeyFactory.getInstance(algorithm.codeName)
        val hash = factory.generateSecret(spec).encoded
        return String(Base64.getEncoder().encode(concat(hash, actualSalt)))
    }

    fun decodePasswordHash(encodedPasswordHash : String, algorithm: Algorithm = Algorithm.PBKDF2WithHmacSHA512) : Pair<ByteArray, ByteArray> {
        val decoded = Base64.getDecoder().decode(encodedPasswordHash)
        val hash = ByteArray(algorithm.keyLength / 8)
        val salt = ByteArray(decoded.size - algorithm.keyLength / 8)
        System.arraycopy(decoded, 0, hash, 0, hash.size)
        System.arraycopy(decoded, hash.size, salt, 0, salt.size)
        return hash to salt
    }
}