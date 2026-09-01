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
            // ...and the reverse, which is the case that actually bit. A record component absent
            // from stored JSON is handed to the canonical constructor as null, so adding a single
            // `int` field to a spec type made every system saved before it unreadable - a 400 on
            // open and on save, with nothing in the message to say which field was missing. Taking
            // the type's default instead lets the record's own compact constructor decide what an
            // unset value means, which is where that decision belongs.
            builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        };
    }
}
