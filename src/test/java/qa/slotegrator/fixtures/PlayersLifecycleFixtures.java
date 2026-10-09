package qa.slotegrator.fixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.LoginRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.api.model.PlayerLookupRequest;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

/** Только локальные варианты одного производственного E2E, без произвольного выбора класса. */
public final class PlayersLifecycleFixtures {
    public static final String PRODUCTION_CLASS = "qa.slotegrator.tests.e2e.players.PlayersLifecycleE2EIT";
    public static final List<String> OVERRIDING_ENVIRONMENT = List.of("BASE_URL", "CONNECT_TIMEOUT_MS",
            "READ_TIMEOUT_MS", "BASIC_AUTH_USERNAME", "BASIC_AUTH_PASSWORD", "TESTER_EMAIL", "TESTER_PASSWORD",
            "CURRENCY_CODE", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
    private static final int PLAYER_COUNT = 12;
    private static final String FOREIGN_ID = "ffffffffffffffffffffffff";
    private static final String BASIC_USERNAME = "local-e2e-user";
    private static final String BASIC_PASSWORD = "dummy-e2e-basic-password";
    private static final String TESTER_EMAIL = "local-e2e@example.test";
    private static final String TESTER_PASSWORD = "dummy-e2e-tester-password";
    private static final String ACCESS_TOKEN = "dummy-e2e-access-token";
    private static final JsonCodec CODEC = new JsonCodec();

    public enum FixtureCase {
        NOMINAL, FOREIGN_INITIAL, CREATE_REJECTED_BUT_PERSISTED, LAST_PROFILE_MISMATCH, LIST_MISMATCH, CLEANUP_FAIL_FIRST, DELETE_REMOVES_ALL, ASSERTION_AND_CLEANUP_FAIL, MISSING_CONTEXT, LOGIN_201, PROFILE_201, PROFILE_IGNORES_EMAIL, DELETE_OLDEST, DELETE_WRONG_ID
    }

    private PlayersLifecycleFixtures() {
    }

    /** Отдельная JVM изолирует намеренные failures и Allure; до discovery разрешён только loopback. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Требуются локальный вариант фикстуры и каталог результатов");
        FixtureCase fixture;
        try {
            fixture = FixtureCase.valueOf(args[0]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неизвестный вариант локальной E2E-фикстуры");
        }
        if (OVERRIDING_ENVIRONMENT.stream().anyMatch(System.getenv()::containsKey))
            throw new IllegalStateException("Окружение дочернего процесса не изолировано");
        Path directory = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(directory);
        System.setProperty("allure.results.directory", directory.resolve("allure-results").toString());
        System.setProperty("xfail.enabled", "false");
        System.setProperty("e2e.authorized", fixture == FixtureCase.MISSING_CONTEXT ? "false" : "true");

        try (var api = new FakeApi(fixture)) {
            Path configuration = directory.resolve("config.properties");
            Files.writeString(configuration, "BASE_URL=" + api.server.uri() + "\n"
                    + "CONNECT_TIMEOUT_MS=2000\nREAD_TIMEOUT_MS=2000\n"
                    + "BASIC_AUTH_USERNAME=" + BASIC_USERNAME + "\nBASIC_AUTH_PASSWORD=" + BASIC_PASSWORD + "\n"
                    + "TESTER_EMAIL=" + TESTER_EMAIL + "\nTESTER_PASSWORD=" + TESTER_PASSWORD + "\n"
                    + "CURRENCY_CODE=USD\n", StandardCharsets.UTF_8);
            System.setProperty("api.config.file", configuration.toString());
            if (!ProjectSettings.load().api().baseUri().equals(api.server.uri()))
                throw new IllegalStateException("Производственный E2E разрешён только на точном адресе ответчика");

            var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(PRODUCTION_CLASS))
                    .filters(includeEngines("junit-jupiter"))
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build();
            var launcher = LauncherFactory.create();
            var plan = launcher.discover(request);
            long discovered = plan.countTestIdentifiers(TestIdentifier::isTest);
            if (discovered != 1)
                throw new AssertionError("Нативная фикстура должна обнаружить ровно один производственный E2E");
            var outcome = new NativeOutcome();
            launcher.execute(plan, outcome);
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("fixtureCase", fixture.name());
            evidence.put("discoveredTests", discovered);
            evidence.put("finishedTests", outcome.finishedTests);
            evidence.put("productionClass", outcome.productionClass);
            evidence.put("productionMethod", outcome.productionMethod);
            evidence.put("junitStatus", outcome.junitStatus);
            evidence.put("errorType", outcome.errorType);
            evidence.put("primaryAssertion", outcome.primaryAssertion);
            evidence.put("suppressedTypes", outcome.suppressedTypes);
            evidence.put("loopbackConfirmed", true);
            evidence.put("authorized", System.getProperty("e2e.authorized"));
            api.addEvidence(evidence);
            Files.writeString(directory.resolve("evidence.json"), CODEC.text(evidence), StandardCharsets.UTF_8);
            if (outcome.finishedTests != 1 || !PRODUCTION_CLASS.equals(outcome.productionClass))
                throw new AssertionError("Нативная фикстура должна завершить только разрешённый производственный E2E");
        }
    }

    private static final class NativeOutcome implements TestExecutionListener {
        private int finishedTests;
        private String productionClass = "NONE";
        private String productionMethod = "NONE";
        private String junitStatus = "NONE";
        private String errorType = "NONE";
        private boolean primaryAssertion;
        private List<String> suppressedTypes = List.of();

        @Override
        public void executionFinished(TestIdentifier test, TestExecutionResult result) {
            if (!test.isTest())
                return;
            finishedTests++;
            if (test.getSource().orElse(null) instanceof MethodSource method) {
                productionClass = method.getClassName();
                productionMethod = method.getMethodName();
            }
            junitStatus = result.getStatus().name();
            var error = result.getThrowable().orElse(null);
            if (error != null) {
                errorType = error.getClass().getSimpleName();
                primaryAssertion = error instanceof AssertionError;
                suppressedTypes = Arrays.stream(error.getSuppressed()).map(value -> value.getClass().getSimpleName())
                        .toList();
            }
        }
    }

    /** Ответы строятся из независимо декодированных HTTP-запросов, а не из объектов тестового контекста. */
    private static final class FakeApi implements AutoCloseable {
        private final LocalServer server = new LocalServer();
        private final FixtureCase fixture;
        private final Map<String, CreatePlayerRequest> requests = new LinkedHashMap<>();
        private final Map<String, ObservedPlayerResponse> players = new LinkedHashMap<>();
        private final List<String> profileIds = new ArrayList<>();
        private final List<String> deleteAttempts = new ArrayList<>();
        private final List<String> deletedIds = new ArrayList<>();
        private final List<String> sequence = new ArrayList<>();
        private final List<String> violations = new ArrayList<>();
        private final ObservedPlayerResponse foreign = new ObservedPlayerResponse(FOREIGN_ID, "foreign-player",
                "foreign@example.test", "Чужой", "Игрок", "EUR");
        private int loginCalls;
        private int listCalls;
        private int createCalls;
        private int profileCalls;
        private int deleteCalls;
        private boolean profileMutationApplied;
        private boolean listMutationApplied;
        private boolean bulkDeleteApplied;

        private FakeApi(FixtureCase fixture) {
            this.fixture = fixture;
            if (fixture == FixtureCase.FOREIGN_INITIAL)
                players.put(FOREIGN_ID, foreign);
            server.on(ApiRoutes.LOGIN, guarded("POST", false, this::login));
            server.on(ApiRoutes.GET_PLAYERS, guarded("GET", true, this::list));
            server.on(ApiRoutes.CREATE_PLAYER, guarded("POST", true, this::create));
            server.on(ApiRoutes.GET_PLAYER, guarded("POST", true, this::profile));
            server.on(ApiRoutes.DELETE_PLAYER.substring(0, ApiRoutes.DELETE_PLAYER.indexOf('{')),
                    guarded("DELETE", true, this::delete));
            server.on("/", exchange -> {
                sequence.add("UNKNOWN");
                violations.add("Запрошен неизвестный маршрут");
                send(exchange, 404, "{}");
            });
        }

        private HttpHandler guarded(String method, boolean bearerRequired, HttpHandler handler) {
            return exchange -> {
                if (!method.equals(exchange.getRequestMethod())) {
                    sequence.add("WRONG_METHOD");
                    violations.add("HTTP-метод не соответствует маршруту");
                    send(exchange, 405, "{}");
                    return;
                }
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String expected = bearerRequired
                        ? "Bearer " + ACCESS_TOKEN
                        : "Basic " + Base64.getEncoder()
                                .encodeToString(
                                        (BASIC_USERNAME + ":" + BASIC_PASSWORD).getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(authorization)) {
                    sequence.add("WRONG_AUTHORIZATION");
                    violations.add("Авторизация не соответствует локальной фикстуре");
                    send(exchange, 401, "{}");
                    return;
                }
                handler.handle(exchange);
            };
        }

        private void login(HttpExchange exchange) throws IOException {
            loginCalls++;
            sequence.add("LOGIN");
            var request = CODEC.decode(exchange.getRequestBody().readAllBytes(), LoginRequest.class);
            if (!TESTER_EMAIL.equals(request.email()) || !TESTER_PASSWORD.equals(request.password())) {
                violations.add("Вход должен использовать только локальные реквизиты");
                send(exchange, 401, "{}");
                return;
            }
            send(exchange, fixture == FixtureCase.LOGIN_201 ? 201 : 200,
                    CODEC.text(Map.of("access_token", ACCESS_TOKEN)));
        }

        private void create(HttpExchange exchange) throws IOException {
            createCalls++;
            String id = Integer.toString(createCalls);
            sequence.add("CREATE:" + id);
            var request = CODEC.decode(exchange.getRequestBody().readAllBytes(), CreatePlayerRequest.class);
            if (!request.passwordChange().equals(request.passwordRepeat()))
                violations.add("Пароли создания должны совпадать");
            if (!"USD".equals(request.currencyCode()))
                violations.add("Валюта создания должна приходить из локальной конфигурации");
            if (requests.values().stream().anyMatch(previous -> previous.email().equals(request.email())
                    || previous.username().equals(request.username())))
                violations.add("Создание повторно использовало email или username");
            requests.put(id, request);
            players.put(id, new ObservedPlayerResponse(id, request.username(), request.email(), request.name(),
                    request.surname(), request.currencyCode()));
            if (fixture == FixtureCase.CREATE_REJECTED_BUT_PERSISTED && createCalls == 6) {
                send(exchange, 400, "{}");
                return;
            }
            send(exchange, 201, CODEC.text(wire(players.get(id))));
        }

        private void profile(HttpExchange exchange) throws IOException {
            profileCalls++;
            var lookup = CODEC.decode(exchange.getRequestBody().readAllBytes(), PlayerLookupRequest.class);
            var player = players.values().stream()
                    .filter(value -> fixture == FixtureCase.PROFILE_IGNORES_EMAIL
                            || value.email().equals(lookup.email()))
                    .findFirst();
            if (player.isEmpty()) {
                sequence.add("UNKNOWN_PROFILE");
                violations.add("Запрошен профиль неизвестного игрока");
                send(exchange, 404, "{}");
                return;
            }
            var response = player.get();
            profileIds.add(response.id());
            sequence.add("PROFILE:" + response.id());
            if (profileCalls == PLAYER_COUNT && (fixture == FixtureCase.LAST_PROFILE_MISMATCH
                    || fixture == FixtureCase.ASSERTION_AND_CLEANUP_FAIL)) {
                response = withWrongName(response);
                profileMutationApplied = true;
            }
            send(exchange, fixture == FixtureCase.PROFILE_201 ? 201 : 200, CODEC.text(wire(response)));
        }

        private void list(HttpExchange exchange) throws IOException {
            listCalls++;
            sequence.add("GET_ALL");
            var response = new ArrayList<>(players.values());
            if (fixture == FixtureCase.LIST_MISMATCH && profileCalls == PLAYER_COUNT && !listMutationApplied) {
                response.set(0, withWrongName(response.getFirst()));
                listMutationApplied = true;
            }
            send(exchange, 200, CODEC.text(response.stream().map(FakeApi::wire).toList()));
        }

        private void delete(HttpExchange exchange) throws IOException {
            deleteCalls++;
            String id = exchange.getRequestURI().getPath().substring(ApiRoutes.DELETE_PLAYER.indexOf('{'));
            sequence.add("DELETE:" + id);
            deleteAttempts.add(id);
            if (!requests.containsKey(id))
                violations.add("DELETE направлен на ID без собственного POST");
            if (deleteCalls == 1 && (fixture == FixtureCase.CLEANUP_FAIL_FIRST
                    || fixture == FixtureCase.ASSERTION_AND_CLEANUP_FAIL)) {
                send(exchange, 500, "{}");
                return;
            }
            if (deleteCalls == 1 && fixture == FixtureCase.DELETE_REMOVES_ALL) {
                // Фиксируем все реально исчезнувшие записи отдельно от единственной попытки DELETE.
                deletedIds.addAll(players.keySet());
                players.clear();
                bulkDeleteApplied = true;
                send(exchange, 200, CODEC.text(wire(new ObservedPlayerResponse(id, requests.get(id).username(),
                        requests.get(id).email(), requests.get(id).name(), requests.get(id).surname(), "USD"))));
                return;
            }
            String actualId = deleteCalls == 1 && fixture == FixtureCase.DELETE_OLDEST
                    ? players.keySet().iterator().next()
                    : id;
            var removed = players.remove(actualId);
            if (removed != null)
                deletedIds.add(actualId);
            var response = wire(removed);
            if (deleteCalls == 1 && fixture == FixtureCase.DELETE_WRONG_ID)
                response.put("id", -100);
            send(exchange, 200, CODEC.text(response));
        }

        private void addEvidence(Map<String, Object> evidence) {
            evidence.put("httpCalls", sequence.size());
            evidence.put("loginCalls", loginCalls);
            evidence.put("listCalls", listCalls);
            evidence.put("createCalls", createCalls);
            evidence.put("profileCalls", profileCalls);
            evidence.put("deleteCalls", deleteCalls);
            evidence.put("createdIds", List.copyOf(requests.keySet()));
            evidence.put("profileIds", profileIds);
            evidence.put("deleteAttempts", deleteAttempts);
            evidence.put("deletedIds", deletedIds);
            evidence.put("remainingIds", List.copyOf(players.keySet()));
            evidence.put("foreignIntact", foreign.equals(players.get(FOREIGN_ID)));
            var names = requests.values().stream().map(CreatePlayerRequest::name).toList();
            evidence.put("insertionOrderUnsorted", !names.equals(names.stream().sorted().toList()));
            evidence.put("profileMutationApplied", profileMutationApplied);
            evidence.put("listMutationApplied", listMutationApplied);
            evidence.put("bulkDeleteApplied", bulkDeleteApplied);
            evidence.put("requestSequence", sequence);
            evidence.put("violations", violations);
        }

        private static Map<String, Object> wire(ObservedPlayerResponse player) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("id", FOREIGN_ID.equals(player.id()) ? FOREIGN_ID : Integer.valueOf(player.id()));
            fields.put("username", player.username());
            fields.put("email", player.email());
            fields.put("name", player.name());
            fields.put("surname", player.surname());
            fields.put("currency_code", player.currencyCode());
            return fields;
        }

        private static ObservedPlayerResponse withWrongName(ObservedPlayerResponse player) {
            return new ObservedPlayerResponse(player.id(), player.username(), player.email(),
                    player.name() + "-искажено", player.surname(), player.currencyCode());
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
