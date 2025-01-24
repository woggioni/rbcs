package net.woggioni.gbcs.server.throttling

import net.woggioni.gbcs.api.Configuration
import net.woggioni.jwo.Bucket
import java.net.InetSocketAddress
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

class BucketManager private constructor(
    private val bucketsByUser: Map<Configuration.User, Bucket> = HashMap(),
    private val bucketsByGroup: Map<Configuration.Group, Bucket> = HashMap(),
    loader: Function<InetSocketAddress, Bucket>?
) {

    private class BucketsByAddress(
        private val map: MutableMap<ByteArrayKey, Bucket>,
        private val loader: Function<InetSocketAddress, Bucket>
    ) {
        fun getBucket(socketAddress : InetSocketAddress) = map.computeIfAbsent(ByteArrayKey(socketAddress.address.address))  {
            loader.apply(socketAddress)
        }
    }

    private val bucketsByAddress: BucketsByAddress? = loader?.let {
        BucketsByAddress(ConcurrentHashMap(), it)
    }

    private class ByteArrayKey(val array: ByteArray) {
        override fun equals(other: Any?) = (other as? ByteArrayKey)?.let { bak ->
            array contentEquals bak.array
        } ?: false

        override fun hashCode() = Arrays.hashCode(array)
    }

    fun getBucketByAddress(address : InetSocketAddress) : Bucket? {
        return bucketsByAddress?.getBucket(address)
    }

    fun getBucketByUser(user : Configuration.User) = bucketsByUser[user]
    fun getBucketByGroup(group : Configuration.Group) = bucketsByGroup[group]

    companion object {
        fun from(cfg : Configuration) : BucketManager {
            val bucketsByUser = cfg.users.values.asSequence().filter {
                it.quota != null
            }.map { user ->
                val quota = user.quota
                val bucket = Bucket.local(
                    quota.maxAvailableCalls,
                    quota.calls,
                    quota.period,
                    quota.initialAvailableCalls
                )
                user to bucket
            }.toMap()
            val bucketsByGroup = cfg.groups.values.asSequence().filter {
                it.quota != null
            }.map { group ->
                val quota = group.quota
                val bucket = Bucket.local(
                    quota.maxAvailableCalls,
                    quota.calls,
                    quota.period,
                    quota.initialAvailableCalls
                )
                group to bucket
            }.toMap()
            return BucketManager(
                bucketsByUser,
                bucketsByGroup,
                cfg.users[""]?.quota?.let { anonymousUserQuota ->
                    Function {
                        Bucket.local(
                            anonymousUserQuota.maxAvailableCalls,
                            anonymousUserQuota.calls,
                            anonymousUserQuota.period,
                            anonymousUserQuota.initialAvailableCalls
                        )
                    }
                }
            )
        }
    }
}
