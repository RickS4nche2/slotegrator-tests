package qa.slotegrator.tests.self.expecteds;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import qa.slotegrator.api.ApiContract;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Совместимость подготовки не подменяет сырой контракт и независимые поля")
class PlayersExpectedTest {
    private static final String OWN_ID = "abcdef0123456789abcdef01";
    private static final JsonCodec CODEC = new JsonCodec();
    private static final CreatePlayerRequest REQUEST = new CreatePlayerRequest("USD", "own@example.test", "Анна",
            "dummy-password", "dummy-password", "Иванова", "own-player");

    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    @DisplayName("Функциональное чтение переживает исправление кода профиля 201 на 200")
    void acceptsDocumentedAndObservedProfileStatus(int status) {
        var profile = fields().put("id", OWN_ID);
        assertDoesNotThrow(() -> PlayersExpected.verifyProfile(response(status, profile), REQUEST, OWN_ID, CODEC));
    }

    @ParameterizedTest
    @ValueSource(longs = {-2147483648L, -1, 0, 7, 2147483647L})
    @DisplayName("Опубликованный integer ID канонизируется только после проверки исходного JSON-типа")
    void supportsCorrectedIntegerIdentity(long id) {
        var node = fields().put("id", id);
        var created = PlayersExpected.verifyCreated(response(201, node), REQUEST, Set.of(Long.toString(id)), CODEC);
        assertEquals(Long.toString(id), created.id());
        assertDoesNotThrow(() -> PlayersExpected.verifyProfile(response(200, node), REQUEST, created.id(), CODEC));
        assertDoesNotThrow(() -> ApiContract.player(node));
        assertEquals(created.id(), PlayersExpected.readList(HttpResult.json(200, "[" + CODEC.text(node) + "]"), CODEC)
                .getFirst().id());
    }

    @Test
    @DisplayName("Совместимость со строковым _id не превращает отклонение в выполнение OpenAPI")
    void observedIdentityStillViolatesRawContract() {
        var node = fields().put("_id", OWN_ID);
        var created = PlayersExpected.verifyCreated(response(201, node), REQUEST, Set.of(OWN_ID), CODEC);
        assertEquals(OWN_ID, created.id());
        assertThrows(AssertionError.class, () -> ApiContract.player(node));
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email", "name", "surname", "currency_code"})
    @DisplayName("Одинаковая подмена поля в ответах не меняет независимый эталон запроса")
    void rejectsChangedSubmittedField(String field) {
        var created = fields().put("_id", OWN_ID).put(field, "changed");
        var profile = fields().put("id", OWN_ID).put(field, "changed");
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyCreated(response(201, created), REQUEST, Set.of(OWN_ID), CODEC));
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyProfile(response(200, profile), REQUEST, OWN_ID, CODEC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "true", "7.5", "\"7\"", "\"\"", "\"../other\"",
            "\"%2f\"", "\"ABCDEF0123456789ABCDEF01\""})
    @DisplayName("Непригодный ID не исправляется преобразованием JSON или владением")
    void rejectsUnsafeOrWronglyTypedId(String raw) {
        var node = fields();
        node.set("id", CODEC.tree(raw));
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyProfile(response(200, node), REQUEST, "7", CODEC));
    }

    @Test
    @DisplayName("Противоречивые опубликованный и наблюдаемый ID не подтверждают владение")
    void rejectsAmbiguousCreatedIdentity() {
        var node = fields().put("id", 7).put("_id", OWN_ID);
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyCreated(response(201, node), REQUEST, Set.of("7", OWN_ID), CODEC));
    }

    @Test
    @DisplayName("Исходные поля не заменяют независимого подтверждения ID созданной записи")
    void rejectsUnconfirmedIdentity() {
        var node = fields().put("_id", OWN_ID);
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyCreated(response(201, node), REQUEST, Set.of(), CODEC));
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 204, 400, 502})
    @DisplayName("Коды вне явно допустимых проверяются до разбора тела")
    void rejectsUnexpectedStatusBeforeParsing(int status) {
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyCreated(HttpResult.json(status, "<html>error</html>"), REQUEST, Set.of(),
                        CODEC));
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyProfile(HttpResult.json(status, "<html>error</html>"), REQUEST, OWN_ID,
                        CODEC));
    }

    @Test
    @DisplayName("Диагностическое представление игрока не раскрывает исходные данные")
    void keepsDiagnosticRepresentationSafe() {
        var created = PlayersExpected.verifyCreated(response(201, fields().put("_id", OWN_ID)), REQUEST, Set.of(OWN_ID),
                CODEC);
        for (String value : List.of(OWN_ID, REQUEST.email(), REQUEST.username(), REQUEST.name(),
                REQUEST.passwordChange()))
            assertFalse(created.toString().contains(value));
    }

    private static ObjectNode fields() {
        return ((ObjectNode) CODEC.tree("{}")).put("username", REQUEST.username()).put("email", REQUEST.email())
                .put("name", REQUEST.name()).put("surname", REQUEST.surname())
                .put("currency_code", REQUEST.currencyCode());
    }

    private static HttpResult response(int status, ObjectNode body) {
        return HttpResult.json(status, CODEC.text(body));
    }
}
