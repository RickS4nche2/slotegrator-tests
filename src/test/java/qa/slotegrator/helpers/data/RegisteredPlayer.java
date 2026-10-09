package qa.slotegrator.helpers.data;

import qa.slotegrator.api.model.CreatePlayerRequest;

/** Подтверждённый ID вместе с исходным запросом — эталоном для последующих чтений. */
public record RegisteredPlayer(String id, CreatePlayerRequest request) {
}
