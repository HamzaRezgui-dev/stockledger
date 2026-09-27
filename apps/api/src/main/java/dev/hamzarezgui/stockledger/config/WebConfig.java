package dev.hamzarezgui.stockledger.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Next.js client, which runs on a different origin (:3000) from
 * this API (:3001).
 *
 * <p>Origins come from configuration and are listed explicitly — never
 * {@code "*"}. This API will carry auth tokens, and a wildcard origin combined
 * with credentials is both forbidden by browsers and a genuine security hole.
 * Keeping the list in {@code application.yaml} means the deployed origin is a
 * config change, not a code change.
 */
@Configuration
@ConfigurationProperties(prefix = "stockledger.cors")
public class WebConfig implements WebMvcConfigurer {

    private List<String> allowedOrigins = List.of();

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE")
                .allowedHeaders("Content-Type", "Idempotency-Key", "X-Actor")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
