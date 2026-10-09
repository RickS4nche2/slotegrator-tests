package qa.slotegrator.helpers.reporting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/** Известный дефект конкретной проверки; успешный результат требует снять отметку. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ExpectedFailure.Cases.class)
@ExtendWith(ExpectedFailureExtension.class)
public @interface ExpectedFailure {
    String bug();

    KnownFailure failure();

    /** Пусто только для обычного теста; для параметров — явный список точных безопасных идентификаторов. */
    String[] caseId() default {};

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(ExpectedFailureExtension.class)
    @interface Cases {
        ExpectedFailure[] value();
    }
}
