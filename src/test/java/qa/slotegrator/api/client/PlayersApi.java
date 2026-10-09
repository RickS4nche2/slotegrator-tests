package qa.slotegrator.api.client;

import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Response;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.PlayerLookupRequest;

/** Возвращается исходный ответ: HTTP-код не теряется при декодировании DTO. */
@Headers({"Content-Type: application/json", "Accept: application/json"})
public interface PlayersApi {
    @RequestLine("POST " + ApiRoutes.CREATE_PLAYER)
    Response create(CreatePlayerRequest request);

    @RequestLine("POST " + ApiRoutes.GET_PLAYER)
    Response getOne(PlayerLookupRequest request);

    @RequestLine("GET " + ApiRoutes.GET_PLAYERS)
    Response getAll();

    @RequestLine("DELETE " + ApiRoutes.DELETE_PLAYER)
    Response delete(@Param("id") String id);
}
