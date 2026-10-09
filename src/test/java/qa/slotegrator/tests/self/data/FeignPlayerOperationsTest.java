package qa.slotegrator.tests.self.data;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.fixtures.LocalServer;
import qa.slotegrator.helpers.auth.ApiSession;
import qa.slotegrator.helpers.config.AuthSettings;
import qa.slotegrator.helpers.data.CreationJournal;
import qa.slotegrator.helpers.data.FeignPlayerOperations;
import qa.slotegrator.helpers.data.PlayerData;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Строковые ID в снимке и DELETE")
class FeignPlayerOperationsTest {
    private static final String PLAYER_ID = "0123456789abcdef01234567";
    private final JsonCodec codec = new JsonCodec();

    /** Design: types/ambiguity. Steps: прочитать непригодный baseline. Expected: до POST нет изменений. */
    @ParameterizedTest(name = "Снимок: {0}")
    @ValueSource(strings = {"дробное число", "null", "нет id", "только _id", "число и _id", "пусто", "23 символа",
            "25 символов", "не hex", "верхний регистр", "разделитель", "encoded separator", "не объект", "дубликат"})
    @DisplayName("Небезопасный или неоднозначный ID снимка блокирует создание и удаление")
    void blocksMutationOnInvalidSnapshot(String violation) {
        try (var server = new LocalServer()) {
            var writes = new AtomicInteger();
            server.respond(ApiRoutes.GET_PLAYERS, 200, snapshot(violation));
            server.on(ApiRoutes.CREATE_PLAYER, exchange -> {
                writes.incrementAndGet();
                exchange.close();
            });
            server.on(ApiRoutes.DELETE_PLAYER.substring(0, ApiRoutes.DELETE_PLAYER.indexOf('{')), exchange -> {
                writes.incrementAndGet();
                exchange.close();
            });
            var runId = UUID.randomUUID();
            var journal = new CreationJournal(runId, operations(server));
            assertThrows(IllegalStateException.class, () -> journal.create(PlayerData.player(runId, 1, "USD", "Анна")));
            assertEquals(0, writes.get());
        }
    }

    /** Design: path guard. Steps: передать опасный сегмент напрямую. Expected: HTTP не отправлен. */
    @ParameterizedTest(name = "DELETE ID: {0}")
    @ValueSource(strings = {"null", "пусто", "разделитель", "query", "encoded separator", "не hex"})
    @DisplayName("Проверка DELETE не передаёт небезопасный ID транспорту и не раскрывает его")
    void blocksUnsafeDeleteBeforeHttp(String violation) {
        try (var server = new LocalServer()) {
            var calls = new AtomicInteger();
            server.on(ApiRoutes.DELETE_PLAYER.substring(0, ApiRoutes.DELETE_PLAYER.indexOf('{')), exchange -> {
                calls.incrementAndGet();
                exchange.close();
            });
            String id = switch (violation) {
                case "null" -> null;
                case "пусто" -> "";
                case "разделитель" -> "../another";
                case "query" -> "id?target=other";
                case "encoded separator" -> "%2f".repeat(8);
                default -> "dummy-invalid-id-secret";
            };
            var error = assertThrows(IllegalArgumentException.class, () -> operations(server).delete(id));
            assertFalse(error.getMessage().contains("dummy-invalid-id-secret"));
            assertEquals(0, calls.get());
        }
    }

    /** Design: nominal/recovery. Steps: прочитать неполную запись и удалить точный ID. Expected: поля не подставляются. */
    @Test
    @DisplayName("Неполные предметные поля не мешают чтению владения; DELETE сохраняет строковый path")
    void preservesStringPathAndIncompleteFacts() {
        try (var server = new LocalServer()) {
            server.respond(ApiRoutes.GET_PLAYERS, 200,
                    "[{\"id\":\"" + PLAYER_ID + "\",\"username\":\"own\",\"email\":\"own@example.test\"}]");
            var path = new AtomicReference<String>();
            var method = new AtomicReference<String>();
            server.on(ApiRoutes.DELETE_PLAYER.substring(0, ApiRoutes.DELETE_PLAYER.indexOf('{')), exchange -> {
                path.set(exchange.getRequestURI().getRawPath());
                method.set(exchange.getRequestMethod());
                byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            var operations = operations(server);
            var players = operations.list();
            assertEquals(List.of(PLAYER_ID), players.stream().map(player -> player.id()).toList());
            assertTrue(players.getFirst().currencyCode() == null && players.getFirst().name() == null);
            assertEquals(200, operations.delete(PLAYER_ID).status());
            assertEquals(ApiRoutes.DELETE_PLAYER.replace("{id}", PLAYER_ID), path.get());
            assertEquals("DELETE", method.get());
        }
    }

    @Test
    @DisplayName("Опубликованный числовой ID остаётся точным signed path без потери владения")
    void preservesPublishedIntegerId() {
        try (var server = new LocalServer()) {
            server.respond(ApiRoutes.GET_PLAYERS, 200, "[{\"id\":-7,\"email\":\"own@example.test\"}]");
            var path = new AtomicReference<String>();
            server.on(ApiRoutes.DELETE_PLAYER.substring(0, ApiRoutes.DELETE_PLAYER.indexOf('{')), exchange -> {
                path.set(exchange.getRequestURI().getRawPath());
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            var operations = operations(server);
            assertEquals("-7", operations.list().getFirst().id());
            operations.delete("-7");
            assertEquals(ApiRoutes.DELETE_PLAYER.replace("{id}", "-7"), path.get());
        }
    }

    private String snapshot(String violation) {
        var node = (ObjectNode) codec
                .tree("{\"id\":\"" + PLAYER_ID + "\",\"username\":\"foreign\",\"email\":\"foreign@example.test\"}");
        switch (violation) {
            case "дробное число" -> node.put("id", 24.5);
            case "null" -> node.putNull("id");
            case "нет id" -> node.remove("id");
            case "только _id" -> {
                node.remove("id");
                node.put("_id", PLAYER_ID);
            }
            case "число и _id" -> {
                node.put("id", 24.5);
                node.put("_id", PLAYER_ID);
            }
            case "пусто" -> node.put("id", "");
            case "23 символа" -> node.put("id", "1".repeat(23));
            case "25 символов" -> node.put("id", "1".repeat(25));
            case "не hex" -> node.put("id", "z".repeat(24));
            case "верхний регистр" -> node.put("id", "A".repeat(24));
            case "разделитель" -> node.put("id", "1".repeat(23) + "/");
            case "encoded separator" -> node.put("id", "%2f".repeat(8));
            case "не объект" -> {
                return "[42]";
            }
            default -> {
                return "[" + codec.text(node) + "," + codec.text(node) + "]";
            }
        }
        return "[" + codec.text(node) + "]";
    }

    private FeignPlayerOperations operations(LocalServer server) {
        var session = new ApiSession(server.settings(), new AuthSettings("local-user", "dummy-id-basic",
                "local@example.test", "dummy-id-password"));
        session.authenticate(HttpResult.json(201, "{\"accessToken\":\"dummy-id-token\"}"));
        return new FeignPlayerOperations(session.players(), session.codec());
    }
}
