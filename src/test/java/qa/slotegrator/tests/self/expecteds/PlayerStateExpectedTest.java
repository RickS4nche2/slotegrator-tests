package qa.slotegrator.tests.self.expecteds;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.MultipleFailuresError;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.expecteds.PlayersExpected;
import qa.slotegrator.helpers.data.RegisteredPlayer;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Последствия операций проверяются до очистки")
class PlayerStateExpectedTest {
    private static final JsonCodec CODEC = new JsonCodec();
    private static final String TARGET_ID = "000000000000000000000001";
    private static final String NEIGHBOR_ID = "000000000000000000000002";
    private static final CreatePlayerRequest REQUEST = new CreatePlayerRequest("USD", "own@example.test", "Игрок",
            "dummy-state-password", "dummy-state-password", "Тестовый", "own-player");
    private static final RegisteredPlayer TARGET = new RegisteredPlayer(TARGET_ID, REQUEST);
    private static final ObservedPlayerResponse OWN = new ObservedPlayerResponse(TARGET_ID, "own-player",
            "own@example.test", "Игрок", "Тестовый", "USD");
    private static final ObservedPlayerResponse NEIGHBOR = new ObservedPlayerResponse(NEIGHBOR_ID, "neighbor-player",
            "neighbor@example.test", "Сосед", "Контрольный", "EUR");
    private static final ObservedPlayerResponse FOREIGN = new ObservedPlayerResponse("ffffffffffffffffffffffff",
            "foreign-player", "foreign@example.test", "Чужой", "Исходный", "USD");
    private static final List<ObservedPlayerResponse> BEFORE = List.of(FOREIGN, OWN, NEIGHBOR);

    /** Design: непустой baseline. Steps: проверить подмножество и переставленный снимок. Expected: оба допустимы. */
    @Test
    @DisplayName("Собственные данные проверяются без требования пустого исходного списка")
    void acceptsOwnSubsetWithForeignBaseline() {
        assertDoesNotThrow(() -> PlayersExpected.verifyOwnPlayers(BEFORE, List.of(TARGET)));
        assertDoesNotThrow(() -> PlayersExpected.verifyUnchanged(BEFORE, List.of(NEIGHBOR, FOREIGN, OWN)));
    }

    /** Design: error guessing. Steps: отказать и фактически сохранить. Expected: ошибка даже при правильном коде. */
    @Test
    @DisplayName("HTTP 400 при сохранённом игроке не превращается в успешный негативный тест")
    void rejectsPersistedCreationDespiteExpectedError() {
        assertThrows(AssertionError.class, () -> PlayersExpected.verifyRejectedCreate(HttpResult.json(400, "{}"),
                400, REQUEST, List.of(FOREIGN, NEIGHBOR), BEFORE));
    }

    /** Design: независимые oracles. Steps: нарушить статус и состояние. Expected: все ошибки собраны вместе. */
    @Test
    @DisplayName("Неизвестный HTTP 500 не пропускает проверку побочного создания")
    void reportsUnexpectedStatusAndMutationTogether() {
        var error = assertThrows(MultipleFailuresError.class,
                () -> PlayersExpected.verifyRejectedCreate(HttpResult.json(500, "{}"),
                        400, REQUEST, List.of(FOREIGN, NEIGHBOR), BEFORE));
        assertEquals(3, error.getFailures().size(), "Должны различаться статус, создание и изменение снимка");
    }

