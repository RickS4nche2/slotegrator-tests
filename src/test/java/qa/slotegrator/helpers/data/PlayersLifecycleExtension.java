package qa.slotegrator.helpers.data;

import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Закрывает существующий контекст; первичную ошибку и ошибку очистки объединяет JUnit Jupiter. */
@RequiredArgsConstructor
public final class PlayersLifecycleExtension implements AfterEachCallback {
    private final Supplier<PlayersTestContext> context;

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        var existing = context.get();
        if (existing != null)
            existing.close();
    }
}
