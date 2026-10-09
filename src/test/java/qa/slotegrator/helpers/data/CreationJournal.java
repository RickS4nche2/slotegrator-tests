package qa.slotegrator.helpers.data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.qameta.allure.Allure;

import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.api.model.PlayerId;
import qa.slotegrator.helpers.http.HttpResult;

/** Журнал попыток и подтверждённого владения. Очистка не зависит от HTTP-кода создания. */
public final class CreationJournal implements AutoCloseable {
    private record Attempt(String email, String username, Set<String> baseline) {
        boolean matches(ObservedPlayerResponse player) {
            return (email != null && email.equals(player.email()))
                    || (username != null && username.equals(player.username()));
        }
    }

    private static final int CLEANUP_READ_ATTEMPTS = 3;
    private static final int CLEANUP_ROUNDS = 3;
    private static final Duration CLEANUP_READ_PAUSE = Duration.ofMillis(100);
    private final String marker;
    private final PlayerOperations operations;
    private final List<Attempt> attempts = new ArrayList<>();
    private final Map<String, ObservedPlayerResponse> originalPlayers = new LinkedHashMap<>();
    private final Map<String, ObservedPlayerResponse> owned = new LinkedHashMap<>();
    private final Set<String> uncertainOwnership = new LinkedHashSet<>();
    private final Set<String> unattributed = new LinkedHashSet<>();
    private boolean closed;

    public CreationJournal(UUID runId, PlayerOperations operations) {
        marker = runId.toString().replace("-", "");
        this.operations = operations;
    }

    /** Вызвать до отправки любого POST создания, в том числе через Rest Assured. */
    public void beginAttempt(String email, String username) {
        ensureOpen();
        if ((email == null || !email.contains(marker)) && (username == null || !username.contains(marker))) {
            throw new IllegalArgumentException("Попытка должна иметь уникальный признак собственного прогона");
        }
        List<ObservedPlayerResponse> before = operations.list();
        Set<String> baseline = ids(before);
        if (attempts.isEmpty())
            before.forEach(player -> originalPlayers.put(player.id(), player));
        attempts.add(new Attempt(email != null && email.contains(marker) ? email : null,
                username != null && username.contains(marker) ? username : null, baseline));
    }

    /** До утверждений о статусе или модели ищем реально появившиеся собственные записи. */
    public void reconcile() {
        ensureOpen();
        reconcile(operations.list());
    }

    public Set<String> ownedIds() {
        return Set.copyOf(owned.keySet());
    }

    /** Свежая проверка принадлежности перед отдельным проверяемым DELETE. */
    public void requireOwned(String id) {
        reconcile();
        if (!owned.containsKey(id) || uncertainOwnership.contains(id))
            throw new IllegalStateException("Удаление запрещено: актуальная принадлежность ID не подтверждена");
    }

    /** Закрывает журнал без DELETE, если переданный актуальный снимок не содержит собственных записей. */
    public boolean closeIfNothingRemains(List<ObservedPlayerResponse> current) {
        ensureOpen();
        reconcile(current);
        Set<String> visible = ids(current);
        unattributed.removeIf(id -> !visible.contains(id));
        if (!owned.isEmpty() || !unattributed.isEmpty())
            return false;
        closed = true;
        return true;
    }

