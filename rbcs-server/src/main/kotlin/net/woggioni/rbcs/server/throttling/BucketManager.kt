package net.woggioni.rbcs.server.throttling

import java.net.InetSocketAddress
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import net.woggioni.jwo.Bucket
import net.woggioni.rbcs.api.Configuration

class BucketManager private constructor(
    private val bucketsByUser: Map<Configuration.User, List<Bucket>> = HashMap(),
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
            val bucketsByUser = cfg.users.values.asSequence().map { user ->
                val buckets = (
                        user.quota
                            ?.let { quota ->
                                sequenceOf(quota)
                            } ?: user.groups.asSequence()
                                .mapNotNull(Configuration.Group::getUserQuota)
                ).map { quota ->
                    Bucket.local(
                        quota.maxAvailableCalls,
                        quota.calls,
                        quota.period,
                        quota.initialAvailableCalls
                    )
                }.toList()
                user to buckets
            }.toMap()
            val bucketsByGroup = cfg.groups.values.asSequence().filter {
                it.groupQuota != null
            }.map { group ->
                val quota = group.groupQuota
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
