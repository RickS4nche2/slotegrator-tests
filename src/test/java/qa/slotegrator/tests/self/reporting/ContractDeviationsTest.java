package qa.slotegrator.tests.self.reporting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import qa.slotegrator.expecteds.PublishedContractExpected;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.reporting.ContractDeviations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Сверка отклонений сквозного сценария")
class ContractDeviationsTest {

    /**
     * Design: отклонение кода в нескольких шагах. Steps: проверить коды 201 и 200, затем итог.
     * Expected: шаги не прерываются, итог падает один раз с номерами багов и числом повторов.
     */
    @Test
    @DisplayName("Отклонение кода не прерывает сценарий и проваливает итоговую сверку с номером бага")
    void collectsDeviationsAndFailsAtEnd() {
        var deviations = new ContractDeviations();
        deviations.check("Код входа", () -> PublishedContractExpected.verifyLoginStatus(HttpResult.json(201, "{}")));
        deviations.check("Профиль №1", () -> PublishedContractExpected.verifyProfileStatus(HttpResult.json(201, "{}")));
        deviations.check("Профиль №2", () -> PublishedContractExpected.verifyProfileStatus(HttpResult.json(201, "{}")));
        deviations.check("Профиль №3", () -> PublishedContractExpected.verifyProfileStatus(HttpResult.json(200, "{}")));

        var error = assertThrows(AssertionError.class, deviations::verifyNone);

        assertTrue(error.getMessage().contains("BUG-001: "), "Отклонение входа названо известным багом");
        assertTrue(error.getMessage().contains("BUG-004: "), "Отклонение профиля названо известным багом");
        assertTrue(error.getMessage().contains("проверок: 2 (Профиль №1; Профиль №2)"), "Повторы сгруппированы");
        assertFalse(error.getMessage().contains("Профиль №3"), "Успешная проверка не попадает в отклонения");
    }

    /** Design: сбой вне контракта. Steps: бросить исключение транспорта. Expected: сценарий прерывается сразу. */
    @Test
    @DisplayName("Ошибка транспорта или разбора не считается отклонением контракта")
    void propagatesNonAssertionErrors() {
        var deviations = new ContractDeviations();
        assertThrows(IllegalStateException.class, () -> deviations.check("Сбой транспорта", () -> {
            throw new IllegalStateException("Искусственный сбой транспорта");
        }));
        assertDoesNotThrow(deviations::verifyNone);
    }
}
