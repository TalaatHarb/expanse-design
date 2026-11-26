package net.talaatharb.expanse.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import net.talaatharb.expanse.connector.ConnectorConfiguration;
import net.talaatharb.expanse.connector.WorkbenchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ConnectorConfiguration.class)
public class McpApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(McpApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.run(args);
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(WorkbenchClient api, ObjectMapper mapper,
                            @Value("${expanse.mcp.allow-mutations:true}") boolean allowMutations,
                            @Value("${expanse.mcp.allow-destructive:false}") boolean allowDestructive) {
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper));
        var policy = new McpPolicy(allowMutations, allowDestructive);
        return McpServer.sync(transport).serverInfo("expanse-design", "0.2.0")
                .instructions("Expanse Design EDA workbench. Inspect with project.*, schematic.*, pcb.*, assembly.* "
                        + "and simulation.run. Every edit needs the current expectedRevision; prefer "
                        + "project.preview_changes before project.apply_changes for multi-step edits.")
                .tools(new WorkbenchTools(api, mapper, policy).specifications()).build();
    }
}
