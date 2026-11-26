package net.talaatharb.expanse.server;

import net.talaatharb.expanse.application.ProjectJson;
import net.talaatharb.expanse.application.ProjectRepository;
import net.talaatharb.expanse.application.ProjectService;
import net.talaatharb.expanse.library.ComponentLibrary;
import net.talaatharb.expanse.persistence.JdbcProjectRepository;
import net.talaatharb.expanse.schematic.SchematicEditor;
import net.talaatharb.expanse.validation.DesignValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class ServiceConfiguration {
    @Bean
    ProjectJson projectJson() {
        return new ProjectJson();
    }

    @Bean
    ProjectRepository projectRepository(JdbcTemplate jdbc, ProjectJson json) {
        return new JdbcProjectRepository(jdbc, json);
    }

    @Bean
    ProjectService projectService(ProjectRepository repository, ProjectJson json) {
        return new ProjectService(repository, json, new ComponentLibrary(), new SchematicEditor(),
                new DesignValidator());
    }
}
