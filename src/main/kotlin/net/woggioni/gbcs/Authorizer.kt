package net.woggioni.gbcs

import io.netty.handler.codec.http.HttpRequest
import net.woggioni.gbcs.api.Role

fun interface Authorizer {
    fun authorize(roles : Set<Role>, request: HttpRequest) : Boolean
}