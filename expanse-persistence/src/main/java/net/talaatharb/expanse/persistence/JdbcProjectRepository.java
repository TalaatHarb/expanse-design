package net.talaatharb.expanse.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.talaatharb.expanse.application.ProjectJson;
import net.talaatharb.expanse.application.ProjectRepository;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores the latest document of each project in {@code projects} and a bounded revision history in
 * {@code project_revisions}. Writes to both tables happen in one transaction.
 */
public final class JdbcProjectRepository implements ProjectRepository {
    private final JdbcTemplate jdbc;
    private final ProjectJson json;
    private final TransactionTemplate transaction;

    public JdbcProjectRepository(JdbcTemplate jdbc, ProjectJson json) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
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
        String document = json.write(project);
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO projects (id, revision, name, document) VALUES (?, ?, ?, ?)",
                        project.id().toString(), project.revision(), project.name(), document);
                record(project.id(), project.revision(), document, History.NONE);
            });
        } catch (DuplicateKeyException e) {
            throw new DesignException("PROJECT_EXISTS", "Import cannot overwrite an existing project: " + project.id());
        }
    }

    @Override
    public void replace(Project project, long expectedRevision, History history) {
        String document = json.write(project);
        String id = project.id().toString();
        boolean updated = Boolean.TRUE.equals(transaction.execute(status -> {
            // Projects stored before history existed get their current revision recorded so it can be undone to.
            jdbc.update("INSERT INTO project_revisions (project_id, revision, document, undo_to, redo_to) "
                            + "SELECT id, revision, document, NULL, NULL FROM projects p WHERE id = ? AND revision = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM project_revisions r WHERE r.project_id = p.id "
                            + "AND r.revision = p.revision)", id, expectedRevision);
            int rows = jdbc.update("UPDATE projects SET revision = ?, name = ?, document = ? WHERE id = ? AND revision = ?",
                    project.revision(), project.name(), document, id, expectedRevision);
            if (rows != 1) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("DELETE FROM project_revisions WHERE project_id = ? AND revision >= ?", id, project.revision());
            record(project.id(), project.revision(), document, history);
            jdbc.update("DELETE FROM project_revisions WHERE project_id = ? AND revision <= ?", id,
                    project.revision() - HISTORY_LIMIT);
            return true;
        }));
        if (!updated) {
            throw new DesignException("REVISION_CONFLICT", "The project changed; refresh before editing",
                    get(project.id()).revision());
        }
    }

    @Override
    public Optional<StoredRevision> revision(UUID id, long revision) {
        return jdbc.query("SELECT document, undo_to, redo_to FROM project_revisions WHERE project_id = ? AND revision = ?",
                (rs, row) -> new StoredRevision(json.read(rs.getString(1)),
                        new History(rs.getObject(2, Long.class), rs.getObject(3, Long.class))),
                id.toString(), revision).stream().findFirst();
    }

    @Override
    public void delete(UUID id, long expectedRevision) {
        int deleted = jdbc.update("DELETE FROM projects WHERE id = ? AND revision = ?", id.toString(), expectedRevision);
        if (deleted != 1) {
            throw new DesignException("REVISION_CONFLICT", "The project changed; refresh before deleting",
                    get(id).revision());
        }
    }

    private void record(UUID id, long revision, String document, History history) {
        jdbc.update("INSERT INTO project_revisions (project_id, revision, document, undo_to, redo_to) "
                + "VALUES (?, ?, ?, ?, ?)", id.toString(), revision, document, history.undoTo(), history.redoTo());
    }
}
