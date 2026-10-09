package qa.slotegrator.tests.self.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qa.slotegrator.helpers.config.ProjectSettings;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Локальная конфигурация API")
class ProjectSettingsTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("UTF-8 сохраняется, окружение имеет приоритет над файлом")
    void loadsUtf8WithEnvironmentOverride() throws Exception {
        var settings = ProjectSettings.load(configuration(""), Map.of("CURRENCY_CODE", "OVERRIDE"));
        assertAll(
                () -> assertTrue("dummy-пароль".equals(settings.auth().testerPassword())),
                () -> assertEquals("OVERRIDE", settings.currencyCode()),
                () -> assertEquals(5000, settings.api().connectTimeout().toMillis()),
                () -> assertFalse(settings.toString().contains("dummy-пароль")));
    }

    @Test
    @DisplayName("Пустое значение окружения не подменяется секретом из файла")
    void blankEnvironmentDoesNotFallBack() throws Exception {
        Path file = configuration("");
        var error = assertThrows(IllegalArgumentException.class,
                () -> ProjectSettings.load(file, Map.of("TESTER_PASSWORD", "")));
        assertEquals("Не задано TESTER_PASSWORD", error.getMessage());
    }

    @Test
    @DisplayName("Некорректный URI с секретом не попадает в исключение")
    void invalidAddressDoesNotExposeItsValue() throws Exception {
        Path file = configuration("BASE_URL=https://dummy-url-secret@broken host\n");
        var error = assertThrows(IllegalArgumentException.class, () -> ProjectSettings.load(file, Map.of()));
        assertFalse(error.getMessage().contains("dummy-url-secret"));
        assertNull(error.getCause());
    }

    @Test
    @DisplayName("Отсутствие файла и реквизитов останавливает подготовку до HTTP")
    void missingConfigurationIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ProjectSettings.load(directory.resolve("absent.properties"), Map.of()));
        assertEquals("Не задано BASIC_AUTH_USERNAME", error.getMessage());
    }

    private Path configuration(String extra) throws Exception {
        Path file = directory.resolve("config.local.properties");
        Files.writeString(file, """
                BASIC_AUTH_USERNAME=local_user
                BASIC_AUTH_PASSWORD=dummy-local-basic
                TESTER_EMAIL=local@example.test
                TESTER_PASSWORD=dummy-пароль
                CURRENCY_CODE=TEST
                """ + extra, StandardCharsets.UTF_8);
        return file;
    }
}
