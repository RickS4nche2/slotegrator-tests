package qa.slotegrator.tests.self.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import qa.slotegrator.fixtures.EndpointFixtures;
import qa.slotegrator.fixtures.EndpointFixtures.FixtureCase;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("Независимые endpoint IT на локальном HTTP с исходным чужим игроком")
class IndependentEndpointsTest {
    private static final Path RUN = Path.of("work/self-endpoints-native", UUID.randomUUID().toString());
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(90);
    private static final List<String> FOREIGN_ONLY = List.of(EndpointFixtures.FOREIGN_ID);
    private static final Map<String, Integer> NOMINAL_METHODS = Map.ofEntries(
            method(EndpointFixtures.CREATION_CLASS, "createsPlayerWithoutChangingNeighbour", 1),
            method(EndpointFixtures.CREATION_CLASS, "acceptsPasswordsAtMinimumLength", 1),
            method(EndpointFixtures.CREATION_CLASS, "acceptsUsernameAtMinimumLength", 1),
            method(EndpointFixtures.CREATION_CLASS, "rejectsMatchingShortPasswords", 1),
            method(EndpointFixtures.CREATION_CLASS, "rejectsInvalidPlayer", 21),
            method(EndpointFixtures.CREATION_CLASS, "rejectsUnauthorizedCreation", 2),
            method(EndpointFixtures.PROFILE_CLASS, "profileMatchesOwnPlayer", 1),
            method(EndpointFixtures.PROFILE_CLASS, "rejectsInvalidLookup", 3),
            method(EndpointFixtures.PROFILE_CLASS, "rejectsUnauthorizedProfile", 2),
            method(EndpointFixtures.LIST_CLASS, "repeatedListPreservesPlayersWithSameName", 1),
            method(EndpointFixtures.LIST_CLASS, "rejectsUnauthorizedList", 2),
            method(EndpointFixtures.DELETION_CLASS, "deletesOnlyOwnTarget", 1),
            method(EndpointFixtures.DELETION_CLASS, "rejectsUnauthorizedDeletion", 2),
            method(EndpointFixtures.LOGIN_CLASS, "issuedTokenGrantsPlayerAccess", 1),
            method(EndpointFixtures.LOGIN_CLASS, "rejectsInvalidBasic", 2),
            method(EndpointFixtures.LOGIN_CLASS, "rejectsWrongTesterPassword", 1),
            method(EndpointFixtures.LOGIN_CLASS, "rejectsInvalidCredentials", 7));
    private final JsonCodec codec = new JsonCodec();

    static Stream<EndpointCase> endpointCases() {
        return Stream.of(
                new EndpointCase("Все endpoint-случаи независимы и сохраняют чужую запись", FixtureCase.NOMINAL,
                        NOMINAL_METHODS, ""),
                new EndpointCase("Исправленные 200 и integer id не ломают подготовку или функциональные тесты",
                        FixtureCase.CORRECTED_API, NOMINAL_METHODS, ""),
                new EndpointCase("DELETE первой записи вместо адресной цели обнаруживается",
                        FixtureCase.DELETE_OLDEST,
                        Map.of(EndpointFixtures.DELETION_CLASS + ".deletesOnlyOwnTarget", 1), "verifyDeleted"),
                new EndpointCase("getOne с игнорированием email обнаруживается",
                        FixtureCase.PROFILE_IGNORES_EMAIL,
                        Map.of(EndpointFixtures.PROFILE_CLASS + ".profileMatchesOwnPlayer", 1), "verifyProfile"),
                new EndpointCase("HTTP 400 при сохранении игрока обнаруживается и очищается",
                        FixtureCase.CREATE_ERROR_PERSISTS,
                        Map.of(EndpointFixtures.CREATION_CLASS + ".rejectsInvalidPlayer", 21), "verifyRejectedCreate"),
                new EndpointCase("DELETE с HTTP 401 не скрывает фактическое удаление цели",
                        FixtureCase.DELETE_UNAUTHORIZED_MUTATES,
                        Map.of(EndpointFixtures.DELETION_CLASS + ".rejectsUnauthorizedDeletion", 2),
                        "verifyRejectedOperation"),
                new EndpointCase("Удаление соседа вместе с целью обнаруживается до teardown",
                        FixtureCase.DELETE_REMOVES_NEIGHBOR,
                        Map.of(EndpointFixtures.DELETION_CLASS + ".deletesOnlyOwnTarget", 1), "verifyDeleted"),
                new EndpointCase("Один CREATE не может незаметно сохранить две одинаковые записи",
                        FixtureCase.CREATE_DUPLICATES_RECORD,
                        Map.of(EndpointFixtures.CREATION_CLASS + ".createsPlayerWithoutChangingNeighbour", 1),
                        "verifyCreationState"),
                new EndpointCase("HTTP 401 не разрешает раскрытие одного идентификатора игрока",
                        FixtureCase.UNAUTHORIZED_LEAKS_IDENTIFIER,
                        Map.of(EndpointFixtures.LIST_CLASS + ".rejectsUnauthorizedList", 2),
                        "verifyNoPlayerIdentifiers"));
    }

