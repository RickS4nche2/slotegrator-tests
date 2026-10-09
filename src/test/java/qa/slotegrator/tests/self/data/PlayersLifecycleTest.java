package qa.slotegrator.tests.self.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

import qa.slotegrator.fixtures.PlayersLifecycleFixtures;
import qa.slotegrator.fixtures.PlayersLifecycleFixtures.FixtureCase;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("Целевой E2E на исправном локальном API и адресных мутантах")
class PlayersLifecycleTest {
    private static final Path RUN = Path.of("work/self-players-lifecycle", UUID.randomUUID().toString());
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);
    private static final List<String> SCENARIO_STEPS = List.of(
            "Получить токен тестера и проверить вход", "Создать 12 игроков в пустом контексте",
            "Прочитать и проверить профили всех 12 игроков",
            "Проверить полный список и отсортировать игроков по имени",
            "Удалить всех подтверждённых собственных игроков", "Подтвердить полностью пустой итоговый список");
    private final JsonCodec codec = new JsonCodec();

    /**
     * Design: исправленный контракт, неверная адресация чтения/удаления, коды, поля и отказ cleanup.
     * Steps: запустить единственный настоящий E2E отдельной JVM на loopback, прочитать Jupiter и Allure.
     * Expected: исправный API проходит шесть шагов; мутант обнаружен предметным шагом, cleanup независим.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureCase.class)
    void verifiesProductionLifecycle(FixtureCase fixture) throws Exception {
        var run = runProductionLocally(fixture);
        var evidence = run.evidence();
        boolean nominal = fixture == FixtureCase.NOMINAL;
        assertEquals(1, evidence.get("discoveredTests").intValue());
        assertEquals(1, evidence.get("finishedTests").intValue());
        assertEquals(PlayersLifecycleFixtures.PRODUCTION_CLASS, evidence.get("productionClass").stringValue());
        assertEquals("completePlayersLifecycle", evidence.get("productionMethod").stringValue());
        assertEquals(nominal ? "SUCCESSFUL" : "FAILED", evidence.get("junitStatus").stringValue());
        assertEquals(fixture == FixtureCase.MISSING_CONTEXT ? "broken" : nominal ? "passed" : "failed",
                run.result().get("status").stringValue());
        assertEquals(!nominal && fixture != FixtureCase.MISSING_CONTEXT,
                evidence.get("primaryAssertion").booleanValue());
        assertEquals(List.of(), strings(evidence.get("violations")));
        assertTrue(evidence.get("loopbackConfirmed").booleanValue());
        verifyOwnership(evidence, fixture);
        verifySubjectSteps(run.result(), fixture);
        if (nominal)
            verifyActualSorting(run);
        if (fixture == FixtureCase.ASSERTION_AND_CLEANUP_FAIL) {
            assertTrue(strings(evidence.get("suppressedTypes")).contains("CleanupFailure"));
            assertTrue(run.result().get("statusDetails").get("trace").stringValue().contains("CleanupFailure"));
        }
        Allure.attachment("Наблюдения локального E2E", "application/json", codec.text(evidence));
    }

    private void verifyOwnership(JsonNode evidence, FixtureCase fixture) {
        var created = strings(evidence.get("createdIds"));
        var deleted = strings(evidence.get("deletedIds"));
        var attempts = strings(evidence.get("deleteAttempts"));
        assertTrue(created.containsAll(attempts), "DELETE разрешён только для ID собственных POST");
        assertEquals(new HashSet<>(deleted).size(), deleted.size(), "Запись не может исчезнуть дважды");
        List<String> remaining = strings(evidence.get("remainingIds"));
        if (fixture == FixtureCase.MISSING_CONTEXT) {
            assertEquals(0, evidence.get("httpCalls").intValue(), "До разрешения запрещён любой HTTP");
        } else if (fixture == FixtureCase.LOGIN_201) {
            assertEquals(1, evidence.get("httpCalls").intValue(), "Отклонение входа обнаружено до создания игроков");
        } else if (fixture == FixtureCase.FOREIGN_INITIAL) {
            assertTrue(evidence.get("foreignIntact").booleanValue());
            assertEquals(0, evidence.get("createCalls").intValue());
            assertEquals(1, remaining.size());
        } else if (fixture == FixtureCase.ASSERTION_AND_CLEANUP_FAIL) {
            assertEquals(List.of(created.getFirst()), remaining);
            assertEquals(new HashSet<>(created.subList(1, created.size())), new HashSet<>(deleted));
        } else {
            assertEquals(List.of(), remaining, "Резервная очистка должна убрать оставшихся собственных игроков");
            assertEquals(new HashSet<>(created), new HashSet<>(deleted));
        }
        if (fixture == FixtureCase.NOMINAL) {
            assertEquals(12, created.size());
            assertEquals(created, strings(evidence.get("profileIds")), "Проверяется каждый собственный профиль");
            assertEquals(created.reversed(), attempts, "Проверяемые DELETE идут в обратном порядке создания");
        }
        if (fixture == FixtureCase.DELETE_OLDEST || fixture == FixtureCase.DELETE_WRONG_ID
                || fixture == FixtureCase.DELETE_REMOVES_ALL || fixture == FixtureCase.CLEANUP_FAIL_FIRST)
            assertEquals(created.getLast(), attempts.getFirst(), "Первое удаление различает первую и целевую запись");
    }

    private void verifySubjectSteps(JsonNode result, FixtureCase fixture) {
        int failedStep = switch (fixture) {
            case MISSING_CONTEXT -> -1;
            case LOGIN_201 -> 0;
            case FOREIGN_INITIAL, CREATE_REJECTED_BUT_PERSISTED -> 1;
            case LAST_PROFILE_MISMATCH, ASSERTION_AND_CLEANUP_FAIL, PROFILE_201, PROFILE_IGNORES_EMAIL -> 2;
            case LIST_MISMATCH -> 3;
            case CLEANUP_FAIL_FIRST, DELETE_REMOVES_ALL, DELETE_OLDEST, DELETE_WRONG_ID -> 4;
            case NOMINAL -> 6;
        };
        var steps = result.get("steps");
        if (failedStep == -1) {
            assertTrue(steps == null || steps.isEmpty());
            return;
        }
        int subjectSteps = Math.min(failedStep + 1, 6);
        for (int index = 0; index < subjectSteps; index++) {
            assertEquals(SCENARIO_STEPS.get(index), steps.get(index).get("name").stringValue());
            assertEquals(index == failedStep ? "failed" : "passed", steps.get(index).get("status").stringValue());
        }
        for (int index = subjectSteps; index < steps.size(); index++) {
            assertEquals("Резервная очистка собственных игроков", steps.get(index).get("name").stringValue());
            assertEquals(fixture == FixtureCase.ASSERTION_AND_CLEANUP_FAIL ? "broken" : "passed",
                    steps.get(index).get("status").stringValue());
        }
    }

    private NativeRun runProductionLocally(FixtureCase fixture) throws Exception {
        Path directory = RUN.resolve(fixture.name()).toAbsolutePath();
        Files.createDirectories(directory);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "--class-path", classpath, PlayersLifecycleFixtures.class.getName(),
                fixture.name(), directory.toString());
        builder.environment().keySet().removeAll(PlayersLifecycleFixtures.OVERRIDING_ENVIRONMENT);
        var process = builder.redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile())
                .start();
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            fail("Локальный E2E не завершился за 45 секунд");
        }
        assertEquals(0, process.exitValue(), "Локальная фикстура должна завершить Jupiter и записать результаты");
        var evidence = codec.tree(Files.readString(directory.resolve("evidence.json"), StandardCharsets.UTF_8));
        List<Path> results;
        try (var files = Files.list(directory.resolve("allure-results"))) {
            results = files.filter(path -> path.getFileName().toString().endsWith("-result.json")).toList();
        }
        assertEquals(1, results.size());
        return new NativeRun(directory.resolve("allure-results"), evidence,
                codec.tree(Files.readAllBytes(results.getFirst())));
    }

    private void verifyActualSorting(NativeRun run) throws Exception {
        assertTrue(run.evidence().get("insertionOrderUnsorted").booleanValue());
        var sources = new ArrayList<String>();
        for (var step : run.result().get("steps").get(3).get("steps")) {
            if ("Имена после сортировки".equals(step.get("name").stringValue()))
                for (var attachment : step.get("attachments"))
                    sources.add(attachment.get("source").stringValue());
        }
        assertEquals(1, sources.size());
        var actual = strings(codec.tree(Files.readAllBytes(run.allureDirectory().resolve(sources.getFirst()))));
        // Независимый явно заданный эталон, без вызова того же сортировщика в проверке.
        assertEquals(List.of("Alice", "Alice", "Boris", "Daniel", "Elena", "George", "Kirill", "Maria", "Nina",
                "Olga", "Victor", "Zoya"), actual);
    }

    private static List<String> strings(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(value -> result.add(value.stringValue()));
        return List.copyOf(result);
    }

    private record NativeRun(Path allureDirectory, JsonNode evidence, JsonNode result) {
    }
}
