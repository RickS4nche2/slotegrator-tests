package qa.slotegrator.tests.self.expecteds;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import qa.slotegrator.expecteds.AuthenticationExpected;
import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Два независимых контракта входа")
class AuthenticationExpectedTest {
    private static final String OBSERVED_TOKEN = "{\"accessToken\":\"dummy-expected-token\"}";
    private static final String PUBLISHED_TOKEN = "{\"access_token\":\"dummy-expected-token\","
            + "\"token_type\":\"Bearer\",\"expires_in\":\"120\",\"scope\":\"players\"}";
    private static final List<LoginCase> LOGIN_CASES = List.of(
            new LoginCase("observed", 201, OBSERVED_TOKEN, true, false),
            new LoginCase("published", 200, PUBLISHED_TOKEN, false, true),
            new LoginCase("wrong-status-observed", 200, OBSERVED_TOKEN, false, false),
            new LoginCase("wrong-name-observed", 201, PUBLISHED_TOKEN, false, false),
            new LoginCase("accepted-is-not-created", 202, OBSERVED_TOKEN, false, false),
            new LoginCase("unauthorized", 401, OBSERVED_TOKEN, false, false),
            new LoginCase("numeric-token", 201, "{\"accessToken\":42}", false, false),
            new LoginCase("null-token", 201, "{\"accessToken\":null}", false, false),
            new LoginCase("blank-token", 201, "{\"accessToken\":\" \"}", false, false),
            new LoginCase("missing-token", 201, "{}", false, false));

    /**
     * Design: таблица решений — код входа × имя/тип токена.
     * Steps: проверить один исходный ответ обоими независимыми ожиданиями.
     * Expected: проходит только заранее объявленный контракт, а не любое успешное HTTP-значение.
     */
    @ParameterizedTest(name = "Контракт входа: {0}")
    @MethodSource("loginCases")
    void keepsObservedAndPublishedExpectationsIndependent(LoginCase testCase) {
        var response = HttpResult.json(testCase.status(), testCase.body());
        var codec = new JsonCodec();
        if (testCase.observed()) {
            assertDoesNotThrow(() -> AuthenticationExpected.verifyObservedLogin(response, codec));
        } else {
            assertThrows(AssertionError.class, () -> AuthenticationExpected.verifyObservedLogin(response, codec));
        }
        if (testCase.published()) {
            assertDoesNotThrow(() -> verifyPublished(response, codec));
        } else {
            assertThrows(AssertionError.class, () -> verifyPublished(response, codec));
        }
    }

    private void verifyPublished(HttpResult response, JsonCodec codec) {
        assertAll(() -> PublishedContractExpected.verifyLoginStatus(response),
                () -> PublishedContractExpected.verifyTokenSchema(response, codec));
    }

    private static Stream<LoginCase> loginCases() {
        return LOGIN_CASES.stream();
    }

    private record LoginCase(String id, int status, String body, boolean observed, boolean published) {
        @Override
        public String toString() {
            return id;
        }
    }
}
