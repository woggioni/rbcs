package net.woggioni.rbcs.common


data class HostAndPort(val host: String, val port: Int = 0) {
    override fun toString(): String {
        return "$host:$port"
    }
}