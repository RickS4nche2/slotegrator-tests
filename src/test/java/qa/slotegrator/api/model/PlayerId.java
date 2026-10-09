package qa.slotegrator.api.model;

import java.util.regex.Pattern;

/** Безопасный идентификатор path и журнала: опубликованное целое число либо наблюдаемый 24-hex. */
public final class PlayerId {
    private static final Pattern OBSERVED = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");

    private PlayerId() {
    }

    public static boolean isObserved(String value) {
        return value != null && OBSERVED.matcher(value).matches();
    }

    public static boolean isSafe(String value) {
        return isObserved(value) || value != null && INTEGER.matcher(value).matches();
    }

    public static void requireSafe(String value) {
        if (!isSafe(value))
            throw new IllegalArgumentException("ID не соответствует безопасной форме");
    }
}
