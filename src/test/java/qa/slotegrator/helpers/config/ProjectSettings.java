package qa.slotegrator.helpers.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/** Окружение имеет приоритет над явно поддерживаемым локальным properties-файлом. */
public record ProjectSettings(ApiSettings api, AuthSettings auth, String currencyCode) {
    public static ProjectSettings load() {
        Path path = Path.of(System.getProperty("api.config.file", "config.local.properties"));
        return load(path, System.getenv());
    }

    /** Читает указанный файл в UTF-8; переданное окружение имеет приоритет, глобальное окружение не меняется. */
    public static ProjectSettings load(Path path, Map<String, String> environment) {
        Properties values = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException | IllegalArgumentException exception) {
                throw new IllegalArgumentException("Не удалось прочитать локальную конфигурацию API");
            }
        }
        try {
            var api = new ApiSettings(URI.create(value(values, environment, "BASE_URL", "https://testslotegrator.com")),
                    Duration.ofMillis(Long.parseLong(value(values, environment, "CONNECT_TIMEOUT_MS", "5000"))),
                    Duration.ofMillis(Long.parseLong(value(values, environment, "READ_TIMEOUT_MS", "15000"))));
            var auth = new AuthSettings(value(values, environment, "BASIC_AUTH_USERNAME", null),
                    value(values, environment, "BASIC_AUTH_PASSWORD", null),
                    value(values, environment, "TESTER_EMAIL", null),
                    value(values, environment, "TESTER_PASSWORD", null));
            String currency = value(values, environment, "CURRENCY_CODE", null);
            if (currency == null || currency.isBlank())
                throw new IllegalArgumentException("Не задано CURRENCY_CODE");
            return new ProjectSettings(api, auth, currency);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Таймауты должны быть целыми числами миллисекунд");
        } catch (IllegalArgumentException exception) {
            // URI.create может включить исходный URI в сообщение. Разрешены лишь заранее известные сообщения.
            String message = exception.getMessage();
            if (message != null && (message.startsWith("Не задано ") || message.startsWith("CONNECT_TIMEOUT_MS ")
                    || message.startsWith("READ_TIMEOUT_MS ") || message.startsWith("BASE_URL должен ")))
                throw exception;
            throw new IllegalArgumentException("Некорректная конфигурация API");
        }
    }

    private static String value(Properties values, Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        return value != null ? value : values.getProperty(name, fallback);
    }

    @Override
    public String toString() {
        return "ProjectSettings[реквизиты скрыты]";
    }
}
