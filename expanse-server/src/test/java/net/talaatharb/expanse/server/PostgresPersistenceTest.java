package net.talaatharb.expanse.server;

import static org.assertj.core.api.Assertions.*;

import net.talaatharb.expanse.application.ProjectJson;
import net.talaatharb.expanse.domain.Design.Project;
import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.persistence.JdbcProjectRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfSystemProperty(named = "expanse.postgres.tests", matches = "true")
class PostgresPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Test
    void sameMigrationAndCompareAndSwapWorkOnPostgres() {
        var dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        var repository = new JdbcProjectRepository(new JdbcTemplate(dataSource), new ProjectJson());
        var project = Project.create("PostgreSQL project");
        repository.insert(project);
        assertThat(repository.get(project.id())).isEqualTo(project);
        var next = project.edited(project.components(), project.nets(), project.board());
        repository.replace(next, 0);
        assertThatThrownBy(() -> repository.replace(next, 0)).isInstanceOf(DesignException.class);
        assertThat(repository.list(0, 100)).containsExactly(next);
    }
}
