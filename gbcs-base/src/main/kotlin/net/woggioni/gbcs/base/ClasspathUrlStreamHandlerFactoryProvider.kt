package net.woggioni.gbcs.base

import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors


class ClasspathUrlStreamHandlerFactoryProvider : URLStreamHandlerFactory {
    private class Handler(private val classLoader: ClassLoader = ClasspathUrlStreamHandlerFactoryProvider::class.java.classLoader) : URLStreamHandler() {

        override fun openConnection(u: URL): URLConnection? {
            return javaClass.module
                ?.takeIf { m: Module -> m.layer != null }
                ?.let {
                    val path = u.path
                    val i = path.lastIndexOf('/')
                    val packageName = path.substring(0, i).replace('/', '.')
                    val modules = packageMap[packageName]!!
                    ModuleResourceURLConnection(
                        u,
                        modules
                    )
                }
                ?: classLoader.getResource(u.path)?.let(URL::openConnection)
            }
        }

    override fun createURLStreamHandler(protocol: String): URLStreamHandler? {
        return when (protocol) {
            "classpath" -> Handler()
            else -> null
        }
    }

    private class ModuleResourceURLConnection(url: URL?, private val modules: List<Module>) :
        URLConnection(url) {
        override fun connect() {}

        override fun getInputStream(): InputStream? {
            for (module in modules) {
                val result = module.getResourceAsStream(getURL().path)
                if (result != null) return result
            }
            return null
        }
    }

    companion object {
        private val installed = AtomicBoolean(false)
        fun install() {
            if (!installed.getAndSet(true)) {
                URL.setURLStreamHandlerFactory(ClasspathUrlStreamHandlerFactoryProvider())
            }
        }

        private val packageMap: Map<String, List<Module>> by lazy {
            ClasspathUrlStreamHandlerFactoryProvider::class.java.module.layer
                .modules()
                .stream()
                .flatMap { m: Module ->
                    m.packages.stream()
                        .map { p: String -> p to m }
                }
                .collect(
                    Collectors.groupingBy(
                        Pair<String, Module>::first,
                        Collectors.mapping(
                            Pair<String, Module>::second,
                            Collectors.toUnmodifiableList<Module>()
                        )
                    )
                )
        }
    }
}