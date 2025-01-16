package net.woggioni.gbcs.client

import io.netty.handler.codec.http.HttpResponseStatus

class HttpException(private val status : HttpResponseStatus) : RuntimeException(status.reasonPhrase()) {

    override val message: String
        get() = "Http status ${status.code()}: ${status.reasonPhrase()}"
}