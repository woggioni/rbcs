package net.woggioni.rbcs.server.redis

class RedisException(msg: String, cause: Throwable? = null)
    : RuntimeException(msg, cause)
