package net.talaatharb.expanse.persistence;

import java.util.List;
import java.util.UUID;
import net.talaatharb.expanse.application.ProjectJson;
import net.talaatharb.expanse.application.ProjectRepository;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcProjectRepository implements ProjectRepository {
    private final JdbcTemplate jdbc;
    private final ProjectJson json;

    public JdbcProjectRepository(JdbcTemplate jdbc, ProjectJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Project get(UUID id) {
        var projects = jdbc.query("SELECT document FROM projects WHERE id = ?",
                (rs, row) -> json.read(rs.getString(1)), id.toString());
        if (projects.isEmpty()) {
            throw new DesignException("PROJECT_NOT_FOUND", "Project not found: " + id);
        }
        return projects.getFirst();
    }

    @Override
    public List<Project> list(int offset, int limit) {
        return jdbc.query("SELECT document FROM projects ORDER BY id LIMIT ? OFFSET ?",
                (rs, row) -> json.read(rs.getString(1)), limit, offset);
    }

    @Override
    public void insert(Project project) {
        try {
            jdbc.update("INSERT INTO projects (id, revision, name, document) VALUES (?, ?, ?, ?)",
                    project.id().toString(), project.revision(), project.name(), json.write(project));
        } catch (DuplicateKeyException e) {
            throw new DesignException("PROJECT_EXISTS", "Import cannot overwrite an existing project: " + project.id());
        }
    }

    @Override
    public void replace(Project project, long expectedRevision) {
        int updated = jdbc.update("UPDATE projects SET revision = ?, name = ?, document = ? WHERE id = ? AND revision = ?",
                project.revision(), project.name(), json.write(project), project.id().toString(), expectedRevision);
        if (updated != 1) {
            throw new DesignException("REVISION_CONFLICT", "The project changed; refresh before editing",
                    get(project.id()).revision());
        }
    }

    @Override
    public void delete(UUID id, long expectedRevision) {
        int deleted = jdbc.update("DELETE FROM projects WHERE id = ? AND revision = ?", id.toString(), expectedRevision);
        if (deleted != 1) {
            throw new DesignException("REVISION_CONFLICT", "The project changed; refresh before deleting",
                    get(id).revision());
        }
    }
}