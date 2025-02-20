package net.woggioni.rbcs.common

import net.woggioni.rbcs.common.PasswordSecurity.decodePasswordHash
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.security.Provider
import java.security.Security
import java.util.Base64


class PasswordHashingTest {

    @EnumSource(PasswordSecurity.Algorithm::class)
    @ParameterizedTest
    fun test(algo: PasswordSecurity.Algorithm) {
        val password = "password"
        val encoded = hashPassword(password, algorithm = algo)
        val (_, salt) = decodePasswordHash(encoded, algo)
        Assertions.assertEquals(encoded,
            hashPassword(password, salt = salt.let(Base64.getEncoder()::encodeToString), algorithm = algo)
        )
    }

    @Test
    fun listAvailableAlgorithms() {
        Security.getProviders().asSequence()
            .flatMap { provider: Provider -> provider.services.asSequence() }
            .filter { service: Provider.Service -> "SecretKeyFactory" == service.type }
            .map(Provider.Service::getAlgorithm)
            .forEach {
                println(it)
            }

    }
}