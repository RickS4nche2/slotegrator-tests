package qa.slotegrator.helpers.data;

import java.util.List;

import qa.slotegrator.api.model.CreatePlayerRequest;
import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.helpers.http.HttpResult;

/** list() должен возвращать полный достоверный снимок контролируемого контекста. */
public interface PlayerOperations {
    HttpResult create(CreatePlayerRequest request);
    List<ObservedPlayerResponse> list();
    HttpResult delete(String id);
}
