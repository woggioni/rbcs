package net.woggioni.gcs

import io.netty.handler.codec.http.HttpRequest

fun interface Authorizer {
    fun authorize(user : String, request: HttpRequest) : Boolean
}