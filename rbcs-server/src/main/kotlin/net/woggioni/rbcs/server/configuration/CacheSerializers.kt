package net.woggioni.rbcs.server.configuration

import net.woggioni.rbcs.api.CacheProvider
import net.woggioni.rbcs.api.Configuration
import java.util.ServiceLoader

object CacheSerializers {
    val index = (Configuration::class.java.module.layer?.let { layer ->
        ServiceLoader.load(layer, CacheProvider::class.java)
    } ?: ServiceLoader.load(CacheProvider::class.java))
        .asSequence()
        .map {
            (it.xmlNamespace to it.xmlType) to it
        }.toMap()
}