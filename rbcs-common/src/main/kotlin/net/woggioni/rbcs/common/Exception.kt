package net.woggioni.rbcs.common

class ResourceNotFoundException(msg : String? = null, cause: Throwable? = null) : RuntimeException(msg, cause) {
}

class ModuleNotFoundException(msg : String? = null, cause: Throwable? = null) : RuntimeException(msg, cause) {
}