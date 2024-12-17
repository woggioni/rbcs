package net.woggioni.gbcs

import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest

class UserAuthorizer(private val users: Map<String, Set<Role>>) : Authorizer {

    companion object {
        private val METHOD_MAP = mapOf(
            Role.Reader to setOf(HttpMethod.GET, HttpMethod.HEAD),
            Role.Writer to setOf(HttpMethod.PUT, HttpMethod.POST)
        )
    }

    override fun authorize(user: String, request: HttpRequest) = users[user]?.let { roles ->
        val allowedMethods = roles.asSequence()
            .mapNotNull(METHOD_MAP::get)
            .flatten()
            .toSet()
        request.method() in allowedMethods
    } ?: false
}