    /**
     * Удаляет подтверждённые собственные записи. Итог определяет последний снимок: промежуточный сбой чтения или
     * DELETE, после которого записей не осталось, попадает в отчёт, но не делает очистку неуспешной.
     */
    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        var problems = new ArrayList<CleanupFailure.Problem>();
        // Собственный ID может впервые найтись при любом свежем чтении, например после потерянного ответа POST.
        // Поэтому очередь пересобирается по проходам; число проходов ограничено, ID удаляется не более одного раза.
        var decided = new LinkedHashSet<String>();
        List<ObservedPlayerResponse> latest = null;
        for (int round = 0; round < CLEANUP_ROUNDS; round++) {
            try {
                latest = readForCleanup();
                reconcile(latest);
            } catch (Exception | AssertionError error) {
                latest = null;
                problems.add(problem(round == 0 ? "поиск перед очисткой" : "повторный поиск", null, error));
            }
            var pending = owned.keySet().stream().filter(id -> !decided.contains(id)).toList();
            if (pending.isEmpty() && latest != null)
                break;
            if (!pending.isEmpty())
                latest = null;
            for (String id : pending)
                deleteIfStillOwned(id, decided, problems);
        }
        boolean verified = false;
        try {
            // Повторно ищем и по уникальным признакам попытки: запись могла получить другой ID.
            List<ObservedPlayerResponse> after = latest != null ? latest : readForCleanup();
            reconcile(after);
            Set<String> remaining = ids(after);
            unattributed.removeIf(id -> !remaining.contains(id));
            verified = true;
        } catch (Exception | AssertionError error) {
            problems.add(problem("проверка после очистки", null, error));
        }
        var unresolved = new LinkedHashSet<>(owned.keySet());
        unresolved.addAll(unattributed);
        if (!unattributed.isEmpty())
            problems.add(new CleanupFailure.Problem("неподтверждённые записи", null, "принадлежность не установлена"));
        if (!problems.isEmpty())
            Allure.attachment("Итог очистки", "Проблемы=" + problems + "; неподтверждённые ID=" + unresolved);
        if (!verified || !unresolved.isEmpty())
            throw new CleanupFailure(problems, List.copyOf(unresolved));
    }

    /** Сбой свежего чтения оставляет ID в очереди следующего прохода; решение по ID принимается один раз. */
    private void deleteIfStillOwned(String id, Set<String> decided, List<CleanupFailure.Problem> problems) {
        try {
            // Каждый ID подтверждается заново: предыдущий DELETE мог изменить владельца следующего.
            reconcile(readForCleanup());
        } catch (Exception | AssertionError error) {
            problems.add(problem("поиск перед DELETE", id, error));
            return;
        }
        decided.add(id);
        if (!owned.containsKey(id))
            return;
        if (uncertainOwnership.contains(id)) {
            problems.add(new CleanupFailure.Problem("принадлежность ID", id, "не подтверждена"));
            return;
        }
        try {
            HttpResult result = operations.delete(id);
            if (result.status() != 200)
                problems.add(new CleanupFailure.Problem("DELETE", id, "HTTP " + result.status()));
        } catch (Exception | AssertionError error) {
            problems.add(problem("DELETE", id, error));
        }
    }

    /** Чтение списка идемпотентно; короткий повтор отделяет кратковременный сбой от недоступного снимка. */
    private List<ObservedPlayerResponse> readForCleanup() {
        for (int attempt = 1;; attempt++) {
            try {
                return operations.list();
            } catch (RuntimeException | AssertionError error) {
                if (attempt == CLEANUP_READ_ATTEMPTS)
                    throw error;
            }
            try {
                Thread.sleep(CLEANUP_READ_PAUSE.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Очистка прервана");
            }
        }
    }

    private void reconcile(List<ObservedPlayerResponse> current) {
        Set<String> visible = ids(current);
        owned.keySet().removeIf(id -> !visible.contains(id));
        for (var entry : owned.entrySet()) {
            boolean sameIdentity = current.stream().anyMatch(player -> player.id().equals(entry.getKey())
                    && attempts.stream()
                            .anyMatch(attempt -> isNewForAttempt(attempt, player) && attempt.matches(player)));
            if (!sameIdentity)
                uncertainOwnership.add(entry.getKey());
        }
        for (Attempt attempt : attempts) {
            for (ObservedPlayerResponse player : current) {
                if (!uncertainOwnership.contains(player.id()) && isNewForAttempt(attempt, player)
                        && attempt.matches(player)) {
                    owned.put(player.id(), player);
                }
            }
        }
        if (!attempts.isEmpty()) {
            for (ObservedPlayerResponse player : current) {
                var original = originalPlayers.get(player.id());
                // Исходный ID не удаляем. Новые признаки попытки на нём всё же запрещают clean verdict.
                boolean changedBaselineMarker = original != null
                        && attempts.stream().anyMatch(attempt -> attempt.matches(player) && !attempt.matches(original));
                if (changedBaselineMarker
                        || (!originalPlayers.containsKey(player.id()) && !owned.containsKey(player.id()))) {
                    unattributed.add(player.id());
                }
            }
        }
    }

    private boolean isNewForAttempt(Attempt attempt, ObservedPlayerResponse player) {
        return !attempts.getFirst().baseline().contains(player.id()) && !attempt.baseline().contains(player.id());
    }

    private Set<String> ids(List<ObservedPlayerResponse> players) {
        if (players.stream().anyMatch(player -> player == null || !PlayerId.isSafe(player.id()))) {
            throw new IllegalStateException("Снимок игроков содержит запись без безопасного наблюдаемого ID");
        }
        var ids = players.stream().map(ObservedPlayerResponse::id).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.size() != players.size())
            throw new IllegalStateException("Снимок содержит неоднозначные повторяющиеся ID");
        return ids;
    }

    private static CleanupFailure.Problem problem(String operation, String id, Throwable error) {
        return new CleanupFailure.Problem(operation, id, error.getClass().getSimpleName());
    }

    private void ensureOpen() {
        if (closed)
            throw new IllegalStateException("Журнал уже закрыт");
    }
}
