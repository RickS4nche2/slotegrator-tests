package qa.slotegrator.expecteds;

import qa.slotegrator.api.ApiContract;
import qa.slotegrator.helpers.http.HttpResult;
import qa.slotegrator.helpers.json.JsonCodec;
import qa.slotegrator.helpers.reporting.KnownFailure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ожидания исходного задания и OpenAPI; наблюдение стенда не меняет их. */
public final class PublishedContractExpected {
    public static final int DOCUMENTED_LOGIN_STATUS = 200;
    public static final int ASSIGNMENT_PROFILE_STATUS = 200;

    private PublishedContractExpected() {
    }

    public static void verifyLoginStatus(HttpResult response) {
        KnownFailure.LOGIN_STATUS_201.rejectIf(response.status() == 201);
        assertEquals(DOCUMENTED_LOGIN_STATUS, response.status(),
                "Код входа противоречит заданию и опубликованному OpenAPI");
    }

    public static void verifyTokenSchema(HttpResult response, JsonCodec codec) {
        assertTrue(response.status() == 200 || response.status() == 201,
                "Ошибка входа не должна маскироваться известным дефектом успешной TokenDTO");
        ApiContract.token(codec.tree(response.body()));
    }

    public static void verifyProfileStatus(HttpResult response) {
        KnownFailure.PROFILE_STATUS_201.rejectIf(response.status() == 201);
        assertEquals(ASSIGNMENT_PROFILE_STATUS, response.status(),
                "Код профиля противоречит требованию HTTP 200 из задания");
    }

    public static void verifyPlayerSchema(HttpResult response, JsonCodec codec) {
        assertTrue(response.status() == 200 || response.status() == 201,
                "Ошибка операции не должна маскироваться известным дефектом успешной PlayerResponseDTO");
        ApiContract.player(codec.tree(response.body()));
    }

    /** Исходный OpenAPI описывает один объект; расхождение с требуемым списком — дефект документации. */
    public static void verifyListSchema(HttpResult response, JsonCodec codec) {
        assertEquals(200, response.status(), "Ошибка чтения не является дефектом схемы списка");
        var body = codec.tree(response.body());
        KnownFailure.LIST_ROOT_ARRAY.rejectIf(body.isArray());
        assertFalse(body.isArray(), "OpenAPI описывает объект списка, но API вернул массив");
        ApiContract.player(body);
    }
}
