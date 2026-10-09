package qa.slotegrator.tests.self.auth;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.PlayerLookupRequest;
import qa.slotegrator.fixtures.LocalServer;
import qa.slotegrator.helpers.auth.ApiSession;
import qa.slotegrator.helpers.config.AuthSettings;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Авторизация и разделение сеансов")
class ApiSessionTest {
    @Test
    @DisplayName("Конструктор не выполняет HTTP и не допускает вызов до получения токена")
    void requiresExplicitAuthentication() {
        try (var server = new LocalServer()) {
            var session = session(server, "A");
            assertThrows(IllegalStateException.class, session::players);
            assertThrows(IllegalStateException.class, session::restAssured);
        }
    }

    @Test
    @DisplayName("Basic-вход и Bearer-вызовы передают правильные реквизиты, сохраняя HTTP-код")
    void usesSeparateAuthSchemes() throws Exception {
        var basic = new AtomicReference<String>();
        var bearer = new AtomicReference<String>();
        var password = new AtomicReference<String>();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.LOGIN, exchange -> {
                basic.set(exchange.getRequestHeaders().getFirst("Authorization"));
                var body = new JsonCodec().tree(exchange.getRequestBody().readAllBytes());
                password.set(body.get("password").stringValue());
                byte[] result = token("A").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, result.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(result);
                }
            });
            server.on(ApiRoutes.GET_PLAYER, exchange -> {
                bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("POST", exchange.getRequestMethod());
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            var session = session(server, "A");
            session.authenticate();
            assertTrue(basic.get().startsWith("Basic "), "Схема входа должна быть Basic");
            assertTrue(password.get().equals("dummy-auth-password-A"), "Пароль должен дойти до локального ответчика");
            var result = HttpResult.read(session.players().getOne(new PlayerLookupRequest("player@example.test")));
            assertEquals(201, result.status(), "Исходный статус не должен превращаться в 200");
            assertTrue(bearer.get().equals("Bearer dummy-auth-token-A"),
                    "Операция должна использовать полученный токен");
        }
    }

    @Test
    @DisplayName("Токен одного сеанса не подменяет токен другого")
    void isolatesTokens() {
        var lastHeader = new AtomicReference<String>();
        var count = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.LOGIN, exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = token(count.incrementAndGet() == 1 ? "A" : "B").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            server.on(ApiRoutes.GET_PLAYERS, exchange -> {
                lastHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            });
            var first = session(server, "A");
            var second = session(server, "B");
            first.authenticate();
            second.authenticate();
            HttpResult.read(first.players().getAll());
            assertTrue(lastHeader.get().equals("Bearer dummy-auth-token-A"));
            HttpResult.read(second.players().getAll());
            assertTrue(lastHeader.get().equals("Bearer dummy-auth-token-B"));
        }
    }

    @Test
    @DisplayName("Неуспешный вход не даёт доступ к операциям игроков")
    void rejectsFailedLogin() {
        try (var server = new LocalServer()) {
            server.respond(ApiRoutes.LOGIN, 401, "{}");
            var session = session(server, "A");
            assertThrows(AssertionError.class, session::authenticate);
            assertThrows(IllegalStateException.class, session::players);
        }
    }

    /** Design: переход состояния. Steps: сохранить клиенты, получить отказ входа. Expected: ни одного HTTP-вызова. */
    @Test
    @DisplayName("Отказ повторного входа блокирует также ранее выданные Feign и Rest Assured клиенты")
    void invalidatesPreviouslyIssuedClientsAfterFailure() {
        var calls = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.GET_PLAYERS, exchange -> {
                calls.incrementAndGet();
                byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(bytes);
                }
            });
            var session = session(server, "A");
            session.authenticate(HttpResult.json(201, token("A")));
            var feign = session.players();
            var restAssured = session.restAssured();
            assertThrows(AssertionError.class, () -> session.authenticate(HttpResult.json(401, "{}")));
            assertThrows(IllegalStateException.class, feign::getAll);
            assertThrows(IllegalStateException.class, () -> RestAssured.given()
                    .spec(restAssured).get(ApiRoutes.GET_PLAYERS));
            assertEquals(0, calls.get(), "Старые клиенты не должны отправить HTTP после отказа входа");
        }
    }

    /** Design: переход состояния. Steps: сменить A на B при сохранённых клиентах. Expected: оба используют B. */
    @Test
    @DisplayName("Сохранённые клиенты обоих транспортов согласованно используют новый токен после входа")
    void updatesPreviouslyIssuedClientsAfterSuccessfulLogin() {
        var authorization = new AtomicReference<String>();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.GET_PLAYERS, exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(bytes);
                }
            });
            var session = session(server, "A");
            session.authenticate(HttpResult.json(201, token("A")));
            var feign = session.players();
            var restAssured = session.restAssured();
            session.authenticate(HttpResult.json(200, "{\"access_token\":\"dummy-auth-token-B\"}"));
            HttpResult.read(feign.getAll());
            assertTrue("Bearer dummy-auth-token-B".equals(authorization.get()));
            authorization.set(null);
            RestAssured.given().spec(restAssured).get(ApiRoutes.GET_PLAYERS);
            assertTrue("Bearer dummy-auth-token-B".equals(authorization.get()));
        }
    }

    private ApiSession session(LocalServer server, String suffix) {
        return new ApiSession(server.settings(), new AuthSettings("local-basic-user", "dummy-basic-password",
                "tester" + suffix + "@example.test", "dummy-auth-password-" + suffix));
    }

    private String token(String suffix) {
        return "{\"accessToken\":\"dummy-auth-token-" + suffix + "\"}";
    }
}
