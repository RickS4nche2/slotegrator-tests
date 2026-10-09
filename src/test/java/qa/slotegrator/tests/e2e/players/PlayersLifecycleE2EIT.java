package qa.slotegrator.tests.e2e.players;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import qa.slotegrator.api.ApiContract;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.PlayersLifecycleExtension;
import qa.slotegrator.helpers.data.PlayersTestContext;
import qa.slotegrator.helpers.data.RegisteredPlayer;

@Epic("Players API")
@Feature("Жизненный цикл игроков")
@Story("Сценарий задания с 12 игроками")
@Tag("api")
@Tag("e2e")
@Tag("regression")
@DisplayName("Жизненный цикл 12 игроков")
class PlayersLifecycleE2EIT {
    // Эталон задан независимо от сортировки полученного ответа.
    private static final List<String> EXPECTED_NAME_ORDER = List.of("Alice", "Alice", "Boris", "Daniel", "Elena",
            "George", "Kirill", "Maria", "Nina", "Olga", "Victor", "Zoya");
    private PlayersTestContext players;

    @RegisterExtension
    final PlayersLifecycleExtension lifecycle = new PlayersLifecycleExtension(() -> players);

    @BeforeEach
    void prepareContext() {
        PlayersTestContext.requireAuthorized(System.getProperty("e2e.authorized", "false"));
        players = new PlayersTestContext(ProjectSettings.load());
    }

    /**
     * Design: шесть действий задания, переходы состояний и сохранность списка при сортировке.
     * Steps: вход, создание 12 игроков, чтение всех профилей, список и сортировка, удаление, пустой список.
     * Expected: коды задания и опубликованная схема, уникальные ID, поля исходных запросов во всех чтениях;
     * сортировка сохраняет полные записи, включая игроков с одинаковым именем. Итоговый список пуст.
     * Предусловие: явное разрешение ограниченного запуска; пустой список не доказывает изоляцию.
     * JUnit lifecycle очищает собственные данные при промежуточном падении. Требования — docs/api-spec.md.
     */
    @Test
    @DisplayName("12 игроков сохраняют исходные данные и удаляются после проверки")
    @Tag("E2E-01")
    @Tag("E2E-02")
    void completePlayersLifecycle() {
        var run = players;
        Allure.step("Получить токен тестера и проверить вход", () -> {
            var login = run.loginResponse();
            PublishedContractExpected.verifyLoginStatus(login);
            run.authenticate(login);
        });

        var registered = Allure.step("Создать 12 игроков в пустом контексте", () -> {
            Allure.step("Подтвердить пустой начальный список", () -> PlayersExpected.verifyEmpty(run.list()));
            var requests = run.playerRequests();
            var result = new ArrayList<RegisteredPlayer>();
            for (int index = 0; index < requests.size(); index++) {
                var request = requests.get(index);
                var player = Allure.step("Создать игрока №" + (index + 1) + " и проверить исходные поля", () -> {
                    var response = run.create(request);
                    var created = PlayersExpected.verifyCreated(response, request, run.ownedIds(), run.codec());
                    ApiContract.player(run.codec().tree(response.body()));
                    return created;
                });
                result.add(new RegisteredPlayer(player.id(), request));
            }
            PlayersExpected.verifyRegistrations(result, PlayersTestContext.PLAYER_COUNT);
            return result;
        });

        Allure.step("Прочитать и проверить профили всех 12 игроков", () -> {
            for (int index = 0; index < registered.size(); index++) {
                var player = registered.get(index);
                Allure.step("Проверить профиль игрока №" + (index + 1), () -> {
                    var profile = run.profile(player.request());
                    PublishedContractExpected.verifyProfileStatus(profile);
                    ApiContract.player(run.codec().tree(profile.body()));
                    PlayersExpected.verifyProfile(profile, player.request(), player.id(), run.codec());
                });
            }
        });

        Allure.step("Проверить полный список и отсортировать игроков по имени", () -> {
            var current = PlayersExpected.verifyList(run.listResponse(), registered, run.codec());
            var sorted = current.stream().sorted(Comparator.comparing(ObservedPlayerResponse::name)).toList();
            PlayersExpected.verifySortedPlayers(current, sorted, EXPECTED_NAME_ORDER);
            Allure.attachment("Имена после сортировки", "application/json",
                    run.codec().text(sorted.stream().map(ObservedPlayerResponse::name).toList()));
        });

        Allure.step("Удалить всех подтверждённых собственных игроков", () -> {
            // Обратный порядок отличает адресное удаление от ошибочного удаления первой записи.
            for (var player : registered.reversed()) {
                Allure.step("Удалить игрока и проверить ответ и оставшиеся записи", () -> {
                    var before = run.snapshot();
                    var response = run.delete(player, AuthorizationMode.VALID);
                    var after = run.snapshot();
                    PlayersExpected.verifyDeleted(response, player, before, after, run.codec());
                    ApiContract.player(run.codec().tree(response.body()));
                });
            }
        });
        Allure.step("Подтвердить полностью пустой итоговый список",
                () -> PlayersExpected.verifyEmpty(PlayersExpected.readList(run.listResponse(), run.codec())));
    }
}
