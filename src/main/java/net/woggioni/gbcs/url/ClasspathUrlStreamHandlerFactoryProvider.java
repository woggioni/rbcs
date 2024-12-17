package net.woggioni.gbcs.url;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

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
            return resourceUrl.openConnection();
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