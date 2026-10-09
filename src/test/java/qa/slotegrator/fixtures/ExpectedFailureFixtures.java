package qa.slotegrator.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.expecteds.AuthenticationExpected;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.data.InvalidJsonField;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.ExpectedFailure;
import qa.slotegrator.helpers.reporting.KnownFailure;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Фиксированный native proof без HTTP, пользовательских реквизитов и произвольных классов запуска. */
public final class ExpectedFailureFixtures {
    public static final int CASE_COUNT = 27;
    private static final String TOKEN = "{\"accessToken\":\"synthetic-issued-token\"}";
    private static final Set<String> CLEANED = ConcurrentHashMap.newKeySet();

    private ExpectedFailureFixtures() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0)
            throw new IllegalArgumentException("Фикстура XFAIL не принимает аргументы");
        var evidence = new ArrayList<Evidence>();
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(Examples.class),
                selectClass(Fields.class), selectClass(Unannotated.class), selectClass(Setup.class),
                selectClass(Cleanup.class))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build();
        LauncherFactory.create().execute(request, new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier test, TestExecutionResult result) {
                if (!test.isTest())
                    return;
                var source = (org.junit.platform.engine.support.descriptor.MethodSource) test.getSource().orElseThrow();
                String fixture = source.getClassName().substring(source.getClassName().lastIndexOf('$') + 1);
                evidence.add(new Evidence(test.getUniqueId(), fixture, test.getDisplayName(), result.getStatus().name(),
                        CLEANED.contains(fixture + ":" + test.getDisplayName())));
            }
        });
        Files.writeString(Path.of("evidence.json"), new JsonCodec().text(evidence));
        if (evidence.size() != CASE_COUNT)
            throw new AssertionError("Не все фиксированные случаи XFAIL были выполнены");
    }

    public record Evidence(String id, String fixture, String name, String status, boolean teardownSeen) {
    }

    public abstract static class Tracked {
        @AfterEach
        void recordCleanup(TestInfo info) {
            CLEANED.add(getClass().getSimpleName() + ":" + info.getDisplayName());
        }
    }

    public enum Example {
        KNOWN_STATUS, KNOWN_TOKEN, KNOWN_PLAYER_ID, KNOWN_PROFILE, KNOWN_LIST, KNOWN_BASIC, KNOWN_CREATE, XPASS, WRONG_STATUS, WRONG_REASON, RUNTIME, SUPPRESSED, UNSELECTED, TOKEN_EMPTY, TOKEN_ERROR, BASIC_WITHOUT_TOKEN, LOGIN_TOKEN_AT_401, LOGIN_HTML_AT_401, PLAYER_WITHOUT_CURRENCY, CREATE_CHANGES_BASELINE, CREATE_TWO_RECORDS
    }

    public static final class Examples extends Tracked {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Example.class)
        @ExpectedFailure(bug = "BUG-001", failure = KnownFailure.LOGIN_STATUS_201, caseId = {"KNOWN_STATUS", "XPASS",
                "WRONG_STATUS", "WRONG_REASON", "RUNTIME", "SUPPRESSED"})
        @ExpectedFailure(bug = "BUG-002", failure = KnownFailure.TOKEN_ACCESS_TOKEN_MISSING, caseId = {"KNOWN_TOKEN",
                "TOKEN_EMPTY", "TOKEN_ERROR"})
        @ExpectedFailure(bug = "BUG-003", failure = KnownFailure.PLAYER_ID_NOT_INTEGER, caseId = {"KNOWN_PLAYER_ID",
                "PLAYER_WITHOUT_CURRENCY"})
        @ExpectedFailure(bug = "BUG-004", failure = KnownFailure.PROFILE_STATUS_201, caseId = "KNOWN_PROFILE")
        @ExpectedFailure(bug = "BUG-005", failure = KnownFailure.LIST_ROOT_ARRAY, caseId = "KNOWN_LIST")
        @ExpectedFailure(bug = "BUG-006", failure = KnownFailure.BASIC_AUTH_IGNORED, caseId = {"KNOWN_BASIC",
                "BASIC_WITHOUT_TOKEN", "LOGIN_TOKEN_AT_401", "LOGIN_HTML_AT_401"})
        @ExpectedFailure(bug = "BUG-008", failure = KnownFailure.CREATE_INVALID_ACCEPTED, caseId = {"KNOWN_CREATE",
                "CREATE_CHANGES_BASELINE", "CREATE_TWO_RECORDS"})
        void run(Example example) {
            var codec = new JsonCodec();
            switch (example) {
                case KNOWN_STATUS -> Allure.step("Известный статус", ExpectedFailureFixtures::knownStatus);
                case KNOWN_TOKEN, WRONG_REASON ->
                    PublishedContractExpected.verifyTokenSchema(HttpResult.json(201, TOKEN), codec);
                case KNOWN_PLAYER_ID -> PublishedContractExpected.verifyPlayerSchema(player(true), codec);
                case KNOWN_PROFILE -> PublishedContractExpected.verifyProfileStatus(HttpResult.json(201, "{}"));
                case KNOWN_LIST -> PublishedContractExpected.verifyListSchema(HttpResult.json(200, "[]"), codec);
                case KNOWN_BASIC -> AuthenticationExpected.verifyRejectedBasic(HttpResult.json(201, TOKEN), codec);
                case KNOWN_CREATE -> rejectedCreation(1, false);
                case XPASS, UNSELECTED -> PublishedContractExpected.verifyLoginStatus(HttpResult.json(200, "{}"));
                case WRONG_STATUS -> PublishedContractExpected.verifyLoginStatus(HttpResult.json(500, "{}"));
                case RUNTIME -> throw new IllegalStateException("Искусственный сбой транспорта");
                case SUPPRESSED -> {
                    try {
                        knownStatus();
                    } catch (AssertionError error) {
                        error.addSuppressed(new AssertionError("Дополнительный сбой"));
                        throw error;
                    }
                }
                case TOKEN_EMPTY -> PublishedContractExpected.verifyTokenSchema(HttpResult.json(201, "{}"), codec);
                case TOKEN_ERROR ->
                    PublishedContractExpected.verifyTokenSchema(HttpResult.json(201, "{\"error\":\"failure\"}"), codec);
                case BASIC_WITHOUT_TOKEN ->
                    AuthenticationExpected.verifyRejectedBasic(HttpResult.json(201, "{}"), codec);
                case LOGIN_TOKEN_AT_401 ->
                    AuthenticationExpected.verifyRejectedCredentials(HttpResult.json(401, TOKEN), codec);
                case LOGIN_HTML_AT_401 -> AuthenticationExpected
                        .verifyRejectedCredentials(HttpResult.json(401, "<html>Ошибка</html>"), codec);
                case PLAYER_WITHOUT_CURRENCY -> PublishedContractExpected.verifyPlayerSchema(player(false), codec);
                case CREATE_CHANGES_BASELINE -> rejectedCreation(1, true);
                case CREATE_TWO_RECORDS -> rejectedCreation(2, false);
            }
        }
    }

    public static final class Fields extends Tracked {
        static Stream<InvalidJsonField> fields() {
            return Stream.of(new InvalidJsonField("email", InvalidJsonField.Violation.MISSING),
                    new InvalidJsonField("email", InvalidJsonField.Violation.NULL));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("fields")
        @ExpectedFailure(bug = "BUG-001", failure = KnownFailure.LOGIN_STATUS_201, caseId = "email:MISSING")
        void run(InvalidJsonField field) {
            if (field.violation() == InvalidJsonField.Violation.MISSING)
                knownStatus();
        }
    }

    public static final class Unannotated extends Tracked {
        @Test
        void run() {
            knownStatus();
        }
    }

    public static final class Setup extends Tracked {
        @BeforeEach
        void failPreparation() {
            throw new IllegalStateException("Искусственный сбой подготовки");
        }

        @Test
        @ExpectedFailure(bug = "BUG-001", failure = KnownFailure.LOGIN_STATUS_201)
        void run() {
            knownStatus();
        }
    }

    public static final class Cleanup extends Tracked {
        @RegisterExtension
        final AfterEachCallback cleanup = context -> {
            if (context.getRequiredTestMethod().getName().equals("callback"))
                throw new IllegalStateException("Искусственный сбой callback очистки");
        };

        @AfterEach
        void failCleanup(TestInfo info) {
            if (info.getTestMethod().orElseThrow().getName().equals("afterEach"))
                throw new AssertionError("Искусственный сбой @AfterEach очистки");
        }

        @Test
        @ExpectedFailure(bug = "BUG-001", failure = KnownFailure.LOGIN_STATUS_201)
        void afterEach() {
            knownStatus();
        }

        @Test
        @ExpectedFailure(bug = "BUG-001", failure = KnownFailure.LOGIN_STATUS_201)
        void callback() {
            knownStatus();
        }
    }

    private static void knownStatus() {
        PublishedContractExpected.verifyLoginStatus(HttpResult.json(201, "{}"));
    }

    private static HttpResult player(boolean currency) {
        return HttpResult.json(201, "{\"id\":\"0123456789abcdef01234567\",\"username\":\"fixture\","
                + "\"email\":\"fixture@example.test\",\"name\":\"Имя\",\"surname\":\"Фамилия\""
                + (currency ? ",\"currency_code\":\"USD\"}" : "}"));
    }

    private static void rejectedCreation(int count, boolean damageBaseline) {
        var original = new ObservedPlayerResponse("0123456789abcdef01234567", "control", "control@example.test", "Имя",
                "Фамилия", "USD");
        var request = new CreatePlayerRequest("USD", "created@example.test", "Имя", "abc", "abc", "Фамилия", "created");
        var after = new ArrayList<ObservedPlayerResponse>();
        after.add(damageBaseline
                ? original.withUsername("changed")
                : original);
        for (int index = 0; index < count; index++)
            after.add(new ObservedPlayerResponse("1123456789abcdef0123456" + index, request.username(), request.email(),
                    request.name(), request.surname(), "USD"));
        PlayersExpected.verifyRejectedCreate(HttpResult.json(201, "{}"), 400, request, List.of(original), after);
    }
}
