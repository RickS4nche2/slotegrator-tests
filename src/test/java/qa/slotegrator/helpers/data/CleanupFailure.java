package qa.slotegrator.helpers.data;

import java.util.List;

import lombok.Getter;

@Getter
public final class CleanupFailure extends RuntimeException {
    public record Problem(String operation, String id, String errorType) {
    }

    private final List<Problem> problems;
    private final List<String> unresolvedIds;

    public CleanupFailure(List<Problem> problems, List<String> unresolvedIds) {
        super("Очистка не подтверждена: проблемы=" + problems + ", ID=" + unresolvedIds);
        this.problems = List.copyOf(problems);
        this.unresolvedIds = List.copyOf(unresolvedIds);
    }
}
