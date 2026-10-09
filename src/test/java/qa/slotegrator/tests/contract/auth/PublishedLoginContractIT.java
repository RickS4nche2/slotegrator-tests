package qa.slotegrator.tests.contract.auth;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.auth.ApiSession;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.ExpectedFailure;
import qa.slotegrator.helpers.reporting.KnownFailure;

/** Каждый контракт проверяет ответ собственной независимой сессии. */
@Epic("Players API")
@Feature("Соответствие опубликованному контракту")
@Story("Код и модель ответа входа")
@Tag("api")
@Tag("contract")
@Tag("regression")
class PublishedLoginContractIT {
    private HttpResult login;
    private JsonCodec codec;

    @BeforeEach
    void readLoginResponse() {
        var settings = ProjectSettings.load();
        var session = new ApiSession(settings.api(), settings.auth());
        codec = session.codec();
        login = Allure.step("Получить исходный ответ входа собственной сессии", session::login);
    }

    /** Design: контракт — точный HTTP-код. Steps: проверить исходный статус. Expected: HTTP 200. */
    @Test
    @DisplayName("Вход соответствует требованию HTTP 200 из задания и OpenAPI")
    @Tag("AUTH-01")
    @ExpectedFailure(bug = "BUG-001", failure = KnownFailure.LOGIN_STATUS_201)
    void loginStatusMatchesPublishedContract() {
        Allure.step("Вход возвращает документированный HTTP 200",
                () -> PublishedContractExpected.verifyLoginStatus(login));
    }

    /** Design: контракт — обязательные поля и типы. Steps: проверить исходный JSON. Expected: TokenDTO. */
    @Test
    @DisplayName("Ответ входа содержит обязательные поля опубликованной TokenDTO")
    @Tag("AUTH-02")
    @ExpectedFailure(bug = "BUG-002", failure = KnownFailure.TOKEN_ACCESS_TOKEN_MISSING)
    void tokenMatchesPublishedSchema() {
        Allure.step("Токен соответствует исходной схеме без alias и подстановок",
                () -> PublishedContractExpected.verifyTokenSchema(login, codec));
    }
}
