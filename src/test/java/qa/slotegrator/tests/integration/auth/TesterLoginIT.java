package qa.slotegrator.tests.integration.auth;

import java.util.stream.Stream;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import qa.slotegrator.api.model.LoginRequest;
import qa.slotegrator.expecteds.AuthenticationExpected;
import qa.slotegrator.helpers.auth.ApiSession;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.InvalidJsonField;
import qa.slotegrator.helpers.data.InvalidJsonField.Violation;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.reporting.ExpectedFailure;
import qa.slotegrator.helpers.reporting.KnownFailure;

/**
 * Независимые проверки входа; создание игроков не требуется.
 * Негативные 400/401 — принятые правила проекта; источники указаны в api-spec.md.
 * Источник и границы ожиданий: docs/api-spec.md.
 */
@Epic("Players API")
@Feature("Вход тестера")
@Story("Токен выдаётся только при корректных реквизитах и теле")
@Tag("api")
@Tag("integration")
@Tag("regression")
@DisplayName("Независимые проверки входа тестера")
class TesterLoginIT {
    private ApiSession session;

    @BeforeEach
    void prepareIndependentSession() {
        var settings = ProjectSettings.load();
        session = new ApiSession(settings.api(), settings.auth());
    }

    /**
     * Design: позитивный класс и пригодность результата. Steps: войти, применить выданный токен к getAll.
     * Expected: пригодный токен и 200 на защищённом чтении; точный код входа проверяется контрактным тестом.
     */
    @Test
    @Tag("smoke")
    @DisplayName("Корректный вход выдаёт токен для чтения игроков")
    @Tag("AUTH-03")
    void issuedTokenGrantsPlayerAccess() {
        var response = Allure.step("Войти с корректным Basic и реквизитами тестера",
                () -> session.loginWithAuthorization(session.validLoginRequest(), AuthorizationMode.VALID));
        Allure.step("Получить непустой токен из успешного ответа входа", () -> session.authenticate(response));
        var players = Allure.step("Применить выданный токен к чтению списка игроков",
                () -> HttpResult.read(session.players().getAll()));
        Allure.step("Выданный токен разрешает защищённое чтение с HTTP 200",
                () -> AuthenticationExpected.verifyTokenGrantsPlayerAccess(players));
    }

    /**
     * Design: отсутствующий/невалидный Basic при корректном JSON. Steps: изменить только Basic, запросить вход.
     * Expected: точный 401 и отсутствие выданного токена; интерпретация docs/api-spec.md.
     */
    @ParameterizedTest(name = "Basic: {0}")
    @EnumSource(value = AuthorizationMode.class, names = {"MISSING", "INVALID"})
    @DisplayName("Отсутствующий или неверный Basic не выдаёт токен")
    @Tag("AUTH-04")
    @ExpectedFailure(bug = "BUG-006", failure = KnownFailure.BASIC_AUTH_IGNORED, caseId = {
            "MISSING", "INVALID"})
    void rejectsInvalidBasic(AuthorizationMode mode) {
        var response = Allure.step("Запросить вход с выбранным отрицательным Basic",
                () -> session.loginWithAuthorization(session.validLoginRequest(), mode));
        Allure.step("Вход отклонён с HTTP 401 без выдачи токена",
                () -> AuthenticationExpected.verifyRejectedBasic(response, session.codec()));
    }

    /**
     * Design: неверные реквизиты при валидном формате. Steps: изменить только JSON-пароль при корректном Basic.
     * Expected: точный 401 без токена; пароль удовлетворяет minLength, чтобы изолировать причину отказа.
     */
    @Test
    @DisplayName("Неверный пароль тестера не выдаёт токен при корректном Basic")
    @Tag("AUTH-04")
    void rejectsWrongTesterPassword() {
        var valid = session.validLoginRequest();
        var invalid = new LoginRequest(valid.email(), valid.password() + "-invalid-login-password");
        var response = Allure.step("Войти с корректным Basic и неверным JSON-паролем",
                () -> session.loginWithAuthorization(invalid, AuthorizationMode.VALID));
        Allure.step("Неверный пароль отклонён с HTTP 401 без выдачи токена",
                () -> AuthenticationExpected.verifyRejectedLogin(response, 401, session.codec()));
    }

    /**
     * Design: required, nullable, тип и граница minLength. Steps: изменить ровно одно поле корректного JSON.
     * Expected: документированный отказ 400/401 без токена; приоритет причин требует уточнения в BUG-007.
     */
    @ParameterizedTest(name = "CredentialsDTO: {0}")
    @MethodSource("invalidCredentials")
    @DisplayName("Нарушение схемы реквизитов не выдаёт токен")
    @Tag("AUTH-05")
    void rejectsInvalidCredentials(InvalidJsonField invalid) {
        var body = Allure.step("Подготовить одно нарушение опубликованной схемы реквизитов",
                () -> invalid
                        .apply((ObjectNode) session.codec().tree(session.codec().encode(session.validLoginRequest()))));
        var response = Allure.step("Запросить вход с корректным Basic и нарушенной схемой JSON",
                () -> session.loginWithAuthorization(body, AuthorizationMode.VALID));
        Allure.step("Некорректные реквизиты отклонены с HTTP 400 или 401 без выдачи токена",
                () -> AuthenticationExpected.verifyRejectedCredentials(response, session.codec()));
    }

    private static Stream<InvalidJsonField> invalidCredentials() {
        var structural = Stream.of("email", "password")
                .flatMap(field -> Stream.of(Violation.MISSING, Violation.NULL, Violation.NUMBER)
                        .map(violation -> new InvalidJsonField(field, violation)));
        return Stream.concat(structural, Stream.of(new InvalidJsonField("password", Violation.TOO_SHORT)));
    }
}
