package qa.slotegrator.helpers.reporting;

import java.io.IOException;
import java.util.Map;

import feign.Client;
import feign.Request;
import feign.Response;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class ReportingFeignClient implements Client {
    private final Client delegate;
    private final HttpDiagnostics diagnostics;

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        Response response;
        byte[] body;
        try {
            response = delegate.execute(request, options);
            try (var stream = response.body() == null ? null : response.body().asInputStream()) {
                body = stream == null ? new byte[0] : stream.readAllBytes();
            }
        } catch (IOException | RuntimeException exception) {
            diagnostics.exchange(request.httpMethod().name(), request.url(), request.headers(), request.body(),
                    0, Map.of(), new byte[0]);
            throw new IOException(diagnostics.failure(exception));
        }
        diagnostics.exchange(request.httpMethod().name(), request.url(), request.headers(), request.body(),
                response.status(), response.headers(), body);
        return response.toBuilder().body(body).build();
    }
}
