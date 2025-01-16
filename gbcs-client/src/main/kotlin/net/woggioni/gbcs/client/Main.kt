package net.woggioni.gbcs.client
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.random.Random


//object Main {
//    @JvmStatic
//    fun main(vararg args : String) {
//        val pwd = "PO%!*bW9p'Zp#=uu\$fl{Ij`Ad.8}x#ho".toCharArray()
//        val keystore = KeyStore.getInstance("PKCS12").apply{
//            Files.newInputStream(Path.of("/home/woggioni/ssl/woggioni@c962475fa38.pfx")).use {
//                load(it, pwd)
//            }
//        }
//        val key = keystore.getKey("woggioni@c962475fa38", pwd) as PrivateKey
//        val certChain = keystore.getCertificateChain("woggioni@c962475fa38").asSequence()
//            .map { it as X509Certificate }
//            .toList()
//            .toTypedArray()
//        GbcsClient.Configuration(
//            serverURI = URI("https://gbcs.woggioni.net/"),
//            GbcsClient.TlsClientAuthenticationCredentials(
//                key, certChain
//            )
//        ).let(::GbcsClient).use { client ->
//            val random = Random(101325)
//            val entry = "something" to ByteArray(0x1000).also(random::nextBytes)
//            client.put(entry.first, entry.second)
//            val retrieved = client.get(entry.first).get()
//            println(retrieved.contentEquals(entry.second))
//        }
//    }
//}