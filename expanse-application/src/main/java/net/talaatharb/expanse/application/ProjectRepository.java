package net.talaatharb.expanse.application;

import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.domain.Design.Project;

public interface ProjectRepository {
    Project get(UUID id);

    List<Project> list(int offset, int limit);

    void insert(Project project);

    void replace(Project project, long expectedRevision);

    void delete(UUID id, long expectedRevision);
}
