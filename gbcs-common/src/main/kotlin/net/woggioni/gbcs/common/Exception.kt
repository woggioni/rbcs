package net.woggioni.gbcs.common

class ResourceNotFoundException(msg : String? = null, cause: Throwable? = null) : RuntimeException(msg, cause) {
}

class ModuleNotFoundException(msg : String? = null, cause: Throwable? = null) : RuntimeException(msg, cause) {
}