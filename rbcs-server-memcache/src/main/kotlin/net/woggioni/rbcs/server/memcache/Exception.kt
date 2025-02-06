package net.woggioni.rbcs.server.memcache

class MemcacheException(status : Short, msg : String? = null, cause : Throwable? = null)
    : RuntimeException(msg ?: "Memcached status $status", cause)