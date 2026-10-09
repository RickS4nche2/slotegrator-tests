package qa.slotegrator.tests.self.reporting;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.ReportSanitizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Маскирование диагностики")
class ReportSanitizerTest {
    private final JsonCodec codec = new JsonCodec();
    private final ReportSanitizer sanitizer = new ReportSanitizer(codec);

    @Test
    @DisplayName("Секреты вложенных объектов, массивов и отражённых сообщений скрываются")
    void masksNestedAndReflectedSecrets() {
        String marker = "dummy-report-password";
        String raw = "{\"message\":\"echo " + marker + "\",\"data\":[{\"password\":\"" + marker
                + "\"}],\"name\":\"Анна\"}";
        String safe = sanitizer.body(raw.getBytes(StandardCharsets.UTF_8));
        assertFalse(safe.contains(marker));
        assertTrue(safe.contains("Анна"));
        assertTrue(raw.contains(marker), "Исходное тело не должно измениться");
    }

    /**
     * Design: имена полей сессии и авторизации вне базового набора. Steps: тело с такими полями и отражением токена.
     * Expected: значения и токен после схемы Bearer скрыты в теле и в последующем тексте.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"session", "sessionId", "bearer", "pwd", "pass", "Authorization", "x_session_key"})
    @DisplayName("Поля сессии, авторизации и сокращённых паролей скрываются вместе с отражениями")
    void masksSessionAndAuthorizationFields(String field) {
        String marker = "dummy-" + field.toLowerCase(Locale.ROOT) + "-secret";
        String raw = "{\"" + field + "\":\"Bearer " + marker + "\",\"name\":\"Анна\"}";
        String safe = sanitizer.body(raw.getBytes(StandardCharsets.UTF_8));
        assertFalse(safe.contains(marker));
        assertTrue(safe.contains("Анна"));
        assertFalse(sanitizer.text("echo " + marker).contains(marker), "Отражение токена после Bearer скрывается");
    }

    @Test
    @DisplayName("Некорректный JSON и слишком большое тело не прикладываются")
    void omitsUnparseableAndLargeBodies() {
        String marker = "dummy-malformed-report-secret";
        assertFalse(sanitizer.body(("{\"password\":\"" + marker).getBytes(StandardCharsets.UTF_8)).contains(marker));
        assertTrue(sanitizer.body(new byte[65_537]).contains("превышен предел"));
    }

    @Test
    @DisplayName("Basic, Bearer и cookie скрываются вместе с отражением их значений")
    void masksHeadersAndReflections() {
        String password = "dummy-basic-password";
        String basic = "Basic "
                + Base64.getEncoder().encodeToString(("user:" + password).getBytes(StandardCharsets.UTF_8));
        assertEquals(ReportSanitizer.HIDDEN, sanitizer.header("aUtHoRiZaTiOn", basic));
        sanitizer.header("Authorization", "Bearer dummy-bearer-token");
        sanitizer.header("Set-Cookie", "sid=dummy-cookie-token; Secure; HttpOnly");
        String safe = sanitizer.text(password + " dummy-bearer-token dummy-cookie-token");
        assertFalse(safe.contains(password));
        assertFalse(safe.contains("dummy-bearer-token"));
        assertFalse(safe.contains("dummy-cookie-token"));
    }

    @Test
    @DisplayName("Отражённые секреты скрываются в JSON-ключах и числовых значениях")
    void masksKeysAndScalarReflections() {
        String marker = "dummy-key-secret";
        sanitizer.remember(marker);
        String raw = "{\"" + marker + "\":\"echo\",\"password\":739182645,\"numberEcho\":739182645}";
        String safe = sanitizer.body(raw.getBytes(StandardCharsets.UTF_8));
        assertFalse(safe.contains(marker));
        assertFalse(safe.contains("739182645"));
        assertTrue(safe.contains("numberEcho"));
    }

    @ParameterizedTest(name = "Секретное поле: {0}")
    @ValueSource(strings = {"accessToken", "refreshToken", "clientSecret", "passwordChange", "passwordRepeat",
            "Access-Token", "jwt", "secret", "apiKey", "X-Auth-Token", "password_hash", "signingSecret"})
    @DisplayName("Camel case и дефисы не обходят маскирование токенов и паролей")
    void masksAlternativeFieldNames(String field) {
        String marker = "dummy-camel-token-secret";
        String raw = "{\"user\":{\"name\":\"Анна\"},\"" + field + "\":\"" + marker
                + "\",\"reflected\":\"" + marker + "\"}";
        String safe = sanitizer.body(raw.getBytes(StandardCharsets.UTF_8));
        assertFalse(safe.contains(marker));
        assertEquals(ReportSanitizer.HIDDEN, codec.tree(safe).get(field).asString());
        assertEquals(ReportSanitizer.HIDDEN, codec.tree(safe).get("reflected").asString());
        assertTrue(safe.contains("Анна"));
    }

    @Test
    @DisplayName("Адрес автора скрывается независимо от имени поля, включая отражения")
    void masksEmailInArbitraryFieldsAndText() {
        String address = "owner+qa@example.test";
        String raw = "{\"createBy\":\"" + address + "\",\"message\":\"created by " + address
                + "\",\"nested\":[{\"unexpected\":\"" + address + "\"}],\"id\":\"abc123\"}";
        String safe = sanitizer.body(raw.getBytes(StandardCharsets.UTF_8));
        assertFalse(safe.contains(address));
        assertEquals(ReportSanitizer.HIDDEN, codec.tree(safe).get("createBy").asString());
        assertEquals("abc123", codec.tree(safe).get("id").asString());
        assertFalse(sanitizer.text("https://example.test/" + address).contains(address));
        assertTrue(raw.contains(address), "Исходный ответ остаётся доступен проверкам без маскирования");
    }

    @Test
    @DisplayName("Нестандартные заголовки секретов, JWT и их отражения скрываются")
    void masksCustomHeadersAndUnlabelledJwt() {
        String token = "dummy-header-auth-value";
        String key = "dummy-header-key-value";
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkdW1teSJ9.c3ludGhldGlj";
        assertEquals(ReportSanitizer.HIDDEN, sanitizer.header("X-Auth-Token", token));
        assertEquals(ReportSanitizer.HIDDEN, sanitizer.header("X-API-Key", key));
        String safe = sanitizer.body(codec.encode(java.util.Map.of("echo", token + " " + key + " " + jwt)));
        assertFalse(safe.contains(token));
        assertFalse(safe.contains(key));
        assertFalse(safe.contains(jwt));
        assertFalse(sanitizer.header("X-Diagnostic", jwt).contains(jwt));
    }
}
