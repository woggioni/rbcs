package net.woggioni.gbcs.server.memcached

class MemcachedException(status : Short, msg : String? = null, cause : Throwable? = null)
    : RuntimeException(msg ?: "Memcached status $status", cause)