package qa.slotegrator.api;

import java.util.Set;

import tools.jackson.databind.JsonNode;

import qa.slotegrator.api.model.PlayerId;
import qa.slotegrator.helpers.reporting.KnownFailure;

/** Проверки исходного JSON по известной части OpenAPI, без приведения типов DTO. */
public final class ApiContract {
    private ApiContract() {
    }

    public static void token(JsonNode node) {
        object(node);
        var observed = node.get("accessToken");
        boolean observedShape = observed != null && observed.isString() && !observed.stringValue().isBlank()
                && (!node.has("user") || node.get("user").isObject())
                && node.properties().stream().allMatch(field -> Set.of("accessToken", "user").contains(field.getKey()));
        KnownFailure.TOKEN_ACCESS_TOKEN_MISSING.rejectIf(observedShape);
        strings(node, "access_token", "token_type", "expires_in", "scope");
        if (node.get("access_token").stringValue().isBlank()) {
            throw new AssertionError("access_token не должен быть пустым");
        }
    }

    public static void player(JsonNode node) {
        object(node);
        strings(node, "username", "email", "name", "surname");
        if (!node.has("currency_code")) {
            throw new AssertionError("Отсутствует обязательное поле currency_code");
        }
        KnownFailure.PLAYER_ID_NOT_INTEGER.rejectIf(
                (!node.has("id") && node.has("_id") && node.get("_id").isString()
                        && PlayerId.isObserved(node.get("_id").stringValue()))
                        || (node.has("id") && node.get("id").isString()
                                && PlayerId.isObserved(node.get("id").stringValue())));
        if (!node.has("id") || !node.get("id").isIntegralNumber()) {
            throw new AssertionError("id должен быть целым числом");
        }
    }

    private static void object(JsonNode node) {
        if (node == null || !node.isObject())
            throw new AssertionError("Ожидался JSON-объект");
    }

    private static void strings(JsonNode node, String... names) {
        for (String name : names) {
            if (!node.has(name)) {
                throw new AssertionError("Отсутствует обязательное поле " + name);
            }
            if (!node.get(name).isString()) {
                throw new AssertionError("Поле " + name + " должно присутствовать и иметь тип string");
            }
        }
    }
}
