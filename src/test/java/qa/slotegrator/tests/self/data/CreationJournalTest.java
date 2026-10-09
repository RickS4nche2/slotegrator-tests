package qa.slotegrator.tests.self.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedCreatedPlayerResponse;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.helpers.data.CleanupFailure;
import qa.slotegrator.helpers.data.CreationJournal;
import qa.slotegrator.helpers.data.PlayerData;
import qa.slotegrator.helpers.data.PlayerOperations;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.json.PayloadException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Жизненный цикл созданных игроков")
class CreationJournalTest {
    private final UUID runId = UUID.randomUUID();
    private final MemoryOperations operations = new MemoryOperations();

    @Test
    @DisplayName("Ошибка первого DELETE не прерывает удаление остальных записей")
    void continuesAfterFirstDeletionFailure() {
        var journal = journal();
        for (int index = 1; index <= 3; index++)
            journal.create(player(index));
        operations.failedDeletes = Set.of(id(1));
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(1), id(2), id(3)), operations.deleted);
        assertEquals(List.of(id(1)), error.unresolvedIds());
        assertEquals(1, error.problems().size());
    }

    @Test
    @DisplayName("Несколько ошибок удаления сохраняются без раскрытия сообщений транспорта")
    void aggregatesAllErrors() {
        var journal = journal();
        for (int index = 1; index <= 3; index++)
            journal.create(player(index));
        operations.failedDeletes = Set.of(id(1), id(2));
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(3, operations.deleted.size());
        assertEquals(2, error.problems().size());
        assertEquals(Set.of(id(1), id(2)), Set.copyOf(error.unresolvedIds()));
        assertFalse(error.toString().contains("dummy-cleanup-secret"));
        assertNull(error.getCause());
    }

    @Test
    @DisplayName("Исходная ошибка остаётся основной, ошибка очистки — suppressed")
    void preservesPrimaryFailure() {
        operations.failedDeletes = Set.of(id(1));
        var error = assertThrows(IllegalStateException.class, () -> {
            try (var journal = journal()) {
                journal.create(player(1));
                throw new IllegalStateException("Исходная ошибка сценария");
            }
        });
        assertEquals("Исходная ошибка сценария", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertInstanceOf(CleanupFailure.class, error.getSuppressed()[0]);
    }

    @Test
    @DisplayName("Отклонённый create, который всё же сохранил запись, не оставляет сироту")
    void recoversRejectedButPersistedCreation() {
        operations.createStatus = 400;
        var rejection = assertThrows(AssertionError.class, () -> {
            try (var journal = journal()) {
                var before = operations.list();
                var result = journal.create(player(1));
                assertEquals(400, result.status());
                // Независимое ожидание состояния обнаруживает дефект, несмотря на правильный код ответа.
                assertEquals(before.size(), operations.list().size(), "Отклонённый запрос изменил данные");
            }
        });
        assertTrue(rejection.getMessage().contains("изменил данные"));
        assertTrue(operations.list().isEmpty());
        assertEquals(List.of(id(1)), operations.deleted);
    }

    @Test
    @DisplayName("Потерянный ответ после записи восстанавливается по журналу попыток")
    void recoversLostResponse() {
        operations.loseResponse = true;
        assertThrows(IllegalStateException.class, () -> {
            try (var journal = journal()) {
                journal.create(player(1));
            }
        });
        assertTrue(operations.list().isEmpty());
        assertEquals(List.of(id(1)), operations.deleted);
    }

    @Test
    @DisplayName("Ошибка декодирования ответа не теряет уже созданный ID")
    void survivesInvalidResponseBody() {
        operations.invalidBody = true;
        assertThrows(PayloadException.class, () -> {
            try (var journal = journal()) {
                HttpResult response = journal.create(player(1));
                new JsonCodec().decode(response.body(), ObservedCreatedPlayerResponse.class);
            }
        });
        assertTrue(operations.list().isEmpty());
    }

    @Test
    @DisplayName("Предсуществующая запись с такими же признаками не удаляется")
    void preservesBaselineRecordDuringDuplicateAttempt() {
        operations.create(player(1));
        try (var journal = journal()) {
            journal.create(player(1));
        }
        assertEquals(Set.of(id(1)), operations.records.keySet());
        assertEquals(List.of(id(2)), operations.deleted);
    }

    @Test
    @DisplayName("Уже удалённый ID не удаляется повторно при закрытии журнала")
    void doesNotRepeatConfirmedDeletion() {
        try (var journal = journal()) {
            journal.create(player(1));
            operations.delete(id(1));
        }
        assertEquals(List.of(id(1)), operations.deleted);
    }

    @Test
    @DisplayName("Повторное закрытие не повторяет DELETE после ошибки очистки")
    void closeIsSinglePass() {
        var journal = journal();
        journal.create(player(1));
        operations.failedDeletes = Set.of(id(1));
        assertThrows(CleanupFailure.class, journal::close);
        journal.close();
        assertEquals(List.of(id(1)), operations.deleted);
    }

    @Test
    @DisplayName("Подмена владельца существующего ID блокирует удаление чужой записи")
    void neverDeletesReassignedIdentity() {
        var journal = journal();
        journal.create(player(1));
        operations.records.put(id(1),
                new ObservedPlayerResponse(id(1), "foreign", "foreign@example.test", "Другой", "Игрок", null));
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertTrue(operations.deleted.isEmpty());
        assertEquals(List.of(id(1)), error.unresolvedIds());
    }

    @Test
    @DisplayName("Изменённый сервером email не теряет уникальный username попытки")
    void recoversCreationUsingSurvivingUniqueMarker() {
        var original = player(1);
        operations.normalizeEmail = true;
        operations.createStatus = 400;
        try (var journal = journal()) {
            journal.create(
                    new CreatePlayerRequest(original.currencyCode(), "", original.name(), original.passwordChange(),
                            original.passwordRepeat(), original.surname(), original.username()));
            assertEquals(1, journal.ownedIds().size());
        }
        assertTrue(operations.list().isEmpty());
    }

    @Test
    @DisplayName("Новая запись без доказуемого владельца не удаляется и не считается очищенной")
    void reportsUnattributedRecordWithoutDeletingIt() {
        var journal = journal();
        var request = player(1);
        journal.beginAttempt(request.email(), request.username());
        operations.records.put(id(55),
                new ObservedPlayerResponse(id(55), "unattributed", "other@example.test", "Другой", "Игрок", null));
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(55)), error.unresolvedIds());
        assertTrue(operations.deleted.isEmpty());
    }

    /** Design: recovery. Steps: потерять снимок перед cleanup. Expected: неподтверждённый ID не удаляется. */
    @Test
    @DisplayName("Недоступное перечитывание владения не разрешает DELETE по устаревшему снимку")
    void skipsDeletionWithoutFreshOwnership() {
        var journal = journal();
        journal.create(player(1));
        operations.failReads = true;
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertTrue(operations.deleted.isEmpty());
        assertEquals(List.of(id(1)), error.unresolvedIds());
        assertTrue(error.problems().size() >= 2);
    }

    @Test
    @DisplayName("Один сбой начального поиска не блокирует очистку с доступным свежим снимком каждого ID")
    void continuesAfterInitialOwnershipReadFailure() {
        var journal = journal();
        for (int index = 1; index <= 3; index++)
            journal.create(player(index));
        operations.failNextRead = true;

        var error = assertThrows(CleanupFailure.class, journal::close);

        assertEquals(List.of(id(1), id(2), id(3)), operations.deleted);
        assertTrue(operations.records.isEmpty());
        assertTrue(error.unresolvedIds().isEmpty());
        assertEquals(List.of(new CleanupFailure.Problem("поиск перед очисткой", null, "IllegalStateException")),
                error.problems());
    }

    @Test
    @DisplayName("После сбоя начального поиска свежий снимок всё равно защищает чужую запись")
    void preservesReassignedIdentityAfterInitialReadFailure() {
        var journal = journal();
        journal.create(player(1));
        journal.create(player(2));
        operations.records.put(id(1),
                new ObservedPlayerResponse(id(1), "foreign", "foreign@example.test", "Другой", "Игрок", "USD"));
        operations.failNextRead = true;

        var error = assertThrows(CleanupFailure.class, journal::close);

        assertEquals(List.of(id(2)), operations.deleted);
        assertEquals(Set.of(id(1)), operations.records.keySet());
        assertEquals("foreign", operations.records.get(id(1)).username());
        assertEquals(List.of(id(1)), error.unresolvedIds());
        assertEquals(List.of(new CleanupFailure.Problem("поиск перед очисткой", null, "IllegalStateException"),
                new CleanupFailure.Problem("принадлежность ID", id(1), "не подтверждена")), error.problems());
    }

    /** Design: recovery. Steps: DELETE первого меняет владельца второго. Expected: второй не удаляется. */
    @Test
    @DisplayName("Подмена владельца между двумя DELETE обнаруживается свежим снимком")
    void checksOwnershipBeforeEveryDeletion() {
        var journal = journal();
        journal.create(player(1));
        journal.create(player(2));
        operations.afterDelete = deleted -> operations.records.put(id(2),
                new ObservedPlayerResponse(id(2), "foreign", "foreign@example.test", "Другой", "Игрок", "USD"));
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(1)), operations.deleted);
        assertEquals(List.of(id(2)), error.unresolvedIds());
        assertEquals("foreign", operations.records.get(id(2)).username());
    }

    /** Design: recovery. Steps: потерять один снимок после DELETE. Expected: пропуск второго, очистка третьего. */
    @Test
    @DisplayName("Ошибка свежего снимка одного ID не прерывает безопасную очистку остальных")
    void continuesAfterOneOwnershipReadFailure() {
        var journal = journal();
        for (int index = 1; index <= 3; index++)
            journal.create(player(index));
        operations.afterDelete = deleted -> {
            if (deleted.equals(id(1)))
                operations.failNextRead = true;
        };
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(1), id(3)), operations.deleted);
        assertEquals(List.of(id(2)), error.unresolvedIds());
        assertEquals("поиск перед DELETE", error.problems().getFirst().operation());
    }

    /** Design: forbidden effect. Steps: DELETE 200 сохраняет запись. Expected: cleanup не подтверждён. */
    @Test
    @DisplayName("DELETE 200 без удаления не даёт успешного результата очистки")
    void detectsSuccessfulDeletionWithoutEffect() {
        var journal = journal();
        journal.create(player(1));
        operations.preserveDeleted = true;
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(1)), error.unresolvedIds());
        assertEquals(List.of(id(1)), operations.deleted);
    }

    /** Design: recovery. Steps: запись получает новый ID после DELETE. Expected: поиск по маркеру находит остаток. */
    @Test
    @DisplayName("Исчезновение старого ID не скрывает собственную запись с новым ID")
    void detectsOwnRecordReappearingWithNewId() {
        var journal = journal();
        journal.create(player(1));
        var original = operations.records.get(id(1));
        operations.afterDelete = deleted -> operations.records.put(id(77), new ObservedPlayerResponse(id(77),
                original.username(), original.email(), original.name(), original.surname(), original.currencyCode()));
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(77)), error.unresolvedIds());
        assertEquals(List.of(id(1)), operations.deleted);
    }

    /** Design: ambiguity. Steps: дублировать ID снимка до создания. Expected: POST не выполняется. */
    @Test
    @DisplayName("Повторяющийся ID исходного снимка блокирует создание до POST")
    void blocksCreationOnDuplicateBaselineId() {
        operations.records.put(id(99),
                new ObservedPlayerResponse(id(99), "foreign", "other@example.test", "Другой", "Игрок", "USD"));
        operations.duplicateSnapshot = true;
        var journal = journal();
        assertThrows(IllegalStateException.class, () -> journal.create(player(1)));
        assertEquals(0, operations.createCalls);
        assertTrue(operations.deleted.isEmpty());
    }

    /** Design: ambiguity. Steps: дублировать ID перед cleanup. Expected: DELETE не выполняется. */
    @Test
    @DisplayName("Неоднозначный снимок перед очисткой запрещает удаление")
    void blocksDeletionOnDuplicateOwnershipId() {
        var journal = journal();
        journal.create(player(1));
        operations.duplicateSnapshot = true;
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertTrue(operations.deleted.isEmpty());
        assertEquals(List.of(id(1)), error.unresolvedIds());
    }

    /** Design: state/ownership. Steps: исходный ID исчезает и возвращается при следующей попытке. Expected: он не свой. */
    @Test
    @DisplayName("ID исходного снимка не становится собственным после исчезновения между попытками")
    void neverClaimsOriginalBaselineIdAcrossAttempts() {
        operations.records.put(id(99),
                new ObservedPlayerResponse(id(99), "foreign", "foreign@example.test", "Другой", "Игрок", "USD"));
        var journal = journal();
        journal.create(player(1));
        operations.records.remove(id(99));
        var second = player(2);
        journal.beginAttempt(second.email(), second.username());
        operations.records.put(id(99), new ObservedPlayerResponse(id(99), second.username(), second.email(),
                second.name(), second.surname(), "USD"));
        journal.reconcile();
        assertEquals(Set.of(id(1)), journal.ownedIds());
        var error = assertThrows(CleanupFailure.class, journal::close);
        assertEquals(List.of(id(99)), error.unresolvedIds());
        assertEquals(List.of(id(1)), operations.deleted);
        assertEquals(Set.of(id(99)), operations.records.keySet());
    }

    private static String id(int value) {
        return "%024x".formatted(value);
    }

    private CreationJournal journal() {
        return new CreationJournal(runId, operations);
    }
    private CreatePlayerRequest player(int index) {
        return PlayerData.player(runId, index, "USD", "Игрок " + index);
    }

    private static final class MemoryOperations implements PlayerOperations {
        private final Map<String, ObservedPlayerResponse> records = new LinkedHashMap<>();
        private final List<String> deleted = new ArrayList<>();
        private final JsonCodec codec = new JsonCodec();
        private Set<String> failedDeletes = Set.of();
        private int createStatus = 201;
        private long sequence;
        private boolean loseResponse;
        private boolean invalidBody;
        private boolean normalizeEmail;
        private boolean failReads;
        private boolean failNextRead;
        private boolean duplicateSnapshot;
        private boolean preserveDeleted;
        private int createCalls;
        private Consumer<String> afterDelete = ignored -> {
        };

        @Override
        public HttpResult create(CreatePlayerRequest request) {
            createCalls++;
            String id = id((int) ++sequence);
            var player = new ObservedPlayerResponse(id, request.username(),
                    normalizeEmail ? "normalized@example.test" : request.email(), request.name(), request.surname(),
                    "USD");
            records.put(id, player);
            if (loseResponse)
                throw new IllegalStateException("Потерян ответ после записи");
            var created = new ObservedCreatedPlayerResponse(id, player.username(), player.email(), player.name(),
                    player.surname(), player.currencyCode());
            return HttpResult.json(createStatus, invalidBody ? "{invalid" : codec.text(created));
        }

        @Override
        public List<ObservedPlayerResponse> list() {
            if (failReads || failNextRead) {
                failNextRead = false;
                throw new IllegalStateException("Снимок недоступен");
            }
            var snapshot = new ArrayList<>(records.values());
            if (duplicateSnapshot && !snapshot.isEmpty())
                snapshot.add(snapshot.getFirst());
            return List.copyOf(snapshot);
        }

        @Override
        public HttpResult delete(String id) {
            deleted.add(id);
            if (failedDeletes.contains(id))
                throw new IllegalStateException("dummy-cleanup-secret");
            if (!preserveDeleted)
                records.remove(id);
            afterDelete.accept(id);
            return HttpResult.json(200, "{}");
        }
    }
}
