package qa.slotegrator.helpers.reporting;

/** Явные признаки известных нарушений; сообщения и stack trace не участвуют в классификации. */
public enum KnownFailure {
    LOGIN_STATUS_201("BUG-001", "Вход вернул 201 вместо документированного 200"), TOKEN_ACCESS_TOKEN_MISSING("BUG-002",
            "Вместо TokenDTO возвращён непустой accessToken"), PLAYER_ID_NOT_INTEGER("BUG-003",
                    "Модель игрока содержит строковый 24-hex ID вместо целочисленного id"), PROFILE_STATUS_201(
                            "BUG-004", "Профиль вернул 201 вместо требуемого заданием 200"), LIST_ROOT_ARRAY("BUG-005",
                                    "OpenAPI описывает объект, API возвращает массив игроков"), BASIC_AUTH_IGNORED(
                                            "BUG-006",
                                            "Вход без действительного Basic выдал токен с HTTP 201 вместо отказа 401"), CREATE_INVALID_ACCEPTED(
                                                    "BUG-008",
                                                    "Некорректный DTO создал одну запись с HTTP 201 вместо отказа 400");

    private final String bug;
    private final String description;

    KnownFailure(String bug, String description) {
        this.bug = bug;
        this.description = description;
    }

    public String bug() {
        return bug;
    }

    public String description() {
        return description;
    }

    /** Вызывается оракулом после независимых проверок, которые известный дефект не должен скрывать. */
    public void rejectIf(boolean reproduced) {
        if (reproduced)
            throw new Violation(this);
    }

    boolean matches(Throwable error) {
        return error instanceof Violation violation && violation.failure == this
                && error.getCause() == null && error.getSuppressed().length == 0;
    }

    public static final class Violation extends AssertionError {
        private final KnownFailure failure;

        private Violation(KnownFailure failure) {
            super(failure.description);
            this.failure = failure;
        }

        public KnownFailure failure() {
            return failure;
        }
    }
}
