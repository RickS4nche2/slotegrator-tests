package qa.slotegrator.api.client;

import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Response;

import qa.slotegrator.api.ApiRoutes;

/** Чтение и удаление для подготовки и очистки; исходный ответ сохраняет HTTP-код. */
@Headers({"Content-Type: application/json", "Accept: application/json"})
public interface PlayersApi {
    @RequestLine("GET " + ApiRoutes.GET_PLAYERS)
    Response getAll();

    @RequestLine("DELETE " + ApiRoutes.DELETE_PLAYER)
    Response delete(@Param("id") String id);
}
