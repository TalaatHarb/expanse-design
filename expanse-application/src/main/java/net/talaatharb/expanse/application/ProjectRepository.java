package net.talaatharb.expanse.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Project;

public interface ProjectRepository {
    /** Number of recent revisions kept per project for undo/redo. */
    int HISTORY_LIMIT = 200;

    /** Undo/redo links of a stored revision; a null link means there is nothing to step to. */
    record History(Long undoTo, Long redoTo) {
        public static final History NONE = new History(null, null);
    }

    record StoredRevision(Project project, History history) {
    }

    Project get(UUID id);

    List<Project> list(int offset, int limit);

    /** Stores a new project; its first revision starts a fresh history. */
    void insert(Project project);

    /** Stores an ordinary edit: it can be undone to {@code expectedRevision} and clears the redo path. */
    default void replace(Project project, long expectedRevision) {
        replace(project, expectedRevision, new History(expectedRevision, null));
    }

    void replace(Project project, long expectedRevision, History history);

    /** A retained revision with its history links, if it has not been pruned. */
    Optional<StoredRevision> revision(UUID id, long revision);

    void delete(UUID id, long expectedRevision);
}
