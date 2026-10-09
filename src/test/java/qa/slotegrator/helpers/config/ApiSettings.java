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

    private static void validateTimeout(Duration timeout, String name) {
        Objects.requireNonNull(timeout, "Нужен таймаут " + name);
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(name + " должен быть от 1 до " + Integer.MAX_VALUE + " мс");
        }
    }
}
