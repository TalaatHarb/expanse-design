package net.talaatharb.expanse.desktop;

import net.talaatharb.expanse.connector.ConnectorConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ConnectorConfiguration.class)
public class DesktopConfiguration {
}
