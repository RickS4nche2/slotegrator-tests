package qa.slotegrator.tests.integration.players;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import qa.slotegrator.api.model.PlayerLookupRequest;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.InvalidJsonField;
import qa.slotegrator.helpers.data.InvalidJsonField.Violation;
import qa.slotegrator.helpers.data.PlayersLifecycleExtension;
import qa.slotegrator.helpers.data.PlayersTestContext;

/** Независимые проверки профиля; негативные коды — рабочая интерпретация OpenAPI, см. docs/api-spec.md. */
@Epic("Players API")
@Feature("Профиль игрока")
@Story("Поиск по email возвращает нужного игрока и сохраняет данные")
@Tag("api")
@Tag("integration")
@Tag("regression")
@DisplayName("Независимые проверки профиля игрока")
class PlayerProfileIT {
    private PlayersTestContext context;

    @RegisterExtension
    final PlayersLifecycleExtension lifecycle = new PlayersLifecycleExtension(() -> context);

    @BeforeEach
    void prepareIndependentContext() {
        context = new PlayersTestContext(ProjectSettings.load());
        Allure.step("Получить и проверить токен собственного контекста",
                () -> context.authenticate(context.login()));
    }

    /**
     * Design: существующая собственная цель и отличающийся сосед различают выбор неправильной записи.
     * Steps: создать обоих, подтвердить снимок, прочитать профили в обратном порядке и повторный снимок.
     * Expected: каждый email выбирает свой ID и исходные поля; чтение не меняет состав и данные игроков.
     */
    @Test
    @Tag("smoke")
    @DisplayName("Профиль по email возвращает собственную цель без изменения игроков")
    @Tag("READ-01")
    void profileMatchesOwnPlayer() {
        var neighbour = context.preparePlayer("Контрольный");
        var target = context.preparePlayer("Целевой");
        var before = Allure.step("Прочитать исходное состояние перед поиском профиля",
                () -> context.snapshot());
        Allure.step("Подтвердить цель и контрольного игрока перед поиском",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(target, neighbour)));

        for (var player : List.of(target, neighbour)) {
            var response = Allure.step("Запросить профиль выбранного игрока по его email",
                    () -> context.profile(player.request()));
            Allure.step("Профиль соответствует именно запрошенному игроку",
                    () -> PlayersExpected.verifyProfile(response, player.request(), player.id(), context.codec()));
        }
        var after = Allure.step("Прочитать состояние после поиска профилей", () -> context.snapshot());
        Allure.step("Чтение профилей сохранило состав и данные игроков",
                () -> PlayersExpected.verifyUnchanged(before, after));
    }

    /**
     * Design: required, nullable:false и строковый тип email в PlayerRequestOneDTO.
     * Steps: подготовить цель и соседа, нарушить только поле email, выполнить поиск и перечитать.
     * Expected: 400 по принятому правилу проекта; см. docs/api-spec.md.
     * Исходное состояние сохранено до teardown.
     */
    @ParameterizedTest(name = "PlayerRequestOneDTO: {0}")
    @MethodSource("invalidLookups")
    @DisplayName("Нарушение схемы поиска отклоняется и сохраняет исходных игроков")
    @Tag("PROFILE-03")
    void rejectsInvalidLookup(InvalidJsonField invalid) {
        var neighbour = context.preparePlayer("Контрольный");
        var target = context.preparePlayer("Целевой");
        var valid = new PlayerLookupRequest(target.request().email());
        var body = invalid.apply((ObjectNode) context.codec().tree(context.codec().encode(valid)));
        var before = Allure.step("Прочитать исходное состояние перед некорректным поиском",
                () -> context.snapshot());
        Allure.step("Подтвердить цель и контрольного игрока перед некорректным поиском",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(target, neighbour)));

        var response = Allure.step("Запросить профиль с одним нарушением схемы email",
                () -> context.profile(body, AuthorizationMode.VALID));
        var after = Allure.step("Прочитать состояние после ответа до резервной очистки",
                () -> context.snapshot());
        Allure.step("Поиск отклонён с HTTP 400 и не изменил исходное состояние",
                () -> PlayersExpected.verifyRejectedOperation(response, 400, before, after));
    }

    /**
     * Design: отсутствующий и неверный Bearer при существующем email собственной цели.
     * Steps: подтвердить цель и соседа, запросить профиль без доступа, перечитать с валидным токеном.
     * Expected: 401 по принятому правилу проекта; см. docs/api-spec.md.
     * Обе записи и исходный состав сохранены.
     */
    @ParameterizedTest(name = "Bearer: {0}")
    @EnumSource(value = AuthorizationMode.class, names = {"MISSING", "INVALID"})
    @DisplayName("Отсутствующий или неверный Bearer не разрешает чтение профиля")
    @Tag("ACCESS-01")
    @Tag("ACCESS-02")
    void rejectsUnauthorizedProfile(AuthorizationMode mode) {
        var neighbour = context.preparePlayer("Контрольный");
        var target = context.preparePlayer("Целевой");
        var before = Allure.step("Прочитать исходное состояние перед поиском без доступа",
                () -> context.snapshot());
        Allure.step("Подтвердить цель и контрольного игрока перед поиском без доступа",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(target, neighbour)));

        var response = Allure.step("Запросить профиль цели с отсутствующим или неверным Bearer",
                () -> context.profile(new PlayerLookupRequest(target.request().email()), mode));
        var after = Allure.step("Авторизованно прочитать состояние до резервной очистки",
                () -> context.snapshot());
        Allure.step("Отказ HTTP 401 не изменил записи и не раскрыл идентификаторы сверх запроса", () -> {
            PlayersExpected.verifyRejectedOperation(response, 401, before, after);
            PlayersExpected.verifyNoPlayerIdentifiers(response, before, Set.of(target.request().email()),
                    context.codec());
        });
    }

    private static Stream<InvalidJsonField> invalidLookups() {
        return Stream.of(Violation.MISSING, Violation.NULL, Violation.NUMBER)
                .map(violation -> new InvalidJsonField("email", violation));
    }
}
