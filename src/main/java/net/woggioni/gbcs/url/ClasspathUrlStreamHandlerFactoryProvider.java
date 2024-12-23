package net.woggioni.gbcs.url;

import net.woggioni.jwo.Fun;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Optional;

public class ClasspathUrlStreamHandlerFactoryProvider implements URLStreamHandlerFactory {

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
            final URL resourceUrl = classLoader.getResource(u.getPath());
            return Optional.ofNullable(resourceUrl)
                    .map((Fun<URL, URLConnection>) URL::openConnection)
                    .orElseThrow(IOException::new);
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
}