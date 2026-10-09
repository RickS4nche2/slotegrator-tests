package qa.slotegrator.helpers.config;

public record AuthSettings(String basicUsername, String basicPassword, String testerEmail, String testerPassword) {
    public AuthSettings {
        required(basicUsername, "BASIC_AUTH_USERNAME");
        required(basicPassword, "BASIC_AUTH_PASSWORD");
        required(testerEmail, "TESTER_EMAIL");
        required(testerPassword, "TESTER_PASSWORD");
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Не задано " + name);
    }

    @Override
    public String toString() {
        return "AuthSettings[реквизиты скрыты]";
    }
}
