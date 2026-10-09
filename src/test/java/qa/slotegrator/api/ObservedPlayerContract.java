package qa.slotegrator.api;

import tools.jackson.databind.JsonNode;

import qa.slotegrator.api.model.PlayerId;

/** Совместимость подготовки и функциональных проверок; сырой опубликованный контракт проверяет ApiContract. */
public final class ObservedPlayerContract {
    private ObservedPlayerContract() {
    }

    public static void created(JsonNode node) {
        player(node, "_id");
    }

    public static void profile(JsonNode node) {
        player(node, "id");
    }

    public static String playerId(JsonNode node, String observedField) {
        if (node == null || !node.isObject())
            throw new AssertionError("Ожидался JSON-объект игрока");
        var published = node.get("id");
        if (published != null && published.isIntegralNumber()) {
            var observed = node.get(observedField);
            if (!"id".equals(observedField) && observed != null)
                throw new AssertionError("Ответ одновременно содержит опубликованный и наблюдаемый ID");
            return published.toString();
        }
        var observed = node.get(observedField);
        if (observed == null || !observed.isString() || !PlayerId.isObserved(observed.stringValue()))
            throw new AssertionError("Ответ не содержит опубликованный integer id или безопасный наблюдаемый ID");
        return observed.stringValue();
    }

    private static void player(JsonNode node, String idField) {
        playerId(node, idField);
        for (String field : new String[]{"username", "email", "name", "surname", "currency_code"}) {
            if (!node.has(field) || !node.get(field).isString())
                throw new AssertionError("Поле " + field + " должно присутствовать и иметь тип string");
        }
    }
}
