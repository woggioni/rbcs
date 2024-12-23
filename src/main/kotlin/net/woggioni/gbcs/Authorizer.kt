package net.woggioni.gbcs

import io.netty.handler.codec.http.HttpRequest

fun interface Authorizer {
    fun authorize(roles : Set<Role>, request: HttpRequest) : Boolean
}