package net.woggioni.gbcs.server.memcached.client

fun interface ResponseListener {
    fun listen(evt : ResponseEvent)
}