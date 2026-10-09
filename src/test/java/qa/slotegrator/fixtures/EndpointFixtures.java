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
import java.util.regex.Pattern;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.jackson.databind.JsonNode;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.ObservedCreatedPlayerResponse;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.InvalidJsonField;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

/** Ограниченные локальные режимы настоящих endpoint IT, без внешнего выбора класса или метода. */
public final class EndpointFixtures {
    public static final String FOREIGN_ID = "ffffffffffffffffffffffff";
    public static final String CREATION_CLASS = "qa.slotegrator.tests.integration.players.PlayerCreationIT";
    public static final String PROFILE_CLASS = "qa.slotegrator.tests.integration.players.PlayerProfileIT";
    public static final String LIST_CLASS = "qa.slotegrator.tests.integration.players.PlayersListIT";
    public static final String DELETION_CLASS = "qa.slotegrator.tests.integration.players.PlayerDeletionIT";
    public static final String LOGIN_CLASS = "qa.slotegrator.tests.integration.auth.TesterLoginIT";
    public static final List<String> PRODUCTION_CLASSES = List.of(CREATION_CLASS, PROFILE_CLASS, LIST_CLASS,
            DELETION_CLASS, LOGIN_CLASS);
    public static final List<String> OVERRIDING_ENVIRONMENT = List.of("BASE_URL", "CONNECT_TIMEOUT_MS",
            "READ_TIMEOUT_MS", "BASIC_AUTH_USERNAME", "BASIC_AUTH_PASSWORD", "TESTER_EMAIL", "TESTER_PASSWORD",
            "CURRENCY_CODE", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
    private static final String BASIC_USERNAME = "local-endpoints-user";
    private static final String BASIC_PASSWORD = "dummy-endpoints-basic-password";
    private static final String TESTER_EMAIL = "local-endpoints@example.test";
    private static final String TESTER_PASSWORD = "dummy-endpoints-tester-password";
    private static final String ACCESS_TOKEN = "dummy-endpoints-access-token";
    private static final JsonCodec CODEC = new JsonCodec();

    public enum FixtureCase {
        NOMINAL, CORRECTED_API, DELETE_OLDEST, PROFILE_IGNORES_EMAIL, CREATE_ERROR_PERSISTS, DELETE_UNAUTHORIZED_MUTATES, DELETE_REMOVES_NEIGHBOR, CREATE_DUPLICATES_RECORD, UNAUTHORIZED_LEAKS_IDENTIFIER
    }

    private EndpointFixtures() {
    }

    /** До discovery изолирует реквизиты и проверяет точное совпадение адреса с собственным loopback. */
    public static void main(String[] args) throws Exception {
        // Управляемый ответчик проверяет сами оракулы; известные дефекты удалённого стенда здесь не применяются.
        System.setProperty("xfail.enabled", "false");
        if (args.length != 2)
            throw new IllegalArgumentException("Требуются вариант локальной фикстуры и каталог результатов");
        FixtureCase fixture;
        try {
            fixture = FixtureCase.valueOf(args[0]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неизвестный вариант локальной endpoint-фикстуры");
        }
        if (OVERRIDING_ENVIRONMENT.stream().anyMatch(System.getenv()::containsKey))
            throw new IllegalStateException("Окружение дочернего процесса не изолировано");
        Path directory = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(directory);
        System.setProperty("allure.results.directory", directory.resolve("allure-results").toString());
        System.setProperty("e2e.authorized", "false");
        try (var api = new FakeApi(fixture)) {
            Path configuration = directory.resolve("config.properties");
            Files.writeString(configuration, "BASE_URL=" + api.server.uri() + "\n"
                    + "CONNECT_TIMEOUT_MS=2000\nREAD_TIMEOUT_MS=2000\n"
                    + "BASIC_AUTH_USERNAME=" + BASIC_USERNAME + "\nBASIC_AUTH_PASSWORD=" + BASIC_PASSWORD + "\n"
                    + "TESTER_EMAIL=" + TESTER_EMAIL + "\nTESTER_PASSWORD=" + TESTER_PASSWORD + "\n"
                    + "CURRENCY_CODE=USD\n", StandardCharsets.UTF_8);
            System.setProperty("api.config.file", configuration.toString());
            if (!ProjectSettings.load().api().baseUri().equals(api.server.uri()))
                throw new IllegalStateException("Endpoint IT разрешены только на точном адресе локального ответчика");

            var request = LauncherDiscoveryRequestBuilder.request().selectors(selectors(fixture))
                    .filters(includeEngines("junit-jupiter"))
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                    .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build();
            var launcher = LauncherFactory.create();
            var outcome = new NativeOutcome(api);
            launcher.execute(request, outcome);
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("fixtureCase", fixture.name());
            evidence.put("loopbackConfirmed", true);
            evidence.put("finishedTests", outcome.invocations.size());
            evidence.put("invocations", outcome.invocations);
            evidence.put("containerFailures", outcome.containerFailures);
            evidence.put("remainingIds", api.ids());
            evidence.put("foreignIntact", api.foreignIntact());
            evidence.put("violations", api.violations);
            Files.writeString(directory.resolve("evidence.json"), CODEC.text(evidence), StandardCharsets.UTF_8);
            if (outcome.invocations.isEmpty())
                throw new AssertionError("Нативная фикстура не выполнила ни одного конкретного endpoint-случая");
        }
    }

    private static List<DiscoverySelector> selectors(FixtureCase fixture) throws ReflectiveOperationException {
        return switch (fixture) {
            case NOMINAL, CORRECTED_API ->
                PRODUCTION_CLASSES.stream().<DiscoverySelector>map(name -> selectClass(name)).toList();
            case CREATE_ERROR_PERSISTS -> List.of(selectMethod(Class.forName(CREATION_CLASS),
                    Class.forName(CREATION_CLASS).getDeclaredMethod("rejectsInvalidPlayer", InvalidJsonField.class)));
            case DELETE_UNAUTHORIZED_MUTATES -> List.of(selectMethod(Class.forName(DELETION_CLASS),
                    Class.forName(DELETION_CLASS).getDeclaredMethod("rejectsUnauthorizedDeletion",
                            AuthorizationMode.class)));
            case DELETE_REMOVES_NEIGHBOR, DELETE_OLDEST -> List.of(selectMethod(Class.forName(DELETION_CLASS),
                    Class.forName(DELETION_CLASS).getDeclaredMethod("deletesOnlyOwnTarget")));
            case PROFILE_IGNORES_EMAIL -> List.of(selectMethod(Class.forName(PROFILE_CLASS),
                    Class.forName(PROFILE_CLASS).getDeclaredMethod("profileMatchesOwnPlayer")));
            case CREATE_DUPLICATES_RECORD -> List.of(selectMethod(Class.forName(CREATION_CLASS),
                    Class.forName(CREATION_CLASS).getDeclaredMethod("createsPlayerWithoutChangingNeighbour")));
            case UNAUTHORIZED_LEAKS_IDENTIFIER -> List.of(selectMethod(Class.forName(LIST_CLASS),
                    Class.forName(LIST_CLASS).getDeclaredMethod("rejectsUnauthorizedList", AuthorizationMode.class)));
        };
    }

    private record HttpEvent(String operation, int status, String targetId, List<String> removedIds,
            String exposedId) {
    }

    private record CreateEvidence(String id, List<String> persistedIds, int status, String emailMarker,
            String usernameMarker,
            List<String> runMarkers) {
    }

    private record InvocationEvidence(String uniqueId, String productionClass, String productionMethod,
            String junitStatus, String errorType, boolean primaryAssertion, List<String> expectedFrames,
            List<String> suppressedTypes, List<String> beforeIds, List<String> afterIds, boolean foreignIntact,
            List<CreateEvidence> creations, List<HttpEvent> http) {
    }

    private static final class NativeOutcome implements TestExecutionListener {
        private final FakeApi api;
        private final List<InvocationEvidence> invocations = new ArrayList<>();
        private final List<String> containerFailures = new ArrayList<>();

        private NativeOutcome(FakeApi api) {
            this.api = api;
        }

        @Override
        public void executionStarted(TestIdentifier test) {
            if (test.isTest())
                api.beginInvocation();
        }

        @Override
        public void executionFinished(TestIdentifier test, TestExecutionResult result) {
            if (!test.isTest()) {
                if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL)
                    containerFailures.add(test.getUniqueId());
                return;
            }
            String className = "NONE";
            String methodName = "NONE";
            if (test.getSource().orElse(null) instanceof MethodSource method) {
                className = method.getClassName();
                methodName = method.getMethodName();
            }
            if (!PRODUCTION_CLASSES.contains(className))
                api.violations.add("Выполнен класс вне фиксированного списка endpoint IT");
            var error = result.getThrowable().orElse(null);
            var frames = error == null
                    ? List.<String>of()
                    : Arrays.stream(error.getStackTrace())
                            .filter(frame -> frame.getClassName().startsWith("qa.slotegrator.expecteds."))
                            .map(frame -> frame.getClassName() + "." + frame.getMethodName()).distinct().toList();
            var suppressed = error == null
                    ? List.<String>of()
                    : Arrays.stream(error.getSuppressed())
                            .map(value -> value.getClass().getSimpleName()).toList();
            invocations.add(api.finishInvocation(test.getUniqueId(), className, methodName, result.getStatus().name(),
                    error == null ? "NONE" : error.getClass().getSimpleName(), error instanceof AssertionError,
                    frames, suppressed));
        }
    }

    /** Состояние изменяют только фактические HTTP-команды; FOREIGN присутствует до каждого случая. */
    private static final class FakeApi implements AutoCloseable {
        private static final List<String> CREATE_FIELDS = List.of("currency_code", "email", "name",
                "password_change", "password_repeat", "surname", "username");
        private static final Pattern RUN_MARKER = Pattern.compile("[a-f0-9]{32}");
        private final LocalServer server = new LocalServer();
        private final FixtureCase fixture;
        private final Map<String, ObservedPlayerResponse> players = new LinkedHashMap<>();
        private final List<String> violations = new ArrayList<>();
        private final ObservedPlayerResponse foreign = new ObservedPlayerResponse(FOREIGN_ID, "foreign-player",
                "foreign@example.test", "Чужой", "Игрок", "EUR");
        private List<String> beforeIds;
        private List<CreateEvidence> creations;
        private List<HttpEvent> http;
        private int nextId;

        private FakeApi(FixtureCase fixture) {
            this.fixture = fixture;
            players.put(FOREIGN_ID, foreign);
            server.on(ApiRoutes.LOGIN, guarded("POST", this::login));
            server.on(ApiRoutes.CREATE_PLAYER, guarded("POST", this::create));
            server.on(ApiRoutes.GET_PLAYER, guarded("POST", this::profile));
            server.on(ApiRoutes.GET_PLAYERS, guarded("GET", this::list));
            server.on(ApiRoutes.DELETE_PLAYER.substring(0, ApiRoutes.DELETE_PLAYER.indexOf('{')),
                    guarded("DELETE", this::delete));
            server.on("/", guarded("UNKNOWN", exchange -> send(exchange, "UNKNOWN", 404, "{}", "", List.of())));
        }

        private synchronized void beginInvocation() {
            if (http != null)
                violations.add("Предыдущий конкретный случай не завершён");
            beforeIds = ids();
            creations = new ArrayList<>();
            http = new ArrayList<>();
        }

        private synchronized InvocationEvidence finishInvocation(String uniqueId, String className, String methodName,
                String status, String errorType, boolean primaryAssertion, List<String> expectedFrames,
                List<String> suppressed) {
            var evidence = new InvocationEvidence(uniqueId, className, methodName, status, errorType, primaryAssertion,
                    expectedFrames, suppressed, beforeIds, ids(), foreignIntact(), List.copyOf(creations),
                    List.copyOf(http));
            http = null;
            return evidence;
        }

        private synchronized List<String> ids() {
            return List.copyOf(players.keySet());
        }

        private synchronized boolean foreignIntact() {
            return foreign.equals(players.get(FOREIGN_ID));
        }

        private HttpHandler guarded(String method, HttpHandler handler) {
            return exchange -> {
                synchronized (this) {
                    if (http == null) {
                        violations.add("HTTP вне конкретного Jupiter-случая");
                        reply(exchange, 500, "{}");
                    } else if (!method.equals(exchange.getRequestMethod())) {
                        violations.add("HTTP-метод или маршрут вне контракта");
                        send(exchange, "UNKNOWN", 405, "{}", "", List.of());
                    } else {
                        handler.handle(exchange);
                    }
                }
            };
        }

        private static boolean authorized(HttpExchange exchange, boolean basic) {
            String expected = basic
                    ? "Basic " + Base64.getEncoder().encodeToString(
                            (BASIC_USERNAME + ":" + BASIC_PASSWORD).getBytes(StandardCharsets.UTF_8))
                    : "Bearer " + ACCESS_TOKEN;
            return expected.equals(exchange.getRequestHeaders().getFirst("Authorization"));
        }

        private void login(HttpExchange exchange) throws IOException {
            var body = CODEC.tree(exchange.getRequestBody().readAllBytes());
            int status;
            if (!authorized(exchange, true))
                status = 401;
            else if (!strings(body, List.of("email", "password")) || body.get("password").stringValue().length() < 4)
                status = 400;
            else if (!TESTER_EMAIL.equals(body.get("email").stringValue())
                    || !TESTER_PASSWORD.equals(body.get("password").stringValue()))
                status = 401;
            else
                status = fixture == FixtureCase.CORRECTED_API ? 200 : 201;
            send(exchange, "LOGIN", status, status == 200 || status == 201
                    ? CODEC.text(
                            Map.of(fixture == FixtureCase.CORRECTED_API ? "access_token" : "accessToken", ACCESS_TOKEN))
                    : "{}",
                    "", List.of());
        }

        private void create(HttpExchange exchange) throws IOException {
            var body = CODEC.tree(exchange.getRequestBody().readAllBytes());
            var changed = body.get("password_change");
            var repeated = body.get("password_repeat");
            boolean pairBoundary = changed != null && repeated != null
                    && (changed.isNumber() || repeated.isNumber()
                            || changed.isString() && changed.stringValue().length() < 4
                            || repeated.isString() && repeated.stringValue().length() < 4);
            if (pairBoundary && !changed.equals(repeated))
                violations.add("Проверка типа или длины паролей не должна подменяться несовпадением пары");
            boolean valid = strings(body, CREATE_FIELDS)
                    && List.of("username", "password_change", "password_repeat").stream()
                            .allMatch(field -> body.get(field).stringValue().length() >= 4);
            int status = !authorized(exchange, false) ? 401 : valid ? 201 : 400;
            String id = "";
            var persistedIds = new ArrayList<String>();
            ObservedPlayerResponse player = null;
            if (status == 201 || status == 400 && fixture == FixtureCase.CREATE_ERROR_PERSISTS) {
                id = fixture == FixtureCase.CORRECTED_API
                        ? Integer.toString(++nextId)
                        : String.format("%024x", ++nextId);
                // Только fake приводит неверные публичные поля к строкам для проверки побочного сохранения.
                // Сохранившийся исходный email/username остаётся точным признаком владения для teardown.
                player = new ObservedPlayerResponse(id, publicField(body, "username"), publicField(body, "email"),
                        publicField(body, "name"), publicField(body, "surname"), publicField(body, "currency_code"));
                players.put(id, player);
                persistedIds.add(id);
                if (status == 201 && fixture == FixtureCase.CREATE_DUPLICATES_RECORD && creations.size() == 1) {
                    String duplicateId = String.format("%024x", ++nextId);
                    players.put(duplicateId, new ObservedPlayerResponse(duplicateId, player.username(), player.email(),
                            player.name(), player.surname(), player.currencyCode()));
                    persistedIds.add(duplicateId);
                }
            }
            String email = markerField(body, "email");
            String username = markerField(body, "username");
            var markers = RUN_MARKER.matcher(email + " " + username).results().map(match -> match.group()).distinct()
                    .toList();
            creations.add(new CreateEvidence(id, List.copyOf(persistedIds), status, email, username, markers));
            String response = status == 201 ? CODEC.text(wire(player, true)) : "{}";
            send(exchange, "CREATE", status, response, id, List.of());
        }

        private void profile(HttpExchange exchange) throws IOException {
            var body = CODEC.tree(exchange.getRequestBody().readAllBytes());
            if (!authorized(exchange, false)) {
                send(exchange, "GET_ONE", 401, "{}", "", List.of());
                return;
            }
            if (!strings(body, List.of("email"))) {
                send(exchange, "GET_ONE", 400, "{}", "", List.of());
                return;
            }
            var player = players.values().stream()
                    .filter(value -> fixture == FixtureCase.PROFILE_IGNORES_EMAIL
                            ? !FOREIGN_ID.equals(value.id())
                            : value.email().equals(body.get("email").stringValue()))
                    .findFirst();
            if (player.isEmpty()) {
                violations.add("Профиль запрошен для неизвестного email");
                send(exchange, "GET_ONE", 404, "{}", "", List.of());
            } else {
                send(exchange, "GET_ONE", fixture == FixtureCase.CORRECTED_API ? 200 : 201,
                        CODEC.text(wire(player.get(), false)), player.get().id(), List.of());
            }
        }

        private void list(HttpExchange exchange) throws IOException {
            int status = authorized(exchange, false) ? 200 : 401;
            if (status == 401 && fixture == FixtureCase.UNAUTHORIZED_LEAKS_IDENTIFIER) {
                String exposedId = players.keySet().stream().filter(id -> !FOREIGN_ID.equals(id)).findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Для проверки утечки требуется собственный игрок"));
                http.add(new HttpEvent("GET_ALL", status, "", List.of(), exposedId));
                reply(exchange, status, CODEC.text(Map.of("id", exposedId)));
                return;
            }
            send(exchange, "GET_ALL", status,
                    status == 200
                            ? CODEC.text(players.values().stream().map(player -> wire(player, false)).toList())
                            : "{}",
                    "", List.of());
        }

        private void delete(HttpExchange exchange) throws IOException {
            String id = exchange.getRequestURI().getPath().substring(ApiRoutes.DELETE_PLAYER.indexOf('{'));
            if (FOREIGN_ID.equals(id) || creations.stream().noneMatch(created -> created.persistedIds().contains(id))) {
                violations.add("DELETE направлен на чужой или не созданный этим случаем ID");
                send(exchange, "DELETE", 403, "{}", id, List.of());
                return;
            }
            int status = authorized(exchange, false) ? 200 : 401;
            var removed = new ArrayList<String>();
            if (status == 200 || fixture == FixtureCase.DELETE_UNAUTHORIZED_MUTATES) {
                String actualId = fixture == FixtureCase.DELETE_OLDEST
                        && http.stream().noneMatch(event -> "DELETE".equals(event.operation()))
                                ? players.keySet().stream().filter(value -> !FOREIGN_ID.equals(value)).findFirst()
                                        .orElseThrow()
                                : id;
                if (players.remove(actualId) != null)
                    removed.add(actualId);
                if (status == 200 && fixture == FixtureCase.DELETE_REMOVES_NEIGHBOR) {
                    var neighbor = players.keySet().stream().filter(value -> !FOREIGN_ID.equals(value)).findFirst();
                    if (neighbor.isPresent()) {
                        players.remove(neighbor.get());
                        removed.add(neighbor.get());
                    }
                }
            }
            send(exchange, "DELETE", status, status == 200
                    ? CODEC.text(fixture == FixtureCase.CORRECTED_API
                            ? Map.of("id", Integer.valueOf(id))
                            : Map.of("_id", id))
                    : "{}", id,
                    List.copyOf(removed));
        }

        private Object wire(ObservedPlayerResponse player, boolean created) {
            if (fixture != FixtureCase.CORRECTED_API)
                return created
                        ? new ObservedCreatedPlayerResponse(player.id(), player.username(), player.email(),
                                player.name(), player.surname(), player.currencyCode())
                        : player;
            var fields = new LinkedHashMap<String, Object>();
            fields.put("id", FOREIGN_ID.equals(player.id()) ? FOREIGN_ID : Integer.valueOf(player.id()));
            fields.put("username", player.username());
            fields.put("email", player.email());
            fields.put("name", player.name());
            fields.put("surname", player.surname());
            fields.put("currency_code", player.currencyCode());
            return fields;
        }

        private static boolean strings(JsonNode body, List<String> fields) {
            return body.isObject() && fields.stream().allMatch(field -> body.has(field) && body.get(field).isString());
        }

        private static String publicField(JsonNode body, String field) {
            return body.has(field) && body.get(field).isString() ? body.get(field).stringValue() : "local-normalized";
        }

        private static String markerField(JsonNode body, String field) {
            String value = publicField(body, field);
            return RUN_MARKER.matcher(value).find() ? value : "";
        }

        private void send(HttpExchange exchange, String operation, int status, String body, String target,
                List<String> removed) throws IOException {
            http.add(new HttpEvent(operation, status, target, removed, ""));
            reply(exchange, status, body);
        }

        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
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
