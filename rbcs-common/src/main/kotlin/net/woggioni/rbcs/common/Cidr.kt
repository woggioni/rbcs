package net.woggioni.rbcs.common

import java.net.InetAddress

data class Cidr private constructor(
    val networkAddress: InetAddress,
    val prefixLength: Int
) {
    companion object {
        fun from(cidr: String) : Cidr {
            val separator = cidr.indexOf("/")
            if(separator < 0) {
                throw IllegalArgumentException("Invalid CIDR format: $cidr")
            }
            val networkAddress = InetAddress.getByName(cidr.substring(0, separator))
            val prefixLength = cidr.substring(separator + 1, cidr.length).toInt()


            // Validate prefix length
            val maxPrefix = if (networkAddress.address.size == 4) 32 else 128
            require(prefixLength in 0..maxPrefix) { "Invalid prefix length: $prefixLength" }
            return Cidr(networkAddress, prefixLength)
        }
    }

    fun contains(address: InetAddress): Boolean {
        val networkBytes = networkAddress.address
        val addressBytes = address.address

        if (networkBytes.size != addressBytes.size) {
            return false
        }


        // Calculate how many full bytes and remaining bits to check
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8


        // Check full bytes
        for (i in 0..<fullBytes) {
            if (networkBytes[i] != addressBytes[i]) {
                return false
            }
        }


        // Check remaining bits if any
        if (remainingBits > 0 && fullBytes < networkBytes.size) {
            val mask = (0xFF shl (8 - remainingBits)).toByte()
            if ((networkBytes[fullBytes].toInt() and mask.toInt()) != (addressBytes[fullBytes].toInt() and mask.toInt())) {
                return false
            }
        }

        return true
    }

    override fun toString(): String {
        return networkAddress.hostAddress + "/" + prefixLength
    }
}