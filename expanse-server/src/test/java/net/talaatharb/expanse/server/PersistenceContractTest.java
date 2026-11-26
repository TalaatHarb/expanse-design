package net.talaatharb.expanse.server;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import net.talaatharb.expanse.application.ProjectJson;
import net.talaatharb.expanse.application.ProjectRepository;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.persistence.JdbcProjectRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistenceContractTest {
    @TempDir
    Path directory;

    @Test
    void fileDatabaseReopensWithoutChangingCanonicalJsonAndRejectsStaleWrites() {
        String url = "jdbc:h2:file:" + directory.resolve("project data").toAbsolutePath();
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var json = new ProjectJson();
        var repository = new JdbcProjectRepository(jdbc, json);
        Project project = Project.create("Persistent project");
        repository.insert(project);
        String before = json.write(repository.get(project.id()));
        jdbc.execute("SHUTDOWN");
        var reopened = new JdbcProjectRepository(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), json);
        assertThat(json.write(reopened.get(project.id()))).isEqualTo(before);
        var next = project.edited(project.components(), project.nets(), project.board());
        reopened.replace(next, 0);
        assertThatThrownBy(() -> reopened.replace(next, 0)).isInstanceOfSatisfying(DesignException.class,
                e -> assertThat(e.currentRevision()).isEqualTo(1));
        assertThatThrownBy(() -> reopened.insert(project)).isInstanceOfSatisfying(DesignException.class,
                e -> assertThat(e.code()).isEqualTo("PROJECT_EXISTS"));
        new JdbcTemplate(dataSource).execute("SHUTDOWN");
    }

    @Test
    void historyIsRecordedTransactionallyBackfilledForOlderProjectsAndDeletedWithTheProject() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:history;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var json = new ProjectJson();
        var repository = new JdbcProjectRepository(jdbc, json);
        Project project = Project.create("History");
        repository.insert(project);
        assertThat(repository.revision(project.id(), 0)).hasValueSatisfying(r ->
                assertThat(r.history()).isEqualTo(ProjectRepository.History.NONE));

        // A project written before V2 has no history row; the first edit records its base revision.
        jdbc.update("DELETE FROM project_revisions");
        var next = project.edited(project.components(), project.nets(), project.board());
        repository.replace(next, 0);
        assertThat(repository.revision(project.id(), 0)).isPresent();
        assertThat(repository.revision(project.id(), 1)).hasValueSatisfying(r -> {
            assertThat(r.history()).isEqualTo(new ProjectRepository.History(0L, null));
            assertThat(json.write(r.project())).isEqualTo(json.write(next));
        });

        assertThatThrownBy(() -> repository.replace(next.renamed("Stale"), 0))
                .isInstanceOf(DesignException.class);
        assertThat(repository.revision(project.id(), 2)).isEmpty();

        repository.delete(project.id(), 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_revisions", Integer.class)).isZero();
        jdbc.execute("SHUTDOWN");
    }
}
