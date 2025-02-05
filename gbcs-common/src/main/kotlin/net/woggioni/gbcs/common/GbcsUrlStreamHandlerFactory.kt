package net.woggioni.gbcs.common

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.spi.URLStreamHandlerProvider
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors


class GbcsUrlStreamHandlerFactory : URLStreamHandlerProvider() {

    private class ClasspathHandler(private val classLoader: ClassLoader = GbcsUrlStreamHandlerFactory::class.java.classLoader) :
        URLStreamHandler() {

        override fun openConnection(u: URL): URLConnection? {
            return javaClass.module
                ?.takeIf { m: Module -> m.layer != null }
                ?.let {
                    val path = u.path
                    val i = path.lastIndexOf('/')
                    val packageName = path.substring(0, i).replace('/', '.')
                    val modules = packageMap[packageName]!!
                    ClasspathResourceURLConnection(
                        u,
                        modules
                    )
                }
                ?: classLoader.getResource(u.path)?.let(URL::openConnection)
        }
    }

    private class JpmsHandler : URLStreamHandler() {

        override fun openConnection(u: URL): URLConnection {
            val moduleName = u.host
            val thisModule = javaClass.module
            val sourceModule =
                thisModule
                ?.let(Module::getLayer)
                ?.let { layer: ModuleLayer ->
                    layer.findModule(moduleName).orElse(null)
                } ?: if(thisModule.layer == null) {
                    thisModule
                } else throw ModuleNotFoundException("Module '$moduleName' not found")

            return JpmsResourceURLConnection(u, sourceModule)
        }
    }

    private class JpmsResourceURLConnection(url: URL, private val module: Module) : URLConnection(url) {
        override fun connect() {
        }

        @Throws(IOException::class)
        override fun getInputStream(): InputStream {
            val resource = getURL().path
            return module.getResourceAsStream(resource)
                ?: throw ResourceNotFoundException("Resource '$resource' not found in module '${module.name}'")
        }
    }

    override fun createURLStreamHandler(protocol: String): URLStreamHandler? {
        return when (protocol) {
            "classpath" -> ClasspathHandler()
            "jpms" -> JpmsHandler()
            else -> null
        }
    }

    private class ClasspathResourceURLConnection(url: URL?, private val modules: List<Module>) :
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
                URL.setURLStreamHandlerFactory(GbcsUrlStreamHandlerFactory())
            }
        }

        private val packageMap: Map<String, List<Module>> by lazy {
            GbcsUrlStreamHandlerFactory::class.java.module.layer
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