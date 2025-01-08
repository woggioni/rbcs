package net.woggioni.gbcs.configuration

import net.woggioni.gbcs.api.CacheProvider
import net.woggioni.gbcs.api.Configuration
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