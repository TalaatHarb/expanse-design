package net.talaatharb.expanse.server;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import net.talaatharb.expanse.dto.ApiModels.Operation;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes the REST API for springdoc; every {@code /api/v1} call needs the owner or read-only bearer token. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    static final String BEARER = "bearerToken";

    @Bean
    OpenAPI expanseOpenApi(ObjectProvider<BuildProperties> build) {
        var properties = build.getIfAvailable();
        return new OpenAPI()
                .info(new Info().title("Expanse Design API")
                        .version(properties == null ? "development" : properties.getVersion())
                        .description("Projects, transactional change sets, undo/redo, validation, simulation, "
                                + "PCB and 3D assembly. The desktop client, the MCP bridge and scripts all use this API.")
                        .license(new License().name("Apache License, Version 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("EXPANSE_API_TOKEN (read/write) or EXPANSE_READ_TOKEN (read-only)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Maps each change-set {@code op} name to its schema so generated clients and agents can pick the right shape. */
    @Bean
    OpenApiCustomizer operationDiscriminator() {
        return api -> {
            var schemas = api.getComponents() == null ? null : api.getComponents().getSchemas();
            var operation = schemas == null ? null : schemas.get("Operation");
            if (operation == null || operation.getDiscriminator() == null) {
                return;
            }
            for (JsonSubTypes.Type type : Operation.class.getAnnotation(JsonSubTypes.class).value()) {
                operation.getDiscriminator().mapping(type.name(), "#/components/schemas/" + type.value().getSimpleName());
            }
        };
    }
}
