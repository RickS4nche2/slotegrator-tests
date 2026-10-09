package qa.slotegrator.api;

/** Пути из исходного OpenAPI; HTTP-методы закрепляются в интерфейсах и тестах. */
public final class ApiRoutes {

    public static final String LOGIN = "/api/tester/login";
    public static final String CREATE_PLAYER = "/api/automationTask/create";
    public static final String GET_PLAYER = "/api/automationTask/getOne";
    public static final String GET_PLAYERS = "/api/automationTask/getAll";
    public static final String DELETE_PLAYER = "/api/automationTask/deleteOne/{id}";

    private ApiRoutes() {
    }
}
