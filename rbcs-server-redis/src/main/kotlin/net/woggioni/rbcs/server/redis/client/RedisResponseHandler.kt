package net.woggioni.rbcs.server.redis.client

import io.netty.handler.codec.redis.RedisMessage

interface RedisResponseHandler {

    fun responseReceived(response: RedisMessage)

    fun exceptionCaught(ex: Throwable)
}
