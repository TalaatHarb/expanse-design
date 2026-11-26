package net.talaatharb.expanse.server;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import net.talaatharb.expanse.application.ProjectJson;
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
}
