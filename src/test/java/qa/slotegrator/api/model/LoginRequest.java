package qa.slotegrator.api.model;

import lombok.With;

@With
public record LoginRequest(String email, String password) {
    @Override
    public String toString() {
        return "LoginRequest[реквизиты скрыты]";
    }
}
