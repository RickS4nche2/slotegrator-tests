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

import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.auth.AuthorizationMode;
import qa.slotegrator.helpers.config.ProjectSettings;
import qa.slotegrator.helpers.data.PlayerData;
import qa.slotegrator.helpers.data.PlayersLifecycleExtension;
import qa.slotegrator.helpers.data.PlayersTestContext;
import qa.slotegrator.helpers.data.RegisteredPlayer;
import qa.slotegrator.helpers.reporting.ContractDeviations;

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
        Allure.parameter("Изоляция стенда", "не подтверждена: запуск разрешён флагом e2e.authorized");
        players = new PlayersTestContext(ProjectSettings.load());
    }

    /**
     * Design: шесть действий задания, переходы состояний и сохранность списка при сортировке.
     * Steps: вход, создание 12 игроков, чтение всех профилей, список и сортировка, удаление, пустой список.
     * Expected: уникальные ID, поля исходных запросов во всех чтениях, адресное удаление, пустой итог;
     * сортировка сохраняет полные записи, включая игроков с одинаковым именем. Нарушение этих свойств
     * прерывает сценарий. Отклонения кодов и схем от задания и OpenAPI отмечаются в своих шагах, не мешают
     * пройти все шесть действий и проваливают тест на последнем шаге.
     * Предусловие: явное разрешение ограниченного запуска; пустой список не доказывает изоляцию.
     * JUnit lifecycle очищает собственные данные при промежуточном падении. Требования — docs/api-spec.md.
     */
    @Test
    @DisplayName("12 игроков сохраняют исходные данные и удаляются после проверки")
    @Tag("E2E-01")
    @Tag("E2E-02")
    void completePlayersLifecycle() {
        var run = players;
        var deviations = new ContractDeviations();
        Allure.step("Получить токен тестера и проверить вход", () -> {
            var login = run.loginResponse();
            deviations.check("Код входа соответствует заданию: 200",
                    () -> PublishedContractExpected.verifyLoginStatus(login));
            deviations.check("Ответ входа соответствует TokenDTO",
                    () -> PublishedContractExpected.verifyTokenSchema(login, run.codec()));
            run.authenticate(login);
        });

        var registered = Allure.step("Создать 12 игроков в пустом контексте", () -> {
            Allure.step("Подтвердить пустой начальный список", () -> PlayersExpected.verifyEmpty(run.list()));
            var requests = run.playerRequests();
            var result = new ArrayList<RegisteredPlayer>();
            for (int index = 0; index < requests.size(); index++) {
                var request = requests.get(index);
                int number = index + 1;
                var player = Allure.step("Создать игрока №" + number + " и проверить исходные поля", () -> {
                    var response = run.create(request);
                    var created = PlayersExpected.verifyCreated(response, request, run.ownedIds(), run.codec());
                    deviations.check("Ответ создания игрока №" + number + " соответствует PlayerResponseDTO",
                            () -> PublishedContractExpected.verifyPlayerSchema(response, run.codec()));
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
                int number = index + 1;
                Allure.step("Проверить профиль игрока №" + number, () -> {
                    var profile = run.profile(player.request());
                    PlayersExpected.verifyProfile(profile, player.request(), player.id(), run.codec());
                    deviations.check("Код профиля игрока №" + number + " соответствует заданию: 200",
                            () -> PublishedContractExpected.verifyProfileStatus(profile));
                    deviations.check("Профиль игрока №" + number + " соответствует PlayerResponseDTO",
                            () -> PublishedContractExpected.verifyPlayerSchema(profile, run.codec()));
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
            for (int index : PlayerData.middleOutOrder(registered.size())) {
                var player = registered.get(index);
                int number = index + 1;
                Allure.step("Удалить игрока №" + number + " и проверить ответ и оставшиеся записи", () -> {
                    var before = run.snapshot();
                    var response = run.delete(player, AuthorizationMode.VALID);
                    var after = run.snapshot();
                    PlayersExpected.verifyDeleted(response, player, before, after, run.codec());
                    deviations.check("Ответ удаления игрока №" + number + " соответствует PlayerResponseDTO",
                            () -> PublishedContractExpected.verifyPlayerSchema(response, run.codec()));
                });
            }
        });
        Allure.step("Подтвердить полностью пустой итоговый список", () -> {
            var remaining = PlayersExpected.readList(run.listResponse(), run.codec());
            PlayersExpected.verifyEmpty(remaining);
            run.confirmNothingOwned(remaining);
        });
        Allure.step("Сверить коды и схемы ответов с заданием и OpenAPI", deviations::verifyNone);
    }

}
