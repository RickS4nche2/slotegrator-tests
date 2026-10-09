package qa.slotegrator.api.model;

public record LoginRequest(String email, String password) {
    @Override
    public String toString() {
        return "LoginRequest[реквизиты скрыты]";
    }
}
