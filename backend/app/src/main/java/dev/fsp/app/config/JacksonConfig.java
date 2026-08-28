package dev.fsp.app.config;

import dev.fsp.app.json.EngineJson;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/** Wires the engine's type mappings into the application's JSON mapper. */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer engineTypesCustomizer() {
        return builder -> {
            EngineJson.configure(builder);
            // Stored specs outlive the code that wrote them: an older version of the app should
            // skip a field it does not recognise rather than refuse to load a system.
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }
}
