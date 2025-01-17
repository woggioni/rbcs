package net.woggioni.gbcs.server.auth

import io.netty.handler.codec.http.HttpRequest
import net.woggioni.gbcs.api.Role

fun interface Authorizer {
    fun authorize(roles : Set<Role>, request: HttpRequest) : Boolean
}