package qa.slotegrator.expecteds;

import java.util.Locale;

import tools.jackson.databind.JsonNode;

import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.json.PayloadException;
import qa.slotegrator.helpers.reporting.KnownFailure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Чистые ожидания входа по наблюдению, зафиксированному в docs/api-spec.md. */
public final class AuthenticationExpected {
    public static final int OBSERVED_LOGIN_STATUS = 201;

    private AuthenticationExpected() {
    }

    /** Совместимость подготовки: опубликованный и прежний ответы, без ослабления контрактных тестов. */
    public static String verifyUsableLogin(HttpResult response, JsonCodec codec) {
        assertTrue(response.status() == 200 || response.status() == 201,
                "Вход для подготовки: ожидался HTTP 200 или 201, получен " + response.status());
        var body = codec.tree(response.body());
        assertTrue(body.isObject(), "Ответ входа должен быть JSON-объектом");
        assertTrue(body.has("access_token") != body.has("accessToken"),
                "Ответ входа должен содержать ровно одно известное поле токена");
        var token = body.get(body.has("access_token") ? "access_token" : "accessToken");
        assertTrue(token.isString() && !token.stringValue().isBlank(), "Токен должен быть непустой строкой");
        return token.stringValue();
    }

    public static void verifyObservedLogin(HttpResult response, JsonCodec codec) {
        assertEquals(OBSERVED_LOGIN_STATUS, response.status(), "Код входа отличается от наблюдаемого контракта");
        var node = codec.tree(response.body());
        assertTrue(node.isObject(), "Ответ входа должен быть JSON-объектом");
        var token = node.get("accessToken");
        assertTrue(token != null && token.isString() && !token.stringValue().isBlank(),
                "Наблюдаемый ответ входа должен содержать непустую строку accessToken");
    }

    /**
     * Точный отказ — принятое правило проекта для опубликованных 400/401.
     * См. docs/api-spec.md. Ошибки не имеют опубликованной схемы; проверяем отсутствие выдачи токена.
     */
    public static void verifyRejectedLogin(HttpResult response, int expectedStatus, JsonCodec codec) {
        if (expectedStatus == 401 && response.status() == OBSERVED_LOGIN_STATUS) {
            // BUG-006 узнаётся только по прежней успешной выдаче токена; иной ответ остаётся новым сбоем.
            verifyObservedLogin(response, codec);
            KnownFailure.BASIC_AUTH_IGNORED.rejectIf(true);
        }
        assertEquals(expectedStatus, response.status(), "Код отказа входа отличается от выбранного требования");
        verifyNoIssuedToken(response, codec);
    }

    /** OpenAPI перечисляет оба кода, но не задаёт приоритет валидации и аутентификации. */
    public static void verifyRejectedCredentials(HttpResult response, JsonCodec codec) {
        assertTrue(response.status() == 400 || response.status() == 401,
                "Некорректные реквизиты: ожидался HTTP 400 или 401, получен " + response.status());
        verifyNoIssuedToken(response, codec);
    }

    private static void verifyNoIssuedToken(HttpResult response, JsonCodec codec) {
        if (response.body().length == 0)
            return;
        JsonNode body;
        try {
            body = codec.tree(response.body());
        } catch (PayloadException ignored) {
            // Формат ошибки не задан; непрозрачный ответ не позволяет доказать отсутствие выдачи токена.
            throw new IllegalStateException("Не удалось проверить отсутствие токена в непрозрачном ответе входа");
        }
        assertFalse(containsIssuedToken(body), "Отказ входа не должен выдавать токен");
    }

    public static void verifyTokenGrantsPlayerAccess(HttpResult response) {
        assertEquals(200, response.status(), "Полученный токен должен открывать чтение списка игроков");
    }

    private static boolean containsIssuedToken(JsonNode node) {
        if (node.isObject()) {
            for (var field : node.properties()) {
                String name = field.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                var value = field.getValue();
                if (name.equals("accesstoken") && value.isString() && !value.stringValue().isBlank())
                    return true;
                if (containsIssuedToken(value))
                    return true;
            }
        } else if (node.isArray()) {
            for (var value : node)
                if (containsIssuedToken(value))
                    return true;
        }
        return false;
    }
}