    @Test
    @DisplayName("Известное сохранение плохого запроса не скрывает порчу исходного игрока")
    void rejectsChangedBaselineBeforeKnownCreationFailure() {
        var changed = new ObservedPlayerResponse(NEIGHBOR.id(), NEIGHBOR.username(), NEIGHBOR.email(),
                "Искажённый", NEIGHBOR.surname(), NEIGHBOR.currencyCode());
        var error = assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyRejectedCreate(HttpResult.json(201, "{}"),
                        400, REQUEST, List.of(FOREIGN, NEIGHBOR), List.of(FOREIGN, OWN, changed)));
        assertTrue(!(error instanceof MultipleFailuresError), "Порча baseline не становится известной тройкой ошибок");
    }

    @Test
    @DisplayName("HTTP 401 не скрывает фактически выполненное удаление")
    void rejectsUnauthorizedDeletionSideEffect() {
        assertThrows(AssertionError.class, () -> PlayersExpected.verifyRejectedOperation(HttpResult.json(401, "{}"),
                401, BEFORE, List.of(FOREIGN, NEIGHBOR)));
    }

    @Test
    @DisplayName("Код 401 не скрывает возвращённые данные существующего игрока")
    void rejectsPrivatePlayerDataDespiteDeniedStatus() {
        assertThrows(AssertionError.class,
                () -> PlayersExpected.verifyNoPlayerIdentifiers(HttpResult.json(401, CODEC.text(OWN)),
                        BEFORE, Set.of(TARGET_ID), CODEC));
        assertDoesNotThrow(() -> PlayersExpected.verifyNoPlayerIdentifiers(HttpResult.json(401,
                "{\"path\":\"/deleteOne/" + TARGET_ID + "\"}"), BEFORE, Set.of(TARGET_ID), CODEC));
        for (String identifier : List.of(TARGET_ID, OWN.email(), OWN.username())) {
            assertThrows(AssertionError.class, () -> PlayersExpected.verifyNoPlayerIdentifiers(
                    HttpResult.json(401, "{\"data\":\"" + identifier + "\"}"), BEFORE, Set.of(), CODEC));
        }
    }

    @Test
    @DisplayName("Один POST не должен незаметно создать две собственные записи")
    void rejectsDuplicateCreationBeforeCleanup() {
        var duplicate = new ObservedPlayerResponse("000000000000000000000003", OWN.username(), OWN.email(),
                OWN.name(), OWN.surname(), OWN.currencyCode());
        assertDoesNotThrow(() -> PlayersExpected.verifyCreationState(List.of(FOREIGN, NEIGHBOR), BEFORE, TARGET));
        assertThrows(AssertionError.class, () -> PlayersExpected.verifyCreationState(List.of(FOREIGN, NEIGHBOR),
                List.of(FOREIGN, NEIGHBOR, OWN, duplicate), TARGET));
    }

    @Test
    @DisplayName("Удаление цели сохраняет собственного соседа и исходную чужую запись")
    void verifiesOnlyTargetRemoved() {
        assertDoesNotThrow(() -> PlayersExpected.verifyDeleted(deletedResponse(TARGET_ID), TARGET, BEFORE,
                List.of(NEIGHBOR, FOREIGN), CODEC));
    }

    @Test
    @DisplayName("Успешный ответ DELETE не скрывает удаление соседа")
    void rejectsCollateralDeletion() {
        assertThrows(AssertionError.class, () -> PlayersExpected.verifyDeleted(deletedResponse(TARGET_ID), TARGET,
                BEFORE, List.of(FOREIGN), CODEC));
    }

    @Test
    @DisplayName("Проверка удаления требует существования цели перед действием")
    void rejectsMissingTargetInBaseline() {
        assertThrows(AssertionError.class, () -> PlayersExpected.verifyDeleted(deletedResponse(TARGET_ID), TARGET,
                List.of(FOREIGN, NEIGHBOR), List.of(FOREIGN, NEIGHBOR), CODEC));
    }

    @ParameterizedTest(name = "Неверный ответ DELETE: {0}")
    @MethodSource("invalidDeletionResponses")
    @DisplayName("Код и идентичность ответа удаления проверяются независимо от пустоты цели")
    void rejectsWrongDeletionResponse(ResponseCase testCase) {
        assertThrows(AssertionError.class, () -> PlayersExpected.verifyDeleted(testCase.response(), TARGET, BEFORE,
                List.of(FOREIGN, NEIGHBOR), CODEC));
    }

    private static Stream<ResponseCase> invalidDeletionResponses() {
        return Stream.of(new ResponseCase("другой-id", deletedResponse(NEIGHBOR_ID)),
                new ResponseCase("нет-id", HttpResult.json(200, "{}")),
                new ResponseCase("неверный-код", HttpResult.json(204, "{}")));
    }

    private static HttpResult deletedResponse(String id) {
        return HttpResult.json(200, "{\"_id\":\"" + id + "\"}");
    }

    private record ResponseCase(String name, HttpResult response) {
        @Override
        public String toString() {
            return name;
        }
    }
}
