package qa.slotegrator.helpers.reporting;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.opentest4j.TestAbortedException;

import qa.slotegrator.helpers.data.InvalidJsonField;

/** Выполняет тест и обычный teardown; меняет только точное известное падение его тела. */
public final class ExpectedFailureExtension implements InvocationInterceptor, TestWatcher {
    private static final Set<String> REQUEST_FIELDS = Set.of("email", "password", "username", "password_change",
            "password_repeat", "name", "surname", "currency_code");

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Allure.getLifecycle().updateTest(result -> result.getLabels()
                .removeIf(label -> label.getName().equals("expectedFailure") && label.getValue().equals("XFAIL")));
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> method,
            ExtensionContext context) throws Throwable {
        execute(invocation, method);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> method,
            ExtensionContext context) throws Throwable {
        execute(invocation, method);
    }

    private static void execute(Invocation<Void> invocation, ReflectiveInvocationContext<Method> method)
            throws Throwable {
        String enabled = System.getProperty("xfail.enabled", "false");
        if (!Set.of("true", "false").contains(enabled))
            throw new IllegalArgumentException("xfail.enabled принимает только true или false");
        var candidates = Arrays.asList(method.getExecutable().getAnnotationsByType(ExpectedFailure.class));
        for (var candidate : candidates)
            validate(candidate, method.getArguments());
        String caseId = safeCaseId(method.getArguments());
        var selected = candidates.stream().filter(candidate -> method.getArguments().isEmpty()
                ? candidate.caseId().length == 0
                : Arrays.asList(candidate.caseId()).contains(caseId)).toList();
        if (selected.size() > 1)
            throw new IllegalArgumentException("Один случай теста должен ссылаться ровно на один известный дефект");
        if (selected.isEmpty()) {
            invocation.proceed();
            return;
        }
        var expected = selected.getFirst();
        try {
            invocation.proceed();
        } catch (Throwable error) {
            // Ссылка на баг ставится только при точном воспроизведении: посторонний сбой не выглядит известным.
            if (!expected.failure().matches(error))
                throw error;
            linkKnownBug(expected);
            if (enabled.equals("false"))
                throw error;
            Allure.label("expectedFailure", "XFAIL");
            throw new TestAbortedException("XFAIL " + expected.bug() + ": " + expected.failure().description()
                    + " (docs/bugs/" + expected.bug() + ".md)");
        }
        linkKnownBug(expected);
        if (enabled.equals("false")) {
            // В строгом режиме успех остаётся успехом, но устаревшая отметка видна в отчёте.
            Allure.label("expectedFailure", "NOT_REPRODUCED");
            return;
        }
        Allure.label("expectedFailure", "XPASS");
        throw new AssertionError("XPASS " + expected.bug()
                + ": известный дефект не воспроизвёлся; перепроверьте и снимите ExpectedFailure");
    }

    private static void linkKnownBug(ExpectedFailure expected) throws Exception {
        var bug = Path.of("docs", "bugs", expected.bug() + ".md").toAbsolutePath();
        Allure.label("knownBug", expected.bug());
        Allure.issue(expected.bug(), bug.toUri().toString());
        Allure.attachment("Описание " + expected.bug(), "text/markdown", Files.readString(bug, StandardCharsets.UTF_8));
    }

    private static void validate(ExpectedFailure expected, List<Object> arguments) {
        if (!expected.bug().matches("BUG-[0-9]{3}"))
            throw new IllegalArgumentException("Известный дефект должен иметь идентификатор BUG-NNN");
        if (!expected.bug().equals(expected.failure().bug()))
            throw new IllegalArgumentException("Признак " + expected.failure() + " относится к другому дефекту");
        if (!Files.isRegularFile(Path.of("docs", "bugs", expected.bug() + ".md")))
            throw new IllegalArgumentException("Отсутствует Markdown-описание известного дефекта " + expected.bug());
        if (arguments.isEmpty() != (expected.caseId().length == 0))
            throw new IllegalArgumentException("Параметризованная отметка требует точный caseId, обычная — пустой");
        if (Arrays.stream(expected.caseId()).anyMatch(String::isBlank)
                || new HashSet<>(Arrays.asList(expected.caseId())).size() != expected.caseId().length)
            throw new IllegalArgumentException("caseId не должен содержать пустые или повторяющиеся значения");
    }

    private static String safeCaseId(List<Object> arguments) {
        if (arguments.isEmpty())
            return "";
        if (arguments.size() != 1)
            throw new IllegalArgumentException("ExpectedFailure поддерживает один безопасный параметр случая");
        Object argument = arguments.getFirst();
        if (argument instanceof Enum<?> value)
            return value.name();
        if (argument instanceof InvalidJsonField field && REQUEST_FIELDS.contains(field.field()))
            return field.field() + ":" + field.violation().name();
        throw new IllegalArgumentException("caseId разрешён только для enum или известного поля InvalidJsonField");
    }
}
