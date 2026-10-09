package qa.slotegrator.api.client;

import feign.Headers;
import feign.RequestLine;
import feign.Response;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.LoginRequest;

@Headers({"Content-Type: application/json", "Accept: application/json"})
public interface TesterApi {
    @RequestLine("POST " + ApiRoutes.LOGIN)
    Response login(LoginRequest request);
}
