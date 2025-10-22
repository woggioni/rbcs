# RBCS Docker images
There are 3 image flavours:
- vanilla
- memcache
- native

The `vanilla` image only contains the envelope 
jar file with no plugins and is based on `eclipse-temurin:25-jre-alpine`

The `memcache` image is similar to the `vanilla` image, except that it also contains
the `rbcs-server-memcache` plugin in the `plugins` folder, use this image if you don't want to use the `native`
image and want to use memcache as the cache backend

The `native` image contains a native, statically-linked executable created with GraalVM Native Image
that has no userspace dependencies. It also embeds the memcache plugin inside the executable.
Use this image for maximum efficiency and minimal memory footprint. 

The `jlink` image contains a custom Java runtime created with GraalVM's Jlink
that only depends on glibc. It also contains the memcache plugin in the module path.
Use this image for best performance.

## Which image should I use?
The `native` image uses Java's SerialGC, so it's ideal for constrained environment like containers or small servers,
if you have a lot of resources and want to squeeze out the maximum throughput you should consider the
`vanilla` or `memcache` image, then choose and fine tune the garbage collector.

Also the `native` image is only available for the `x86_64` architecture at the moment, 
while `vanilla` and `memcache` also ship a `aarch64` variant.
