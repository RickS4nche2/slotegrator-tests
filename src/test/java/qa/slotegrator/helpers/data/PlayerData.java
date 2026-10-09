package qa.slotegrator.helpers.data;

import java.util.ArrayList;
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

    /**
     * Порядок проверяемых удалений от середины к краям: пока остаются соседи с обеих сторон, цель не совпадает
     * ни с самой старой, ни с самой новой записью, поэтому удаление крайней записи вместо цели заметно.
     */
    public static List<Integer> middleOutOrder(int size) {
        var order = new ArrayList<Integer>();
        int middle = size / 2;
        for (int step = 0; order.size() < size; step++) {
            int index = step % 2 == 0 ? middle + step / 2 : middle - (step + 1) / 2;
            if (index >= 0 && index < size)
                order.add(index);
        }
        return List.copyOf(order);
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
