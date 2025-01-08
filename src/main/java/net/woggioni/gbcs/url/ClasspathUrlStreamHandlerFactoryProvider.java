package net.woggioni.gbcs.url;

import net.woggioni.jwo.Fun;
import net.woggioni.jwo.LazyValue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ClasspathUrlStreamHandlerFactoryProvider implements URLStreamHandlerFactory {

    private static final AtomicBoolean installed = new AtomicBoolean(false);
    public static void install() {
        if(!installed.getAndSet(true)) {
            URL.setURLStreamHandlerFactory(new ClasspathUrlStreamHandlerFactoryProvider());
        }
    }

    private static final LazyValue<Map<String, List<Module>>> packageMap = LazyValue.of(() ->
            ClasspathUrlStreamHandlerFactoryProvider.class.getModule().getLayer()
                .modules()
                .stream()
                .flatMap(m -> m.getPackages().stream().map(p -> Map.entry(p, m)))
                .collect(
                    Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(
                            Map.Entry::getValue,
                            Collectors.toUnmodifiableList()
                        )
                    )
                ),
            LazyValue.ThreadSafetyMode.NONE
    );


    private static class Handler extends URLStreamHandler {
        private final ClassLoader classLoader;

        public Handler() {
            this.classLoader = getClass().getClassLoader();
        }

        public Handler(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            return Optional.ofNullable(getClass().getModule())
                    .filter(m -> m.getLayer() != null)
                    .map(m -> {
                        final var path = u.getPath();
                        final var i = path.lastIndexOf('/');
                        final var packageName = path.substring(0, i).replace('/', '.');
                        final var modules = packageMap.get().get(packageName);
                        return (URLConnection) new ModuleResourceURLConnection(u, modules);
                    })
                    .or(() -> Optional.of(classLoader).map(cl -> cl.getResource(u.getPath())).map((Fun<URL, URLConnection>) URL::openConnection))
                    .orElse(null);
        }
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        URLStreamHandler result;
        switch (protocol) {
            case "classpath":
                result = new Handler();
                break;
            default:
                result = null;
        }
        return result;
    }

    private static final class ModuleResourceURLConnection extends URLConnection {
        private final List<Module> modules;

        ModuleResourceURLConnection(URL url, List<Module> modules) {
            super(url);
            this.modules = modules;
        }

        public void connect() {
        }

        public InputStream getInputStream() throws IOException {
            for(final var module : modules) {
                final var result = module.getResourceAsStream(getURL().getPath());
                if(result != null) return result;
            }
            return null;
        }
    }
}