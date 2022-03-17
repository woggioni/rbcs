package net.woggioni.gbcs

import java.nio.file.Path

data class Configuration(
    val cacheFolder : Path,
    val host : String,
    val port : Int,
    val users : Map<String, Set<Role>>
)