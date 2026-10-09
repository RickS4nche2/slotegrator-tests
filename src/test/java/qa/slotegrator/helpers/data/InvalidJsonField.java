package qa.slotegrator.helpers.data;

import tools.jackson.databind.node.ObjectNode;

/** Один дефект поля DTO; название случая не содержит тело запроса или реквизиты. */
public record InvalidJsonField(String field, Violation violation) {
    public enum Violation {
        MISSING, NULL, NUMBER, EMPTY, TOO_SHORT
    }

    public ObjectNode apply(ObjectNode original) {
        var body = original.deepCopy();
        switch (violation) {
            case MISSING -> body.remove(field);
            case NULL -> body.putNull(field);
            case NUMBER -> body.put(field, 123);
            case EMPTY -> body.put(field, "");
            case TOO_SHORT -> body.put(field, "abc");
        }
        return body;
    }

    @Override
    public String toString() {
        return field + ":" + violation;
    }
}
