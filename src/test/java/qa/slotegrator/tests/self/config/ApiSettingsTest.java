package qa.slotegrator.tests.self.config;

import java.net.URI;
import java.time.Duration;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import qa.slotegrator.helpers.config.ApiSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Настройки API")
class ApiSettingsTest {

    @Test
    @DisplayName("Адрес локального ответчика принимается без обращения к стенду")
    void acceptsLocalAddress() {
        Allure.step("Создать настройки локального HTTP-клиента", () -> {
            var settings = settings(URI.create("http://127.0.0.1:12345"));
            assertEquals("127.0.0.1", settings.baseUri().getHost());
            assertEquals(Duration.ofSeconds(2), settings.readTimeout());
        });
    }

    @ParameterizedTest(name = "Недопустимая схема: {0}")
    @ValueSource(strings = {"ftp://localhost", "file:///tmp/data", "/relative"})
    @DisplayName("Принимаются только абсолютные HTTP(S)-адреса")
    void rejectsOtherSchemes(String address) {
        assertThrows(IllegalArgumentException.class, () -> settings(URI.create(address)));
    }

    @Test
    @DisplayName("Реквизиты в адресе отклоняются и не попадают в исключение")
    void rejectsCredentialsWithoutExposingThem() {
        String marker = "dummy-sensitive-value";
        var exception = assertThrows(IllegalArgumentException.class,
                () -> settings(URI.create("https://tester:" + marker + "@localhost")));
        assertFalse(exception.getMessage().contains(marker), "Исключение раскрывает реквизиты");
    }

    @ParameterizedTest(name = "Недопустимый таймаут: {0} мс")
    @ValueSource(longs = {0, -1, 2_147_483_648L})
    @DisplayName("Таймауты исключают бесконечное ожидание и переполнение транспорта")
    void rejectsUnboundedTimeout(long millis) {
        assertThrows(IllegalArgumentException.class, () -> new ApiSettings(
                URI.create("http://localhost"), Duration.ofMillis(millis), Duration.ofSeconds(2)));
    }

    private static ApiSettings settings(URI uri) {
        return new ApiSettings(uri, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }
}
