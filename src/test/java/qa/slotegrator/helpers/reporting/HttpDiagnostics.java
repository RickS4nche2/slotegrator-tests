package qa.slotegrator.helpers.reporting;

import java.net.URI;
import java.util.Collection;
import java.util.Map;

import io.qameta.allure.Allure;
import io.qameta.allure.http.HttpExchange;
import io.qameta.allure.http.HttpExchangeBody;
import lombok.RequiredArgsConstructor;

/** Адаптер безопасных данных в нативное HTTP-вложение Allure 3. */
@RequiredArgsConstructor
public final class HttpDiagnostics {
    private final ReportSanitizer sanitizer;

    public void exchange(String method, String url, Map<String, ? extends Collection<String>> requestHeaders,
            byte[] requestBody, int status, Map<String, ? extends Collection<String>> responseHeaders,
            byte[] responseBody) {
        sanitizer.rememberBody(requestBody);
        sanitizer.rememberBody(responseBody);
        // Сначала регистрируем все чувствительные заголовки, затем маскируем любые их отражения.
        requestHeaders.forEach((name, values) -> values.forEach(value -> sanitizer.header(name, value)));
        responseHeaders.forEach((name, values) -> values.forEach(value -> sanitizer.header(name, value)));
        var exchange = HttpExchange.builder().request(method, safeUrl(url), request -> {
            requestHeaders.forEach((name, values) -> values.forEach(value -> request.addHeader(
                    sanitizer.text(name), sanitizer.header(name, value))));
            request.setBody(HttpExchangeBody.utf8(sanitizer.body(requestBody)));
        });
        if (status > 0) {
            exchange.response(response -> {
                response.setStatus(status);
                responseHeaders.forEach((name, values) -> values
                        .forEach(value -> response.addHeader(sanitizer.text(name), sanitizer.header(name, value))));
                response.setBody(HttpExchangeBody.utf8(sanitizer.body(responseBody)));
            });
        }
        Allure.addHttpExchange("HTTP " + method, exchange.build());
    }

    public String failure(Throwable error) {
        String message = "Ошибка HTTP: " + error.getClass().getSimpleName();
        Allure.attachment("Ошибка транспорта", message);
        // Сообщение и cause внешнего транспорта не копируются: они могут содержать тело или URL с секретом.
        return message;
    }

    private String safeUrl(String url) {
        try {
            URI uri = URI.create(url);
            return sanitizer.text(
                    new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString());
        } catch (Exception ignored) {
            return "[адрес скрыт]";
        }
    }
}
