package qa.slotegrator.helpers.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.qameta.allure.Allure;

/**
 * Отклонения кодов и схем ответов от задания и OpenAPI в сквозном сценарии. Нарушение отмечается отдельным
 * упавшим шагом и не прерывает сценарий; итог проверяется в конце. Ошибки транспорта и разбора JSON не
 * являются AssertionError и по-прежнему прерывают сценарий.
 */
public final class ContractDeviations {
    private final Map<String, List<String>> deviations = new LinkedHashMap<>();

    public void check(String step, Allure.ThrowableRunnableVoid verification) {
        try {
            Allure.step(step, verification);
        } catch (AssertionError error) {
            deviations.computeIfAbsent(describe(error), ignored -> new ArrayList<>()).add(step);
        }
    }

    public void verifyNone() {
        if (deviations.isEmpty())
            return;
        String report = deviations.entrySet().stream()
                .map(entry -> "- " + entry.getKey() + "; проверок: " + entry.getValue().size() + " ("
                        + String.join("; ", entry.getValue()) + ")")
                .collect(Collectors.joining("\n"));
        throw new AssertionError(
                "Все шаги сценария выполнены, но ответы расходятся с заданием или OpenAPI:\n" + report);
    }

    private static String describe(AssertionError error) {
        String message = String.valueOf(error.getMessage());
        return error instanceof KnownFailure.Violation violation
                ? violation.failure().bug() + ": " + message
                : message;
    }
}
