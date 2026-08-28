package dev.fsp.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer configuration.
 *
 * <p>The allowed origins have to cover the deployed origin as well as the development one. Browsers
 * attach an {@code Origin} header to every request that is not a GET or HEAD, including same-origin
 * ones, and Spring checks that header against this list regardless of whether the request actually
 * crossed an origin. Listing only the dev server therefore breaks every write from the packaged UI
 * with a 403, while leaving reads working - a failure that looks like a broken button rather than
 * like a CORS problem.
 *
 * <p>Set {@code FSP_WEB_ALLOWED_ORIGINS} when serving the UI from a different host or port.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${fsp.web.allowed-origins:"
            + "http://localhost:3000,http://127.0.0.1:3000,"
            + "http://localhost:5173,http://127.0.0.1:5173}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins.clone();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE").allowedHeaders("*").maxAge(3600);
    }
}
