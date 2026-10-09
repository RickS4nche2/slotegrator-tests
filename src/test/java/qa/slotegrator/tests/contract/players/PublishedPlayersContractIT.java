package qa.slotegrator.tests.contract.players;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.PlayersLifecycleExtension;
import qa.slotegrator.helpers.data.PlayersTestContext;
import qa.slotegrator.helpers.reporting.ExpectedFailure;
import qa.slotegrator.helpers.reporting.KnownFailure;

/**
 * Независимые проверки задания и исходного OpenAPI; источники и приоритеты — docs/api-spec.md.
 * Наблюдаемые модели используются для подготовки и очистки, исходный ответ проверяется без преобразований.
 */
@Epic("Players API")
@Feature("Соответствие опубликованному контракту")
@Story("Коды и модели ответов игроков")
@Tag("api")
@Tag("contract")
@Tag("regression")
@DisplayName("Соответствие операций с игроками заданию и OpenAPI")
class PublishedPlayersContractIT {
    private PlayersTestContext context;

    @RegisterExtension
    final PlayersLifecycleExtension lifecycle = new PlayersLifecycleExtension(() -> context);

    @BeforeEach
    void prepareIndependentContext() {
        context = new PlayersTestContext(ProjectSettings.load());
        Allure.step("Получить токен для собственного контекста проверки контракта",
                () -> context.authenticate(context.login()));
    }

    /**
     * Design: обязательные поля и типы ответа создания из PlayerResponseDTO; источник — docs/api-spec.md.
     * Steps: подготовить уникальный запрос, создать собственного игрока, проверить исходный JSON.
     * Expected: объект с целочисленным id и обязательными полями; строковый _id не заменяет id.
     */
    @Test
    @DisplayName("Ответ создания соответствует опубликованной PlayerResponseDTO")
    @Tag("CREATE-02")
    @ExpectedFailure(bug = "BUG-003", failure = KnownFailure.PLAYER_ID_NOT_INTEGER)
    void creationMatchesPublishedSchema() {
        var request = Allure.step("Подготовить уникальные данные собственного игрока",
                () -> context.newPlayerRequest("Контракт создания"));
        var response = Allure.step("Создать собственного игрока и сохранить исходный ответ",
                () -> context.create(request));
        Allure.step("Ответ создания соответствует PlayerResponseDTO без замены полей и типов",
                () -> PublishedContractExpected.verifyPlayerSchema(response, context.codec()));
    }

    /**
     * Design: точный код профиля по заданию имеет приоритет над 201 из OpenAPI; источник — docs/api-spec.md.
     * Steps: подготовить собственного игрока, запросить профиль по email, проверить исходный статус.
     * Expected: ровно HTTP 200; модель ответа проверяется отдельным независимым тестом.
     */
    @Test
    @DisplayName("Профиль соответствует требованию HTTP 200 из задания")
    @Tag("PROFILE-01")
    @ExpectedFailure(bug = "BUG-004", failure = KnownFailure.PROFILE_STATUS_201)
    void profileStatusMatchesAssignment() {
        var player = context.preparePlayer("Контракт кода профиля");
        var response = Allure.step("Запросить профиль собственного игрока по email",
                () -> context.profile(player.request()));
        Allure.step("Профиль возвращает HTTP 200 согласно заданию",
                () -> PublishedContractExpected.verifyProfileStatus(response));
    }

    /**
     * Design: обязательные поля и типы профиля из PlayerResponseDTO; источник — docs/api-spec.md.
     * Steps: подготовить собственного игрока, запросить профиль, проверить исходный JSON.
     * Expected: объект с целочисленным id; строковый ID не приводится к опубликованному типу.
     */
    @Test
    @DisplayName("Ответ профиля соответствует опубликованной PlayerResponseDTO")
    @Tag("PROFILE-02")
    @ExpectedFailure(bug = "BUG-003", failure = KnownFailure.PLAYER_ID_NOT_INTEGER)
    void profileMatchesPublishedSchema() {
        var player = context.preparePlayer("Контракт модели профиля");
        var response = Allure.step("Запросить исходный ответ профиля собственного игрока",
                () -> context.profile(player.request()));
        Allure.step("Профиль соответствует PlayerResponseDTO без приведения типов",
                () -> PublishedContractExpected.verifyPlayerSchema(response, context.codec()));
    }

    /**
     * Design: соответствие ответа буквальной схеме getAll; источник и конфликт — docs/api-spec.md.
     * Steps: подготовить собственную запись, запросить getAll, проверить исходный JSON по OpenAPI.
     * Expected: опубликован один PlayerResponseDTO. Массив выявляет дефект документации,
     * а требование задания к коллекции независимо проверяется функциональным набором.
     */
    @Test
    @DisplayName("Форма ответа списка соответствует опубликованной схеме getAll")
    @Tag("DOC-01")
    @ExpectedFailure(bug = "BUG-005", failure = KnownFailure.LIST_ROOT_ARRAY)
    void listMatchesPublishedSchema() {
        context.preparePlayer("Контракт модели списка");
        var response = Allure.step("Получить исходный ответ списка игроков", () -> context.listResponse());
        Allure.step("Форма getAll соответствует опубликованной схеме одного PlayerResponseDTO",
                () -> PublishedContractExpected.verifyListSchema(response, context.codec()));
    }

    /**
     * Design: обязательные поля и типы ответа удаления из PlayerResponseDTO; источник — docs/api-spec.md.
     * Steps: подготовить и адресно удалить собственного игрока, проверить исходный JSON.
     * Expected: объект с целочисленным id; строковый _id не заменяет опубликованный id.
     */
    @Test
    @DisplayName("Ответ удаления соответствует опубликованной PlayerResponseDTO")
    @Tag("DELETE-02")
    @ExpectedFailure(bug = "BUG-003", failure = KnownFailure.PLAYER_ID_NOT_INTEGER)
    void deletionMatchesPublishedSchema() {
        var player = context.preparePlayer("Контракт удаления");
        var response = Allure.step("Удалить подтверждённого собственного игрока и сохранить ответ",
                () -> context.delete(player, AuthorizationMode.VALID));
        Allure.step("Ответ удаления соответствует PlayerResponseDTO без замены полей и типов",
                () -> PublishedContractExpected.verifyPlayerSchema(response, context.codec()));
    }
}
