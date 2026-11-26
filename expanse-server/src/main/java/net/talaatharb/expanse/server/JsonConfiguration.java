package net.talaatharb.expanse.server;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictRequests() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                        StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(8_388_608)
                                .maxDocumentLength(9_437_184).build()));
    }
}
