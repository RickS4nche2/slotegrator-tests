package qa.slotegrator.helpers.data;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import qa.slotegrator.api.model.CreatePlayerRequest;

public final class PlayerData {
    private PlayerData() {
    }

    public static List<CreatePlayerRequest> lifecyclePlayers(UUID runId, String currencyCode) {
        // Неотсортированный набор и два одинаковых имени различают ошибки порядка и потери элементов.
        var names = List.of("Victor", "Alice", "Maria", "Boris", "Alice", "Zoya",
                "Elena", "Daniel", "Nina", "George", "Olga", "Kirill");
        return IntStream.range(0, names.size())
                .mapToObj(index -> player(runId, index + 1, currencyCode, names.get(index)))
                .toList();
    }

    /** Четыре символа по нижней границе схемы; принадлежность прогона сохраняется в длинном email. */
    public static String minimumLengthUsername(Set<String> existingUsernames) {
        return IntStream.range(0, 0x1000)
                .mapToObj(number -> "q" + String.format("%03x", number))
                .filter(candidate -> !existingUsernames.contains(candidate))
                .findFirst().orElseThrow(() -> new IllegalStateException("Нет свободного граничного username"));
    }

    public static CreatePlayerRequest player(UUID runId, int number, String currencyCode, String name) {
        String marker = runId.toString().replace("-", "");
        String suffix = marker + "_" + number;
        String password = "T!" + UUID.randomUUID().toString().replace("-", "");
        return new CreatePlayerRequest(currencyCode, "qa+" + suffix + "@example.test", name,
                password, password, "Тестовый", "qa_" + suffix);
    }
}
