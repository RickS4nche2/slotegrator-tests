package qa.slotegrator.helpers.reporting;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import qa.slotegrator.helpers.json.JsonCodec;

public final class SafeAllureFilter implements Filter {
    private final JsonCodec codec;
    private final HttpDiagnostics diagnostics;

    public SafeAllureFilter(JsonCodec codec, HttpDiagnostics diagnostics) {
        this.codec = codec;
        this.diagnostics = diagnostics;
    }

    @Override
    public Response filter(FilterableRequestSpecification request, FilterableResponseSpecification responseSpec,
            FilterContext context) {
        Object body = request.getBody();
        byte[] requestBody = body == null
                ? new byte[0]
                : body instanceof byte[] bytes
                        ? bytes
                        : body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : codec.encode(body);
        Map<String, List<String>> headers = headers(request.getHeaders());
        Response response;
        try {
            response = context.next(request, responseSpec);
        } catch (Exception exception) {
            diagnostics.exchange(request.getMethod(), request.getURI(), headers, requestBody, 0, Map.of(), new byte[0]);
            throw new IllegalStateException(diagnostics.failure(exception));
        }
        diagnostics.exchange(request.getMethod(), request.getURI(), headers, requestBody,
                response.statusCode(), headers(response.getHeaders()), response.asByteArray());
        return response;
    }

    private static Map<String, List<String>> headers(io.restassured.http.Headers input) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        input.forEach(
                header -> result.computeIfAbsent(header.getName(), key -> new ArrayList<>()).add(header.getValue()));
        return result;
    }
}
