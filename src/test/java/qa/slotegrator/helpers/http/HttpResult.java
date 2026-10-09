package qa.slotegrator.helpers.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import qa.slotegrator.helpers.json.PayloadException;

public record HttpResult(int status, Map<String, Collection<String>> headers, byte[] body) {
    public HttpResult {
        headers = headers.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public static HttpResult read(feign.Response response) {
        try (response) {
            byte[] bytes = response.body() == null ? new byte[0] : response.body().asInputStream().readAllBytes();
            return new HttpResult(response.status(), response.headers(), bytes);
        } catch (IOException exception) {
            throw new PayloadException("Не удалось прочитать HTTP-ответ");
        }
    }

    public static HttpResult json(int status, String json) {
        return new HttpResult(status, Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResult read(io.restassured.response.Response response) {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        response.getHeaders().forEach(header -> headers.computeIfAbsent(header.getName(),
                key -> new ArrayList<>()).add(header.getValue()));
        return new HttpResult(response.statusCode(), headers, response.asByteArray());
    }

    @Override
    public String toString() {
        return "HttpResult[status=" + status + ", размер тела=" + body.length + "]";
    }
}
