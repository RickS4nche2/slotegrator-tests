package qa.slotegrator.helpers.json;

/** Диагностика без исходного JSON и сообщения парсера, которые могут содержать секреты. */
public final class PayloadException extends RuntimeException {
    public PayloadException(String message) {
        super(message);
    }
}
