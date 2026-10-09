package qa.slotegrator.tests.self.json;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import qa.slotegrator.api.ApiContract;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.PlayerResponse;
import qa.slotegrator.api.model.TokenResponse;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.json.PayloadException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JSON и исходный контракт")
class JsonCodecTest {
    private final JsonCodec codec = new JsonCodec();

    @Test
    @DisplayName("Snake case и кириллица сохраняются при переводе DTO в JSON и обратно")
    void preservesRequestFields() {
        var request = new CreatePlayerRequest("TEST", "local@example.test", "Анна", "dummy-json-password",
                "dummy-json-password", "Иванова", "local_user");
        byte[] body = codec.encode(request);
        var node = codec.tree(body);
        assertAll(
                () -> assertEquals(7, node.size()),
                () -> assertTrue(node.has("currency_code")),
                () -> assertTrue(node.has("password_change")),
                () -> assertTrue(node.has("password_repeat")),
                () -> assertFalse(node.has("passwordChange")),
                () -> assertEquals(request, codec.decode(body, CreatePlayerRequest.class)));
    }

    @ParameterizedTest(name = "Нарушение expires_in: {0}")
    @ValueSource(strings = {"число", "null", "нет поля"})
    @DisplayName("Типы исходного JSON проверяются до десериализации")
    void rejectsWrongTokenShape(String kind) {
        var node = (tools.jackson.databind.node.ObjectNode) codec.tree(validToken());
        switch (kind) {
            case "число" -> node.put("expires_in", 120);
            case "null" -> node.putNull("expires_in");
            default -> node.remove("expires_in");
        }
        assertThrows(AssertionError.class, () -> ApiContract.token(node));
        assertThrows(PayloadException.class, () -> codec.decode(codec.encode(node), TokenResponse.class));
    }

    @Test
    @DisplayName("Дополнительные поля разрешены опубликованной схемой")
    void acceptsAdditionalFields() {
        var node = (tools.jackson.databind.node.ObjectNode) codec.tree(validToken());
        node.put("additional", "value");
        ApiContract.token(node);
        assertNotNull(codec.decode(codec.encode(node), TokenResponse.class));
    }

    @Test
    @DisplayName("Неописанный тип currency_code сохраняется без домысла о string")
    void preservesUndocumentedCurrencyType() {
        byte[] body = "{\"id\":1,\"username\":\"user\",\"email\":\"a@example.test\",\"name\":\"Анна\",\"surname\":\"А\",\"currency_code\":42}"
                .getBytes(StandardCharsets.UTF_8);
        ApiContract.player(codec.tree(body));
        assertTrue(codec.decode(body, PlayerResponse.class).currencyCode().isIntegralNumber());
    }

    @Test
    @DisplayName("Снимок игрока не меняется через вложенный JSON currency_code")
    void playerSnapshotIsImmutable() {
        var currency = (tools.jackson.databind.node.ObjectNode) codec.tree("{\"code\":\"TEST\"}");
        var player = new PlayerResponse(1L, "user", "a@example.test", "Анна", "А", currency);
        currency.put("code", "CHANGED");
        ((tools.jackson.databind.node.ObjectNode) player.currencyCode()).put("code", "CHANGED_AGAIN");
        assertEquals("TEST", player.currencyCode().get("code").asString());
    }

    /** Design: independent contract. Steps: предъявить наблюдаемый строковый ID. Expected: исходный oracle не меняется. */
    @ParameterizedTest(name = "Опубликованный id: {0}")
    @ValueSource(strings = {"строка", "только _id"})
    @DisplayName("Наблюдаемый строковый ID не подменяет опубликованную числовую модель")
    void preservesPublishedNumericIdContract(String form) {
        var node = (tools.jackson.databind.node.ObjectNode) codec.tree(
                "{\"id\":1,\"username\":\"user\",\"email\":\"a@example.test\",\"name\":\"Анна\",\"surname\":\"А\",\"currency_code\":42}");
        if (form.equals("строка"))
            node.put("id", "0123456789abcdef01234567");
        else {
            node.remove("id");
            node.put("_id", "0123456789abcdef01234567");
        }
        assertThrows(AssertionError.class, () -> ApiContract.player(node));
        assertThrows(PayloadException.class, () -> codec.decode(codec.encode(node), PlayerResponse.class));
    }

    @Test
    @DisplayName("Сломанный JSON не раскрывается в сообщении или cause парсера")
    void parsingFailureIsSafe() {
        String marker = "dummy-broken-json-secret";
        var error = assertThrows(PayloadException.class, () -> codec.tree("{\"password\":\"" + marker));
        assertFalse(error.getMessage().contains(marker));
        assertNull(error.getCause());
    }

    @Test
    @DisplayName("Дубли JSON-ключей не скрывают конфликтующие значения")
    void rejectsDuplicateKeys() {
        assertThrows(PayloadException.class, () -> codec.tree("{\"id\":1,\"id\":2}"));
    }

    @Test
    @DisplayName("Диагностическое представление секретных DTO скрывает их значения")
    void secretModelsDoNotExposeValues() {
        var token = codec.decode(validToken().getBytes(StandardCharsets.UTF_8), TokenResponse.class);
        assertFalse(token.toString().contains("dummy-json-token"));
    }

    private String validToken() {
        return "{\"access_token\":\"dummy-json-token\",\"token_type\":\"Bearer\",\"expires_in\":\"120\",\"scope\":\"players\"}";
    }
}
