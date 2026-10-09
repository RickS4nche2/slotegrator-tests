package qa.slotegrator.tests.self.data;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import qa.slotegrator.helpers.data.InvalidJsonField;
import qa.slotegrator.helpers.data.InvalidJsonField.Violation;
import qa.slotegrator.helpers.data.PlayerData;
import qa.slotegrator.helpers.json.JsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Тестовые данные игроков")
class PlayerDataTest {

    /**
     * Design: ошибочное удаление крайней записи вместо цели. Steps: пройти порядок для 12 игроков.
     * Expected: пока остаются соседи с обеих сторон, цель не самая старая и не самая новая запись.
     */
    @Test
    @DisplayName("Удаление идёт от середины к краям и не выбирает крайнюю запись, пока есть соседи")
    void ordersDeletionFromMiddle() {
        var order = PlayerData.middleOutOrder(12);
        assertEquals(List.of(6, 5, 7, 4, 8, 3, 9, 2, 10, 1, 11, 0), order);
        var remaining = new TreeSet<>(IntStream.range(0, 12).boxed().toList());
        for (int index : order) {
            if (remaining.size() >= 3) {
                assertNotEquals(remaining.first().intValue(), index, "Цель совпала с самой старой записью");
                assertNotEquals(remaining.last().intValue(), index, "Цель совпала с самой новой записью");
            }
            remaining.remove(index);
        }
        assertEquals(List.of(1, 0, 2), PlayerData.middleOutOrder(3));
        assertEquals(List.of(), PlayerData.middleOutOrder(0));
    }

    /**
     * Design: сервер ошибочно приводит число к строке. Steps: подставить NUMBER в поле с minLength=4.
     * Expected: строковая форма числа проходит minLength, поэтому отказ возможен только из-за типа.
     */
    @Test
    @DisplayName("Число в нарушении типа после приведения к строке не нарушает minLength")
    void numberViolationSatisfiesLengthAfterCoercion() {
        var codec = new JsonCodec();
        var body = new InvalidJsonField("username", Violation.NUMBER).apply(codec.mapper().createObjectNode());
        assertTrue(body.get("username").isIntegralNumber());
        assertTrue(body.get("username").asString().length() >= 4);
    }
}
