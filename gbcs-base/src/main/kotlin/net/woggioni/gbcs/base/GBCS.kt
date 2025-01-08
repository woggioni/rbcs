package net.woggioni.gbcs.base

import java.net.URI
import java.net.URL

object GBCS {
    fun String.toUrl() : URL = URL.of(URI(this), null)

    const val GBCS_NAMESPACE_URI: String = "urn:net.woggioni.gbcs"
    const val GBCS_PREFIX: String = "gbcs"
    const val XML_SCHEMA_NAMESPACE_URI = "http://www.w3.org/2001/XMLSchema-instance"
}