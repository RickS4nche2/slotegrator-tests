package qa.slotegrator.tests.self.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;

import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import qa.slotegrator.api.ApiRoutes;
import qa.slotegrator.api.model.LoginRequest;
import qa.slotegrator.expecteds.AuthenticationExpected;
import qa.slotegrator.fixtures.LocalServer;
import qa.slotegrator.helpers.auth.ApiSession;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.AuthSettings;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.KnownFailure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

@DisplayName("Явная отрицательная авторизация и её безопасный отчёт")
class NegativeAuthorizationTest {
    private static final String WORKING_TOKEN = "fixture-working-token";
    private static final String BASIC_USER = "fixture-basic-user";
    private static final String BASIC_PASSWORD = "fixture-basic-password";
    private static final String TESTER_PASSWORD = "fixture-tester-password";

    @TempDir
    Path directory;

    /** Design: обход автоподстановки. Steps: отрицательный запрос, затем контрольный. Expected: точные заголовки. */
    @ParameterizedTest(name = "Bearer: {0}")
    @EnumSource(value = AuthorizationMode.class, names = {"MISSING", "INVALID"})
    @DisplayName("Отрицательный Bearer не заменяется рабочим и не портит контрольный сеанс")
    void preservesNegativeBearerAndWorkingSession(AuthorizationMode mode) {
        var observed = new AtomicReference<List<String>>();
        var calls = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.GET_PLAYERS, exchange -> {
                calls.incrementAndGet();
                var authorization = exchange.getRequestHeaders().get("Authorization");
                observed.set(authorization == null ? List.of() : List.copyOf(authorization));
                send(exchange, 200, "[]");
            });
            var session = session(server);
            session.authenticate(HttpResult.json(201, "{\"accessToken\":\"" + WORKING_TOKEN + "\"}"));
            RestAssured.given().spec(session.requestWithAuthorization(mode)).get(ApiRoutes.GET_PLAYERS);
            if (mode == AuthorizationMode.MISSING) {
                assertTrue(observed.get().isEmpty(), "Отсутствующий заголовок нельзя восстанавливать");
            } else {
                assertEquals(1, observed.get().size(), "Должен уйти ровно один отрицательный заголовок");
                assertTrue(observed.get().getFirst().equals("Bearer invalid-slotegrator-test-bearer"),
                        "Должен уйти выбранный искусственный Bearer, без рабочего токена");
            }
            RestAssured.given().spec(session.requestWithAuthorization(AuthorizationMode.VALID))
                    .get(ApiRoutes.GET_PLAYERS);
            assertEquals(1, observed.get().size());
            assertTrue(observed.get().getFirst().equals("Bearer " + WORKING_TOKEN),
                    "Следующий контрольный запрос должен сохранить рабочий токен");
            assertEquals(2, calls.get(), "Авторизация не должна вызывать скрытых повторов");
        }
    }

    /** Design: независимые схемы. Steps: login с каждым режимом. Expected: точный Basic или его отсутствие. */
    @ParameterizedTest(name = "Basic: {0}")
    @EnumSource(AuthorizationMode.class)
    @DisplayName("Login явно передаёт выбранный Basic без подстановки Bearer")
    void sendsChosenBasic(AuthorizationMode mode) {
        var observed = new AtomicReference<List<String>>();
        var observedBody = new AtomicReference<LoginRequest>();
        var calls = new AtomicInteger();
        try (var server = new LocalServer()) {
            server.on(ApiRoutes.LOGIN, exchange -> {
                calls.incrementAndGet();
                var headers = exchange.getRequestHeaders().get("Authorization");
                observed.set(headers == null ? List.of() : List.copyOf(headers));
                observedBody.set(new JsonCodec().decode(exchange.getRequestBody().readAllBytes(), LoginRequest.class));
                send(exchange, 401, "{}");
            });
            var session = session(server);
            session.authenticate(HttpResult.json(201, "{\"accessToken\":\"" + WORKING_TOKEN + "\"}"));
            var response = session.loginWithAuthorization(session.validLoginRequest(), mode);
            assertEquals(401, response.status(), "Исходный отказ не преобразуется");
            assertEquals(1, calls.get(), "Preemptive Basic не должен создавать второй запрос");
            assertTrue(observedBody.get().password().equals(TESTER_PASSWORD), "Тело login не изменяется");
            if (mode == AuthorizationMode.MISSING) {
                assertTrue(observed.get().isEmpty(), "Без Basic не должен появляться другой Authorization");
            } else {
                assertEquals(1, observed.get().size());
                String header = observed.get().getFirst();
                assertTrue(header.startsWith("Basic "), "Login использует именно Basic");
                String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
                String expectedPassword = mode == AuthorizationMode.VALID
                        ? BASIC_PASSWORD
                        : "invalid-slotegrator-basic-password";
                assertTrue(decoded.equals(BASIC_USER + ":" + expectedPassword),
                        "Basic должен сохранить пользователя и использовать выбранный пароль");
            }
        }
    }

    /** Design: контрпримеры оракула. Steps: подменить статус или вернуть токен с отказом. Expected: FAIL. */
    @Test
    @DisplayName("Оракул отказа различает неправильный статус и скрытую выдачу токена")
    void rejectsWrongStatusAndIssuedToken() {
        var codec = new JsonCodec();
        AuthenticationExpected.verifyRejectedLogin(HttpResult.json(401, "{}"), 401, codec);
        AuthenticationExpected.verifyRejectedLogin(HttpResult.json(400, ""), 400, codec);
        assertThrows(AssertionError.class,
                () -> AuthenticationExpected.verifyRejectedLogin(HttpResult.json(403, "{}"), 401, codec));
        assertThrows(AssertionError.class, () -> AuthenticationExpected.verifyRejectedLogin(
                HttpResult.json(401, "{\"accessToken\":\"synthetic-issued-token\"}"), 401, codec));
        assertThrows(AssertionError.class, () -> AuthenticationExpected.verifyRejectedLogin(
                HttpResult.json(400, "{\"data\":[{\"access_token\":\"synthetic-issued-token\"}]}"), 400, codec));
    }

    /**
     * Design: атрибуция известного дефекта. Steps: выдать токен на неверный JSON-пароль и на неверный Basic.
     * Expected: BUG-006 узнаётся только в проверке Basic; выдача токена на неверный пароль остаётся новым сбоем.
     */
    @Test
    @DisplayName("Выдача токена на неверный пароль тестера не считается известным дефектом Basic")
    void attributesBasicBugOnlyToBasicRejection() {
        var codec = new JsonCodec();
        var issued = HttpResult.json(201, "{\"accessToken\":\"synthetic-issued-token\"}");
        var passwordError = assertThrows(AssertionError.class,
                () -> AuthenticationExpected.verifyRejectedLogin(issued, 401, codec));
        assertFalse(passwordError instanceof KnownFailure.Violation, "Обход пароля не должен выглядеть как BUG-006");
        var basicError = assertThrows(KnownFailure.Violation.class,
                () -> AuthenticationExpected.verifyRejectedBasic(issued, codec));
        assertEquals(KnownFailure.BASIC_AUTH_IGNORED, basicError.failure());
    }

    /**
     * Design: непрозрачный ответ. Steps: HTML или повреждённый JSON при верном коде отказа.
     * Expected: безопасная диагностическая ошибка, без заявления о нарушении схемы API и без исходного тела.
     */
    @Test
    @DisplayName("Непрозрачный ответ не даёт ложного подтверждения отсутствия токена")
    void failsClosedForOpaqueLoginRejection() {
        var codec = new JsonCodec();
        String secret = "synthetic-opaque-login-secret";
        for (String body : List.of("<html>" + secret + "</html>", "{\"accessToken\":\"" + secret)) {
            var error = assertThrows(IllegalStateException.class,
                    () -> AuthenticationExpected.verifyRejectedLogin(HttpResult.json(401, body), 401, codec));
            assertEquals("Не удалось проверить отсутствие токена в непрозрачном ответе входа", error.getMessage());
            assertTrue(error.getCause() == null, "Диагностика не должна сохранять исключение с исходным телом");
            assertFalse(error.toString().contains(secret), "Диагностика не должна раскрывать искусственный секрет");
        }
        assertThrows(AssertionError.class, () -> AuthenticationExpected.verifyRejectedLogin(
                HttpResult.json(403, "<html>" + secret + "</html>"), 401, codec),
                "Неожиданный HTTP-код должен быть виден до разбора тела");
    }

    /** Design: отражение секретов. Steps: локальный ответчик отражает их в нативный Allure. Expected: файлы безопасны. */
    @Test
    @DisplayName("Искусственные отрицательные секреты скрываются в реальных файлах Allure")
    void masksReflectedSecretsInNativeAllure() throws Exception {
        Path results = directory.resolve("allure-results");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dallure.results.directory=" + results, "--class-path", classpath,
                NegativeAuthorizationTest.class.getName()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("process.log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Локальная фикстура диагностики не завершилась за 30 секунд");
        }
        assertEquals(0, process.exitValue(), "Не удалось выполнить фиксированную локальную фикстуру");
        List<Path> files;
        try (var paths = Files.list(results)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, files.stream().filter(path -> path.toString().endsWith("-result.json")).count(),
                "Отдельный Allure должен содержать ровно один локальный тест");
        String encodedBasic = Base64.getEncoder().encodeToString(
                (BASIC_USER + ":invalid-slotegrator-basic-password").getBytes(StandardCharsets.UTF_8));
        boolean reflectionPresent = false;
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            reflectionPresent |= content.contains("reflection-control");
            for (String secret : List.of("invalid-slotegrator-", "synthetic-login-secret", BASIC_USER,
                    BASIC_PASSWORD, TESTER_PASSWORD, WORKING_TOKEN, encodedBasic)) {
                assertFalse(content.contains(secret), "Файл Allure раскрыл секрет из искусственного запроса");
            }
        }
        assertTrue(reflectionPresent, "Нужно проверить сохранённое отражение, а не пустой каталог");
    }

    /** Запускает только эту фиксированную локальную фикстуру, не принимает классы или URL извне. */
    public static void main(String[] arguments) {
        if (arguments.length != 0)
            throw new IllegalArgumentException("Локальная фикстура диагностики не принимает аргументы");
        var finished = new AtomicInteger();
        var failed = new AtomicInteger();
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(ReflectionScenario.class))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true").build();
        LauncherFactory.create().execute(request, new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier test, TestExecutionResult result) {
                if (test.isTest()) {
                    finished.incrementAndGet();
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL)
                        failed.incrementAndGet();
                }
            }
        });
        if (finished.get() != 1 || failed.get() != 0)
            throw new AssertionError("Фиксированная локальная проверка диагностики не завершилась успешно");
    }

    /** Вложенная фикстура выбирается только явным локальным launcher выше. */
    public static final class ReflectionScenario {
        @Test
        void reflectArtificialSecrets() {
            var codec = new JsonCodec();
            try (var server = new LocalServer()) {
                server.on(ApiRoutes.GET_PLAYERS, exchange -> {
                    String header = exchange.getRequestHeaders().getFirst("Authorization");
                    var body = codec.mapper().createObjectNode();
                    body.put("control", "reflection-control");
                    body.put("headerEcho", header);
                    body.put("rawTokenEcho", header.substring(7));
                    send(exchange, 401, codec.text(body));
                });
                server.on(ApiRoutes.LOGIN, exchange -> {
                    String header = exchange.getRequestHeaders().getFirst("Authorization");
                    String encoded = header.substring(6);
                    String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                    var request = codec.decode(exchange.getRequestBody().readAllBytes(), LoginRequest.class);
                    var body = codec.mapper().createObjectNode();
                    body.put("control", "reflection-control");
                    body.put("headerEcho", header);
                    body.put("encodedEcho", encoded);
                    body.put("decodedEcho", decoded);
                    body.put("passwordEcho", decoded.substring(decoded.indexOf(':') + 1));
                    body.put("requestEcho", request.password());
                    send(exchange, 401, codec.text(body));
                });
                var session = session(server);
                RestAssured.given().spec(session.requestWithAuthorization(AuthorizationMode.INVALID))
                        .get(ApiRoutes.GET_PLAYERS);
                session.loginWithAuthorization(new LoginRequest("fixture@example.test", "synthetic-login-secret"),
                        AuthorizationMode.INVALID);
            }
        }
    }

    private static ApiSession session(LocalServer server) {
        return new ApiSession(server.settings(),
                new AuthSettings(BASIC_USER, BASIC_PASSWORD, "fixture@example.test", TESTER_PASSWORD));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
