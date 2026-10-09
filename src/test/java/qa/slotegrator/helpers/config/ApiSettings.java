package qa.slotegrator.helpers.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Общие настройки HTTP; реквизиты авторизации сюда не входят. */
public record ApiSettings(URI baseUri, Duration connectTimeout, Duration readTimeout) {

    public ApiSettings {
        Objects.requireNonNull(baseUri, "Нужен адрес API");
        if ((!"https".equalsIgnoreCase(baseUri.getScheme()) && !"http".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("BASE_URL должен быть HTTP(S)-адресом без реквизитов, query и fragment");
        }
        validateTimeout(connectTimeout, "CONNECT_TIMEOUT_MS");
        validateTimeout(readTimeout, "READ_TIMEOUT_MS");
    }

    public static ApiSettings fromEnvironment() {
        return new ApiSettings(
                baseUriFromEnvironment(),
                timeoutFromEnvironment("CONNECT_TIMEOUT_MS", 5_000),
                timeoutFromEnvironment("READ_TIMEOUT_MS", 15_000));
    }

    private static URI baseUriFromEnvironment() {
        try {
            return URI.create(environmentOrDefault("BASE_URL", "https://testslotegrator.com"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("BASE_URL должен быть корректным URI");
        }
    }

    private static Duration timeoutFromEnvironment(String name, long defaultMillis) {
        try {
            return Duration.ofMillis(Long.parseLong(environmentOrDefault(name, Long.toString(defaultMillis))));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " должен быть целым числом миллисекунд");
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void validateTimeout(Duration timeout, String name) {
        Objects.requireNonNull(timeout, "Нужен таймаут " + name);
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(name + " должен быть от 1 до " + Integer.MAX_VALUE + " мс");
        }
    }
}
