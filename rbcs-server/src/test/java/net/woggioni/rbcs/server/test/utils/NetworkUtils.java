package net.woggioni.rbcs.server.test.utils;

import net.woggioni.jwo.JWO;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

public class NetworkUtils {

    private static final int MAX_ATTEMPTS = 50;

    public static int getFreePort() {
        int count = 0;
        while(count < MAX_ATTEMPTS) {
            try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLocalHost())) {
                final var candidate = serverSocket.getLocalPort();
                if (candidate > 0) {
                    return candidate;
                } else {
                    JWO.newThrowable(RuntimeException.class, "Got invalid port number: %d", candidate);
                    throw new RuntimeException("Error trying to find an open port");
                }
            } catch (IOException ignored) {
                ++count;
            }
        }
        throw new RuntimeException("Error trying to find an open port");
    }
}
