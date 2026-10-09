package qa.slotegrator.tests.self.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import feign.Headers;
import feign.RequestLine;
import feign.Response;
import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.fixtures.LocalServer;
import qa.slotegrator.helpers.http.HttpClients;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.HttpDiagnostics;
import qa.slotegrator.helpers.reporting.ReportSanitizer;
import qa.slotegrator.helpers.reporting.ReportingFeignClient;
import qa.slotegrator.helpers.reporting.SafeAllureFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Локальная работа HTTP-клиентов")
class HttpClientsTest {
    @Headers({"Accept: application/json", "Content-Type: application/json"})
    interface LocalApi {
        @RequestLine("POST /echo")
        CreatePlayerRequest echo(CreatePlayerRequest request);
        @RequestLine("GET /redirect")
        Response redirect();
        @RequestLine("POST /drop")
        Response drop(CreatePlayerRequest request);
        @RequestLine("DELETE /drop")
        Response delete();
    }

    @Test
    @DisplayName("Feign и Rest Assured используют одинаковую сериализацию и безопасные вложения")
    void usesSharedCodecWithoutChangingRequests() {
        var codec = new JsonCodec();
        var sanitizer = new ReportSanitizer(codec);
        var diagnostics = new HttpDiagnostics(sanitizer);
        var actualBody = new AtomicReference<String>();
        try (var server = new LocalServer()) {
            server.on("/echo", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                actualBody.set(new String(body, StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            var input = player();
            var client = HttpClients.feign(server.settings(), codec)
                    .client(new ReportingFeignClient(HttpClients.feignTransport(), diagnostics))
                    .target(LocalApi.class, server.uri().toString());
            assertEquals(input, client.echo(input));
            assertTrue(actualBody.get().contains("dummy-http-password"), "В транспорт должна уйти исходная величина");
            var result = RestAssured.given().spec(HttpClients.restAssured(server.settings(), codec))
                    .filter(new SafeAllureFilter(codec, diagnostics)).body(input).post("/echo");
            assertEquals(200, result.statusCode());
            assertEquals(input, codec.decode(result.asByteArray(), CreatePlayerRequest.class));
            assertTrue(actualBody.get().contains("password_change"));
            assertTrue(actualBody.get().contains("Анна"));
        }
    }

    @Test
    @DisplayName("Клиенты сохраняют 307 вместо скрытого перехода к 200")
    void exposesRedirect() throws IOException {
        try (var server = new LocalServer()) {
            server.respond("/ok", 200, "{}");
            server.on("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", "/ok");
                exchange.sendResponseHeaders(307, -1);
                exchange.close();
            });
            var client = HttpClients.feign(server.settings()).target(LocalApi.class, server.uri().toString());
            try (var result = client.redirect()) {
                assertEquals(307, result.status());
            }
            assertEquals(307,
                    RestAssured.given().spec(HttpClients.restAssured(server.settings())).get("/redirect").statusCode());
        }
    }

    @Test
    @DisplayName("Разрыв соединения не вызывает повторный POST")
    void neverReplaysCreation() {
        var calls = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on("/drop", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.close();
            });
            var codec = new JsonCodec();
            var diagnostics = new HttpDiagnostics(new ReportSanitizer(codec));
            var client = HttpClients.feign(server.settings(), codec)
                    .client(new ReportingFeignClient(HttpClients.feignTransport(), diagnostics))
                    .target(LocalApi.class, server.uri().toString());
            assertThrows(Exception.class, () -> client.drop(player()));
            assertEquals(1, calls.get());
            var error = assertThrows(Exception.class, () -> RestAssured.given()
                    .spec(HttpClients.restAssured(server.settings(), codec))
                    .filter(new SafeAllureFilter(codec, diagnostics))
                    .body(player()).post("/drop"));
            assertEquals(2, calls.get());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("dummy-http-password"));
        }
    }

    @Test
    @DisplayName("Разрыв после полученного DELETE не вызывает скрытый повтор удаления")
    void neverReplaysDeletion() {
        var calls = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on("/drop", exchange -> {
                assertEquals("DELETE", exchange.getRequestMethod());
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                // Сервер уже получил удаление, но соединение оборвалось до ответа.
                exchange.close();
            });
            var codec = new JsonCodec();
            var diagnostics = new HttpDiagnostics(new ReportSanitizer(codec));
            var client = HttpClients.feign(server.settings(), codec)
                    .client(new ReportingFeignClient(HttpClients.feignTransport(), diagnostics))
                    .target(LocalApi.class, server.uri().toString());
            assertThrows(Exception.class, client::delete);
            assertEquals(1, calls.get(), "Feign должен передать ровно один DELETE");
            assertThrows(Exception.class, () -> RestAssured.given()
                    .spec(HttpClients.restAssured(server.settings(), codec))
                    .filter(new SafeAllureFilter(codec, diagnostics)).delete("/drop"));
            assertEquals(2, calls.get(), "Rest Assured должен передать ровно один дополнительный DELETE");
        }
    }

    private CreatePlayerRequest player() {
        return new CreatePlayerRequest("TEST", "local@example.test", "Анна", "dummy-http-password",
                "dummy-http-password", "Иванова", "local_user");
    }
}
