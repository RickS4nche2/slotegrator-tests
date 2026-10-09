package qa.slotegrator.tests.self.data;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.fixtures.LocalServer;
import qa.slotegrator.helpers.config.AuthSettings;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.PlayersTestContext;
import qa.slotegrator.helpers.http.HttpResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Границы контекста 12 игроков")
class PlayersTestContextTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "false", "isolated", "exclusive", "authorized", "true "})
    @DisplayName("Только явное true разрешает E2E")
    void rejectsMissingOrUnknownContext(String declaration) {
        assertThrows(IllegalStateException.class, () -> PlayersTestContext.requireAuthorized(declaration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true"})
    @DisplayName("Явное разрешение принимается")
    void acceptsDeclaredContext(String declaration) {
        assertDoesNotThrow(() -> PlayersTestContext.requireAuthorized(declaration));
    }

    /**
     * Design: границы и переходы состояния оснастки; HTTP-ответчик намеренно не сохраняет записи.
     * Steps: попытка без входа, 12 POST, повторный вход, 13-й POST, очистка и повторное закрытие.
     * Expected: защищённые переходы не отправляют POST; закрытие не запускает повторные HTTP-запросы.
     */
    @Test
    @DisplayName("До входа, после 12 POST и после очистки создание заблокировано")
    void limitsRequestsAndProtectsLifecycle() {
        var creates = new AtomicInteger();
        var reads = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.GET_PLAYERS, exchange -> {
                reads.incrementAndGet();
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.on(ApiRoutes.CREATE_PLAYER, exchange -> {
                creates.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            var settings = new ProjectSettings(server.settings(),
                    new AuthSettings("local-user", "dummy-basic", "local@example.test", "dummy-password"), "USD");
            try (var run = new PlayersTestContext(settings)) {
                var requests = run.playerRequests();
                assertEquals(12, requests.size());
                assertEquals(12, requests.stream().map(request -> request.email()).distinct().count());
                assertEquals(12, requests.stream().map(request -> request.username()).distinct().count());
                assertThrows(IllegalStateException.class, () -> run.create(requests.getFirst()));
                assertEquals(0, reads.get());
                var login = HttpResult.json(201, "{\"accessToken\":\"dummy-token\"}");
                run.authenticate(login);
                for (var request : requests)
                    run.create(request);
                assertThrows(IllegalStateException.class, () -> run.authenticate(login));
                assertThrows(IllegalStateException.class, () -> run.create(requests.getFirst()));
                assertEquals(12, creates.get());
                run.cleanup();
                assertThrows(IllegalStateException.class, () -> run.create(requests.getFirst()));
                int readsAfterCleanup = reads.get();
                run.cleanup();
                run.close();
                run.close();
                assertThrows(IllegalStateException.class, run::list);
                assertEquals(readsAfterCleanup, reads.get());
                assertEquals(12, creates.get());
            }
        }
    }
}
