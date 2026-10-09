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

/** Независимые проверки адресного удаления; последствия проверяются до резервной очистки. */
@Epic("Players API")
@Feature("Удаление игрока")
@Story("Удаление затрагивает только собственную цель и требует авторизации")
@Tag("api")
@Tag("integration")
@Tag("regression")
@DisplayName("Независимые проверки удаления игрока")
class PlayerDeletionIT {
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
     * Design: переход существующей цели в отсутствие; сосед различает удаление лишних записей.
     * Steps: создать и подтвердить двух игроков, удалить цель, прочитать состояние до teardown.
     * Expected: 200 и _id цели; цель отсутствует по ID/email/username, остальные записи неизменны.
     */
    @Test
    @Tag("smoke")
    @DisplayName("Удаление убирает только целевого игрока и сохраняет контрольного соседа")
    @Tag("DELETE-01")
    void deletesOnlyOwnTarget() {
        var neighbour = context.preparePlayer("Контрольный");
        var target = context.preparePlayer("Целевой");
        var before = Allure.step("Прочитать исходное состояние перед удалением",
                () -> context.snapshot());
        Allure.step("Подтвердить существование цели и контрольного игрока перед удалением",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(target, neighbour)));

        var response = Allure.step("Удалить только подтверждённого целевого игрока",
                () -> context.delete(target, AuthorizationMode.VALID));
        var after = Allure.step("Прочитать состояние после удаления до резервной очистки",
                () -> context.snapshot());
        Allure.step("DELETE вернул HTTP 200 и удалил только цель, сохранив контрольного игрока",
                () -> PlayersExpected.verifyDeleted(response, target, before, after, context.codec()));
    }

    /**
     * Design: отсутствующий и неверный Bearer при существующей собственной цели и контрольном соседе.
     * Steps: подтвердить обе записи, отправить DELETE без доступа, перечитать с валидным токеном.
     * Expected: 401 по принятому правилу проекта; см. docs/api-spec.md.
     * Цель, сосед и весь исходный состав сохранены.
     */
    @ParameterizedTest(name = "Bearer: {0}")
    @EnumSource(value = AuthorizationMode.class, names = {"MISSING", "INVALID"})
    @DisplayName("Отсутствующий или неверный Bearer не разрешает удаление существующего игрока")
    @Tag("ACCESS-01")
    @Tag("ACCESS-02")
    void rejectsUnauthorizedDeletion(AuthorizationMode mode) {
        var neighbour = context.preparePlayer("Контрольный");
        var target = context.preparePlayer("Целевой");
        var before = Allure.step("Прочитать исходное состояние перед удалением без доступа",
                () -> context.snapshot());
        Allure.step("Подтвердить цель и контрольного игрока перед удалением без доступа",
                () -> PlayersExpected.verifyOwnPlayers(before, List.of(target, neighbour)));

        var response = Allure.step("Удалить цель с отсутствующим или неверным Bearer",
                () -> context.delete(target, mode));
        var after = Allure.step("Авторизованно прочитать состояние до резервной очистки",
                () -> context.snapshot());
        Allure.step("Отказ HTTP 401 не изменил записи и не раскрыл идентификаторы сверх запроса", () -> {
            PlayersExpected.verifyRejectedOperation(response, 401, before, after);
            PlayersExpected.verifyNoPlayerIdentifiers(response, before, Set.of(target.id()), context.codec());
        });
    }
}
