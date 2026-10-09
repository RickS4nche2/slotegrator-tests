package qa.slotegrator.fixtures;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import qa.slotegrator.helpers.config.ApiSettings;

/** Локальный ответчик с отдельным случайным портом и обязательным закрытием. */
public final class LocalServer implements AutoCloseable {
    private final HttpServer server;

    public LocalServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось открыть локальный ответчик");
        }
    }

    public void on(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    public void respond(String path, int status, String body) {
        on(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public ApiSettings settings() {
        return new ApiSettings(uri(), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
