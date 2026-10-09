package qa.slotegrator.helpers.data;

import java.util.List;

import qa.slotegrator.api.model.ObservedPlayerResponse;
import qa.slotegrator.helpers.http.HttpResult;

/** list() должен возвращать полный достоверный снимок контролируемого контекста. */
public interface PlayerOperations {
    List<ObservedPlayerResponse> list();
    HttpResult delete(String id);
}
