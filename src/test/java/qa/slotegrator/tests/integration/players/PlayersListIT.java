package qa.slotegrator.tests.integration.players;

import java.util.List;
import java.util.Set;

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

import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.PlayersLifecycleExtension;
import qa.slotegrator.helpers.data.PlayersTestContext;

/** Проверки состава списка не требуют пустого исходного контекста или серверной сортировки. */
@Epic("Players API")
@Feature("Список игроков")
@Story("Список сохраняет разные записи с одинаковыми именами")
@Tag("api")
@Tag("integration")
@Tag("regression")
@DisplayName("Независимые проверки списка игроков")
class PlayersListIT {
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
     * Design: два разных ID с одинаковым именем различают потерю записей; повтор чтения — инвариант.
     * Steps: создать одноимённых игроков, подтвердить их, получить список дважды.
     * Expected: 200 и массив, оба игрока ровно по одному разу с исходными полями; состав не изменён.
     */
    @Test
    @Tag("smoke")
    @DisplayName("Список содержит обоих одноимённых игроков и сохраняется при повторном чтении")
    @Tag("LIST-01")
    @Tag("READ-01")
    void repeatedListPreservesPlayersWithSameName() {
        var first = context.preparePlayer("Одноимённый");
        var second = context.preparePlayer("Одноимённый");
        var ownPlayers = List.of(first, second);
        var before = Allure.step("Прочитать исходное состояние перед проверкой списка",
                () -> context.snapshot());
        Allure.step("Подтвердить две разные собственные записи с одинаковым именем",
                () -> PlayersExpected.verifyOwnPlayers(before, ownPlayers));

        var response = Allure.step("Получить список игроков", () -> context.listResponse(AuthorizationMode.VALID));
        var listed = Allure.step("Список вернул HTTP 200 и содержит обе собственные записи", () -> {
            var players = PlayersExpected.readList(response, context.codec());
            PlayersExpected.verifyOwnPlayers(players, ownPlayers);
            PlayersExpected.verifyUnchanged(before, players);
            return players;
        });
        var repeated = Allure.step("Повторно получить список игроков",
                () -> context.listResponse(AuthorizationMode.VALID));
        Allure.step("Повторное чтение сохранило обе записи, полный состав и исходные поля", () -> {
            var after = PlayersExpected.readList(repeated, context.codec());
            PlayersExpected.verifyOwnPlayers(after, ownPlayers);
            PlayersExpected.verifyUnchanged(listed, after);
        });
    }

    /**
     * Design: отсутствующий и неверный Bearer при подтверждённом непустом наборе своих записей.
     * Steps: подготовить двух игроков, запросить список без доступа, перечитать с валидным токеном.
     * Expected: 401 по принятому правилу проекта; см. docs/api-spec.md.
     * Исходный состав и поля сохранены до teardown.
     */
    @ParameterizedTest(name = "Bearer: {0}")
    @EnumSource(value = AuthorizationMode.class, names = {"MISSING", "INVALID"})
    @DisplayName("Отсутствующий или неверный Bearer не разрешает чтение списка")
    @Tag("ACCESS-01")
    @Tag("ACCESS-02")
    void rejectsUnauthorizedList(AuthorizationMode mode) {
        var first = context.preparePlayer("Первый");
        var second = context.preparePlayer("Второй");
        var before = Allure.step("Прочитать исходное состояние перед чтением списка без доступа",
                () -> context.snapshot());
        Allure.step("Подтвердить две собственные записи перед чтением списка без доступа",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(first, second)));

        var response = Allure.step("Получить список с отсутствующим или неверным Bearer",
                () -> context.listResponse(mode));
        var after = Allure.step("Авторизованно прочитать состояние до резервной очистки",
                () -> context.snapshot());
        Allure.step("Отказ HTTP 401 не изменил записи и не раскрыл идентификаторы сверх запроса",
                () -> {
                    PlayersExpected.verifyRejectedOperation(response, 401, before, after);
                    PlayersExpected.verifyNoPlayerIdentifiers(response, before, Set.of(), context.codec());
                });
    }
}
