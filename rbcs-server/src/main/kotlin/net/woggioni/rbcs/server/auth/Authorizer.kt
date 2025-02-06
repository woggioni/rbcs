package net.woggioni.rbcs.server.auth

import io.netty.handler.codec.http.HttpRequest
import net.woggioni.rbcs.api.Role

fun interface Authorizer {
    fun authorize(roles : Set<Role>, request: HttpRequest) : Boolean
}