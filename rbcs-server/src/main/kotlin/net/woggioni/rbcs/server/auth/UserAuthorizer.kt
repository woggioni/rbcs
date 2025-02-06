package net.woggioni.rbcs.server.auth

import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import net.woggioni.rbcs.api.Role

class RoleAuthorizer : Authorizer {

    companion object {
        private val METHOD_MAP = mapOf(
            Role.Reader to setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.TRACE),
            Role.Writer to setOf(HttpMethod.PUT, HttpMethod.POST)
        )
    }

    override fun authorize(roles: Set<Role>, request: HttpRequest) : Boolean {
        val allowedMethods = roles.asSequence()
            .mapNotNull(METHOD_MAP::get)
            .flatten()
            .toSet()
        return request.method() in allowedMethods
    }
}