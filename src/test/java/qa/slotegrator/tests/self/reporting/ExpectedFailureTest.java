package qa.slotegrator.tests.self.reporting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import qa.slotegrator.fixtures.ExpectedFailureFixtures;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Строгий XFAIL на реальном Jupiter и Allure")
class ExpectedFailureTest {
    private static final Path RUN = Path.of("work", "self-xfail-native", UUID.randomUUID().toString());

    /**
     * Design: явный opt-in, точный известный дефект и независимые сбои в теле/подготовке/очистке.
     * Steps: выполнить фиксированные случаи отдельной JVM; сверить Jupiter, teardown, native Allure и Markdown бага.
     * Expected: default/false не преобразуют ошибки; true разрешает лишь выбранный дефект, XPASS проваливает тест.
     */
    @ParameterizedTest(name = "xfail.enabled={0}")
    @ValueSource(strings = {"default", "false", "true"})
    @DisplayName("XFAIL включается явно, сохраняет неизвестные ошибки и выполняет teardown")
    void verifiesNativeLifecycle(String mode) throws Exception {
        boolean enabled = mode.equals("true");
        Path directory = RUN.resolve(mode).toAbsolutePath();
        Files.createDirectories(directory.resolve("docs/bugs"));
        for (String bug : Set.of("BUG-001", "BUG-002", "BUG-003", "BUG-004", "BUG-005", "BUG-006", "BUG-008"))
            Files.writeString(directory.resolve("docs/bugs/" + bug + ".md"), "Локальная фикстура");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
                "-Dallure.results.directory=" + directory.resolve("allure-results")));
        if (!mode.equals("default"))
            command.add("-Dxfail.enabled=" + mode);
        command.addAll(List.of("--class-path", classpath, ExpectedFailureFixtures.class.getName()));
        var builder = new ProcessBuilder(command);
        builder.environment().keySet().removeAll(Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        var process = builder.directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("process.log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Локальная проверка XFAIL не завершилась за 30 секунд");
        }
        assertEquals(0, process.exitValue(), "Не удалось выполнить фиксированные случаи XFAIL");
        var codec = new JsonCodec();
        var evidence = codec.tree(Files.readAllBytes(directory.resolve("evidence.json")));
        var results = new HashMap<String, JsonNode>();
        try (var files = Files.list(directory.resolve("allure-results"))) {
            for (Path path : files.filter(file -> file.toString().endsWith("-result.json")).toList()) {
                var result = codec.tree(Files.readAllBytes(path));
                assertTrue(results.put(label(result, "junit.platform.uniqueid"), result) == null,
                        "Нативный результат не должен дублироваться");
            }
        }
        assertEquals(27, evidence.size(), "Нужны все конкретные случаи и фазы жизненного цикла");
        assertEquals(evidence.size(), results.size(), "Каждый случай должен иметь нативный Allure");
        for (JsonNode test : evidence) {
            String fixture = test.get("fixture").stringValue();
            String name = test.get("name").stringValue();
            var result = results.get(test.get("id").stringValue());
            assertTrue(test.get("teardownSeen").booleanValue(), fixture + ": teardown обязан выполниться");
            String bug = knownBug(fixture, name);
            if (!bug.isEmpty()) {
                assertTrue(hasBugLink(result, bug, directory), "Ссылка на баг нужна и в строгом режиме");
                assertTrue(hasBugAttachment(result, bug, directory.resolve("allure-results")),
                        "Описание бага нужно и в строгом режиме");
            } else if (fixture.equals("Cleanup")) {
                // Тело воспроизвело BUG-001 до сбоя очистки: ссылка уместна, но статус остаётся неуспешным.
                assertTrue(hasBugLink(result, "BUG-001", directory), "Воспроизведённый дефект тела связан с багом");
            } else if (!name.equals("XPASS")) {
                assertTrue(result.get("links") == null || result.get("links").isEmpty(),
                        fixture + ": посторонний исход не получает ссылку на баг");
                assertEquals("", label(result, "knownBug"), fixture + ": посторонний исход не считается известным");
            }
            boolean xfail = enabled && !bug.isEmpty();
            boolean xpass = enabled && name.equals("XPASS");
            boolean notReproduced = !enabled && name.equals("XPASS");
            boolean passed = name.equals("UNSELECTED") || name.equals("email:NULL") || notReproduced;
            assertEquals(xfail ? "ABORTED" : passed ? "SUCCESSFUL" : "FAILED",
                    test.get("status").stringValue(), fixture + ": " + name);
            assertEquals(xfail ? "XFAIL" : xpass ? "XPASS" : notReproduced ? "NOT_REPRODUCED" : "",
                    label(result, "expectedFailure"), fixture + ": посторонняя ошибка не должна получить XFAIL");
            if (xfail) {
                assertEquals("skipped", result.get("status").stringValue());
                assertEquals(bug, label(result, "knownBug"));
                assertTrue(result.get("statusDetails").get("message").stringValue().startsWith("XFAIL " + bug));
                assertTrue(hasBugLink(result, bug, directory), "Нужна ссылка на существующий Markdown");
                assertTrue(hasBugAttachment(result, bug, directory.resolve("allure-results")),
                        "Переносимый отчёт должен содержать Markdown бага");
            } else if (passed) {
                assertEquals("passed", result.get("status").stringValue());
            } else {
                assertTrue(Set.of("failed", "broken").contains(result.get("status").stringValue()),
                        fixture + ": ошибка тела, setup или cleanup не может стать skipped");
                if (xpass)
                    assertTrue(result.get("statusDetails").get("message").stringValue().startsWith("XPASS BUG-001"));
            }
        }
    }

    private static String knownBug(String fixture, String name) {
        if (fixture.equals("Fields") && name.equals("email:MISSING"))
            return "BUG-001";
        if (!fixture.equals("Examples"))
            return "";
        return switch (name) {
            case "KNOWN_STATUS" -> "BUG-001";
            case "KNOWN_TOKEN" -> "BUG-002";
            case "KNOWN_PLAYER_ID" -> "BUG-003";
            case "KNOWN_PROFILE" -> "BUG-004";
            case "KNOWN_LIST" -> "BUG-005";
            case "KNOWN_BASIC" -> "BUG-006";
            case "KNOWN_CREATE" -> "BUG-008";
            default -> "";
        };
    }

    private static String label(JsonNode result, String key) {
        for (JsonNode label : result.get("labels"))
            if (label.get("name").stringValue().equals(key))
                return label.get("value").stringValue();
        return "";
    }

    private static boolean hasBugLink(JsonNode result, String bug, Path directory) {
        for (JsonNode link : result.get("links"))
            if (link.get("name").stringValue().equals(bug)
                    && link.get("url").stringValue()
                            .equals(directory.resolve("docs/bugs/" + bug + ".md").toUri().toString()))
                return true;
        return false;
    }

    private static boolean hasBugAttachment(JsonNode executable, String bug, Path results) throws Exception {
        var attachments = executable.get("attachments");
        if (attachments != null)
            for (JsonNode attachment : attachments)
                if (attachment.get("name").stringValue().equals("Описание " + bug)
                        && attachment.get("type").stringValue().equals("text/markdown")
                        && Files.readString(results.resolve(attachment.get("source").stringValue()),
                                StandardCharsets.UTF_8)
                                .equals("Локальная фикстура"))
                    return true;
        var steps = executable.get("steps");
        if (steps != null)
            for (JsonNode step : steps)
                if (hasBugAttachment(step, bug, results))
                    return true;
        return false;
    }
}
