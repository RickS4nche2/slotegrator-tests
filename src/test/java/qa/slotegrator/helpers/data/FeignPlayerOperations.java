package qa.slotegrator.helpers.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import tools.jackson.databind.JsonNode;

import qa.slotegrator.api.ObservedPlayerContract;
import qa.slotegrator.api.client.PlayersApi;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.api.model.PlayerId;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

/** Снимок корневого массива getAll по наблюдению стенда; некритичные поля не блокируют cleanup. */
public final class FeignPlayerOperations implements PlayerOperations {
    private final PlayersApi api;
    private final JsonCodec codec;

    public FeignPlayerOperations(PlayersApi api, JsonCodec codec) {
        this.api = api;
        this.codec = codec;
    }

    @Override
    public HttpResult create(CreatePlayerRequest request) {
        return HttpResult.read(api.create(request));
    }

    @Override
    public List<ObservedPlayerResponse> list() {
        HttpResult result = HttpResult.read(api.getAll());
        if (result.status() != 200)
            throw new AssertionError("Снимок игроков: ожидался HTTP 200, получен " + result.status());
        var node = codec.tree(result.body());
        if (!node.isArray())
            throw new AssertionError("getAll должен вернуть корневой массив; запись заблокирована");
        var players = new ArrayList<ObservedPlayerResponse>();
        var seen = new HashSet<String>();
        for (var entry : node) {
            // Принадлежность должна восстанавливаться и при нарушении схемы некритичных полей ответа.
            String id;
            try {
                id = ObservedPlayerContract.playerId(entry, "id");
            } catch (AssertionError invalidId) {
                throw new IllegalStateException("Снимок содержит запись без безопасного ID");
            }
            if (!seen.add(id))
                throw new IllegalStateException("Снимок содержит повторяющиеся ID");
            players.add(new ObservedPlayerResponse(id, text(entry, "username"), text(entry, "email"),
                    text(entry, "name"), text(entry, "surname"), text(entry, "currency_code")));
        }
        return List.copyOf(players);
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    @Override
    public HttpResult delete(String id) {
        PlayerId.requireSafe(id);
        return HttpResult.read(api.delete(id));
    }
}