    /**
     * Design: изоляция конкретных параметров, непустой baseline, запрещённые изменения и раскрытие ID при отказе.
     * Steps: запустить фиксированные production IT отдельной JVM; проверить HTTP, Jupiter и native Allure.
     * Expected: номинальные случаи успешны; дефекты падают в предметном Expected, собственные записи очищены.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpointCases")
    @DisplayName("Настоящие endpoint IT проверяют последствия и завершают каждый случай без собственных данных")
    void verifiesIndependentProductionEndpoints(EndpointCase scenario) throws Exception {
        var run = runProductionLocally(scenario);
        verifyExactNativeCases(run, scenario);
        verifyIndependentOwnership(run.evidence(), scenario);
        Allure.attachment("Безопасные наблюдения endpoint IT на localhost", "application/json",
                codec.text(run.evidence()));
    }

    private NativeRun runProductionLocally(EndpointCase scenario) throws Exception {
        Path directory = RUN.resolve(scenario.fixture().name()).toAbsolutePath();
        Files.createDirectories(directory);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dallure.results.directory=" + directory.resolve("allure-results"),
                "--class-path", classpath, EndpointFixtures.class.getName(), scenario.fixture().name(),
                directory.toString());
        builder.environment().keySet().removeAll(EndpointFixtures.OVERRIDING_ENVIRONMENT);
        var process = builder.redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile())
                .start();
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            fail("Локальные endpoint IT не завершились за 90 секунд");
        }
        assertEquals(0, process.exitValue(), "Не удалось выполнить ограниченную нативную endpoint-фикстуру");
        var evidence = codec.tree(Files.readString(directory.resolve("evidence.json"), StandardCharsets.UTF_8));
        var results = new ArrayList<JsonNode>();
        try (var files = Files.list(directory.resolve("allure-results"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("-result.json")).toList())
                results.add(codec.tree(Files.readAllBytes(file)));
        }
        return new NativeRun(evidence, List.copyOf(results));
    }

    private void verifyExactNativeCases(NativeRun run, EndpointCase scenario) {
        var evidence = run.evidence();
        int expectedCount = scenario.methods().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(scenario.fixture().name(), evidence.get("fixtureCase").stringValue());
        assertTrue(evidence.get("loopbackConfirmed").booleanValue());
        assertEquals(expectedCount, evidence.get("finishedTests").intValue(),
                "Нужны все конкретные parameter invocations");
        assertEquals(expectedCount, evidence.get("invocations").size());
        assertEquals(expectedCount, run.results().size(), "Каждый endpoint-случай должен иметь native Allure result");
        assertEquals(List.of(), strings(evidence.get("containerFailures")),
                "Не должно быть сбоев discovery или контейнеров");
        assertEquals(List.of(), strings(evidence.get("violations")), "HTTP вышел за разрешённый локальный контракт");
        var actualMethods = new LinkedHashMap<String, Integer>();
        var identifiers = new HashSet<String>();
        boolean nominal = scenario.fixture() == FixtureCase.NOMINAL || scenario.fixture() == FixtureCase.CORRECTED_API;
        for (var invocation : evidence.get("invocations")) {
            String identity = invocation.get("productionClass").stringValue() + "."
                    + invocation.get("productionMethod").stringValue();
            actualMethods.merge(identity, 1, Integer::sum);
            assertTrue(identifiers.add(invocation.get("uniqueId").stringValue()),
                    "Concrete invocation не должен повторяться");
            assertEquals(nominal ? "SUCCESSFUL" : "FAILED", invocation.get("junitStatus").stringValue(), identity);
            assertEquals(!nominal, invocation.get("primaryAssertion").booleanValue(),
                    "Дефект должен падать assertion в Expected");
            if (nominal) {
                assertEquals("NONE", invocation.get("errorType").stringValue());
            } else {
                assertTrue(strings(invocation.get("expectedFrames")).contains(
                        "qa.slotegrator.expecteds.PlayersExpected." + scenario.expectedMethod()),
                        "Дефект должен обнаруживаться выбранным предметным Expected до cleanup");
                assertFalse(strings(invocation.get("suppressedTypes")).contains("CleanupFailure"),
                        "Резервная очистка должна завершиться без дополнительного сбоя");
            }
        }
        assertEquals(scenario.methods(), actualMethods, "Состав конкретных production-методов отличается от покрытия");
        var allureMethods = new LinkedHashMap<String, Integer>();
        for (var result : run.results()) {
            allureMethods.merge(result.get("fullName").stringValue(), 1, Integer::sum);
            assertEquals(nominal ? "passed" : "failed", result.get("status").stringValue());
            if (!nominal)
                assertTrue(hasFailedStep(result.get("steps")),
                        "Native Allure должен сохранить провал verification-шага");
        }
        assertEquals(scenario.methods(), allureMethods, "Allure должен отражать те же методы и параметры, что Jupiter");
    }

    private void verifyIndependentOwnership(JsonNode evidence, EndpointCase scenario) {
        assertEquals(FOREIGN_ONLY, strings(evidence.get("remainingIds")));
        assertTrue(evidence.get("foreignIntact").booleanValue());
        var allOwnedIds = new HashSet<String>();
        var allRunMarkers = new HashSet<String>();
        var allIdentityMarkers = new HashSet<String>();
        for (var invocation : evidence.get("invocations")) {
            assertEquals(FOREIGN_ONLY, strings(invocation.get("beforeIds")), "Каждый случай начинает с одного FOREIGN");
            assertEquals(FOREIGN_ONLY, strings(invocation.get("afterIds")),
                    "Teardown каждого случая должен убрать свои записи");
            assertTrue(invocation.get("foreignIntact").booleanValue(), "FOREIGN должен сохранить все исходные поля");
            String methodName = invocation.get("productionMethod").stringValue();
            var http = elements(invocation.get("http"));
            if (EndpointFixtures.LOGIN_CLASS.equals(invocation.get("productionClass").stringValue())) {
                verifyLoginHasNoPlayerData(invocation, methodName, http, scenario.fixture());
                continue;
            }
            var creations = elements(invocation.get("creations"));
            assertEquals(2, creations.size(),
                    "У endpoint-случая должны быть собственная попытка/цель и контрольный игрок");
            assertEquals(1, count(http, "LOGIN"), "У каждого endpoint-случая должен быть отдельный вход");
            var ownedIds = new HashSet<String>();
            var runMarkers = new HashSet<String>();
            for (var creation : creations) {
                var markers = strings(creation.get("runMarkers"));
                assertEquals(1, markers.size(), "Попытка должна иметь уникальный UUID-признак даже при дефекте поля");
                runMarkers.addAll(markers);
                for (String field : List.of("emailMarker", "usernameMarker")) {
                    String identity = creation.get(field).stringValue();
                    if (!identity.isEmpty())
                        assertTrue(allIdentityMarkers.add(identity),
                                "Попытки не должны повторно использовать идентификационный признак");
                }
                var persistedIds = strings(creation.get("persistedIds"));
                String firstPersistedId = creation.get("id").stringValue();
                assertEquals(persistedIds.isEmpty() ? "" : persistedIds.getFirst(), firstPersistedId,
                        "Основной ID попытки соответствует первой реально сохранённой записи");
                for (String id : persistedIds) {
                    assertFalse(FOREIGN_ONLY.contains(id));
                    assertTrue(ownedIds.add(id), "Ответчик не должен повторно выдавать ID");
                    assertTrue(allOwnedIds.add(id), "Собственный ID не должен разделяться между случаями");
                }
            }
            assertEquals(1, runMarkers.size(), "Внутри одного случая данные принадлежат одному новому контексту");
            assertTrue(allRunMarkers.add(runMarkers.iterator().next()),
                    "UUID контекста не должен разделяться между случаями");
            var removedIds = new ArrayList<String>();
            for (var event : http) {
                if ("DELETE".equals(event.get("operation").stringValue())) {
                    String target = event.get("targetId").stringValue();
                    assertTrue(ownedIds.contains(target), "Удалять можно лишь собственный ID текущего случая");
                    removedIds.addAll(strings(event.get("removedIds")));
                }
            }
            assertEquals(ownedIds, new HashSet<>(removedIds),
                    "Все реально созданные ID должны быть фактически удалены");
            assertEquals(removedIds.size(), new HashSet<>(removedIds).size(), "Один ID не может исчезнуть дважды");
            verifyActionAndMutation(scenario, methodName, creations, http, ownedIds);
        }
    }

    private static void verifyLoginHasNoPlayerData(JsonNode invocation, String methodName, List<JsonNode> http,
            FixtureCase fixture) {
        assertEquals(0, invocation.get("creations").size(), "Проверки входа не создают игроков");
        boolean positive = "issuedTokenGrantsPlayerAccess".equals(methodName);
        assertEquals(positive ? 2 : 1, http.size(), "Негативный login не должен читать или менять игроков");
        assertEquals("LOGIN", http.getFirst().get("operation").stringValue());
        int expectedStatus = positive
                ? fixture == FixtureCase.CORRECTED_API ? 200 : 201
                : "rejectsInvalidCredentials".equals(methodName) ? 400 : 401;
        assertEquals(expectedStatus, http.getFirst().get("status").intValue());
        if (positive) {
            assertEquals("GET_ALL", http.getLast().get("operation").stringValue());
            assertEquals(200, http.getLast().get("status").intValue());
        }
    }

    private static void verifyActionAndMutation(EndpointCase scenario, String methodName, List<JsonNode> creations,
            List<JsonNode> http, Set<String> ownedIds) {
        var deletes = http.stream().filter(event -> "DELETE".equals(event.get("operation").stringValue())).toList();
        assertFalse(deletes.isEmpty(), "Собственные записи должны удаляться фактическим HTTP");
        var secondCreation = creations.getLast();
        switch (scenario.fixture()) {
            case CREATE_ERROR_PERSISTS -> {
                assertEquals(400, secondCreation.get("status").intValue());
                assertFalse(secondCreation.get("id").stringValue().isEmpty(),
                        "Дефект должен реально сохранить отклонённую запись");
                assertEquals(2, ownedIds.size());
                assertEquals(2, deletes.size(), "После failed Expected очищаются сосед и отклонённая запись");
            }
            case DELETE_UNAUTHORIZED_MUTATES -> {
                assertEquals(2, ownedIds.size());
                assertEquals(2, deletes.size(), "После дефектного DELETE teardown удаляет оставшегося соседа");
                assertEquals(401, deletes.getFirst().get("status").intValue());
                assertEquals(List.of(deletes.getFirst().get("targetId").stringValue()),
                        strings(deletes.getFirst().get("removedIds")),
                        "Ответ 401 должен скрывать фактическое удаление цели");
                assertEquals(200, deletes.getLast().get("status").intValue());
                assertFalse(deletes.getFirst().get("targetId").equals(deletes.getLast().get("targetId")),
                        "Teardown должен удалить именно сохранившегося соседа");
            }
            case DELETE_REMOVES_NEIGHBOR -> {
                assertEquals(2, ownedIds.size());
                assertEquals(1, deletes.size(), "После ошибочного удаления обеих записей лишние DELETE не нужны");
                assertEquals(200, deletes.getFirst().get("status").intValue());
                assertEquals(ownedIds, new HashSet<>(strings(deletes.getFirst().get("removedIds"))));
            }
            case CREATE_DUPLICATES_RECORD -> {
                assertEquals(2, count(http, "CREATE"), "Дефект создаёт лишнюю запись одним HTTP, без третьего POST");
                assertEquals(201, secondCreation.get("status").intValue());
                assertEquals(1, creations.getFirst().get("persistedIds").size(),
                        "Подготовка соседа должна быть штатной");
                assertEquals(2, secondCreation.get("persistedIds").size(), "Второй CREATE должен сохранить две записи");
                assertEquals(3, ownedIds.size(), "Две команды CREATE должны реально создать три собственных ID");
                assertEquals(3, deletes.size(), "После failed Expected очищаются сосед и обе записи второго CREATE");
                for (var event : deletes) {
                    assertEquals(200, event.get("status").intValue());
                    assertEquals(List.of(event.get("targetId").stringValue()), strings(event.get("removedIds")));
                }
            }
            case UNAUTHORIZED_LEAKS_IDENTIFIER -> {
                var rejectedReads = http.stream().filter(event -> "GET_ALL".equals(event.get("operation").stringValue())
                        && event.get("status").intValue() == 401).toList();
                assertEquals(1, rejectedReads.size(), "Каждый случай проверяет один отказ GET_ALL");
                assertTrue(ownedIds.contains(rejectedReads.getFirst().get("exposedId").stringValue()),
                        "Отказ должен фактически раскрыть ID одного из собственных игроков");
                assertEquals(2, ownedIds.size());
                assertEquals(2, deletes.size(), "После обнаружения утечки teardown очищает обоих сохранённых игроков");
                for (var event : deletes) {
                    assertEquals(200, event.get("status").intValue());
                    assertEquals(List.of(event.get("targetId").stringValue()), strings(event.get("removedIds")));
                }
            }
            case DELETE_OLDEST -> {
                assertEquals(2, ownedIds.size());
                assertEquals(2, deletes.size(), "Первое ошибочное удаление и адресная очистка оставшейся цели");
                assertFalse(deletes.getFirst().get("targetId").stringValue()
                        .equals(strings(deletes.getFirst().get("removedIds")).getFirst()));
            }
            case PROFILE_IGNORES_EMAIL -> {
                var reads = http.stream().filter(event -> "GET_ONE".equals(event.get("operation").stringValue()))
                        .toList();
                assertEquals(1, reads.size(), "Первый поиск второй записи уже обнаруживает игнорирование email");
                assertEquals(creations.getFirst().get("id").stringValue(),
                        reads.getFirst().get("targetId").stringValue());
            }
            case NOMINAL, CORRECTED_API -> {
                for (var event : deletes) {
                    int status = event.get("status").intValue();
                    assertEquals(status == 200 ? List.of(event.get("targetId").stringValue()) : List.of(),
                            strings(event.get("removedIds")),
                            "HTTP-отказ не меняет состояние, успешный DELETE удаляет только цель");
                }
                if (List.of("rejectsInvalidPlayer", "rejectsMatchingShortPasswords", "rejectsUnauthorizedCreation")
                        .contains(methodName)) {
                    assertEquals("rejectsUnauthorizedCreation".equals(methodName) ? 401 : 400,
                            secondCreation.get("status").intValue());
                    assertEquals("", secondCreation.get("id").stringValue());
                    assertEquals(1, ownedIds.size());
                } else {
                    assertEquals(2, ownedIds.size());
                }
                if ("rejectsUnauthorizedDeletion".equals(methodName)) {
                    assertEquals(401, deletes.getFirst().get("status").intValue());
                    assertEquals(3, deletes.size(), "Отказ сохраняет обоих игроков до двух отдельных cleanup DELETE");
                }
            }
        }
    }

    private static int count(List<JsonNode> http, String operation) {
        return (int) http.stream().filter(event -> operation.equals(event.get("operation").stringValue())).count();
    }

    private static boolean hasFailedStep(JsonNode steps) {
        if (steps == null)
            return false;
        for (var step : steps) {
            if (step.has("status") && "failed".equals(step.get("status").stringValue())
                    || hasFailedStep(step.get("steps")))
                return true;
        }
        return false;
    }

    private static List<String> strings(JsonNode array) {
        return elements(array).stream().map(JsonNode::stringValue).toList();
    }

    private static List<JsonNode> elements(JsonNode array) {
        var result = new ArrayList<JsonNode>();
        array.forEach(result::add);
        return List.copyOf(result);
    }

    private static Map.Entry<String, Integer> method(String className, String methodName, int count) {
        return Map.entry(className + "." + methodName, count);
    }

    private record EndpointCase(String name, FixtureCase fixture, Map<String, Integer> methods, String expectedMethod) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record NativeRun(JsonNode evidence, List<JsonNode> results) {
    }
}
