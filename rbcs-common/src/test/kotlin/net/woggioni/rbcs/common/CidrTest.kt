package net.woggioni.rbcs.common

import java.net.InetAddress
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CidrTest {
    class CidrTest {
        @Test
        fun test() {
            val cidr = Cidr.from("2a02:4780:12:368b::1/128")
            Assertions.assertTrue {
                cidr.contains(InetAddress.ofLiteral("2a02:4780:12:368b::1"))
            }
        }
    }
}