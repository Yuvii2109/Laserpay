package com.laserpay.pdei.api.config;

import com.laserpay.pdei.api.support.CorrelationIds;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Next.js frontend (contract section 2: {@code pdei-web} on port 3000).
 *
 * <p>Only the configured origins are allowed and {@code X-Correlation-Id} is exposed so the browser
 * can read back the id the gateway assigned and show it next to an error. Wildcard origins are
 * deliberately not used: credentials are allowed, and the two are incompatible anyway.</p>
 *
 * <p>Declared as a {@code @Bean} of type {@link WebMvcConfigurer} rather than by implementing the
 * interface on the configuration class, so it stays out of {@code @WebMvcTest} slices.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    public WebMvcConfigurer pdeiCorsConfigurer(ApiProperties properties) {
        ApiProperties.Cors cors = properties.getCors();
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(cors.getAllowedOrigins().toArray(String[]::new))
                        .allowedMethods(cors.getAllowedMethods().toArray(String[]::new))
                        .allowedHeaders("*")
                        .exposedHeaders(exposed())
                        .allowCredentials(cors.isAllowCredentials())
                        .maxAge(cors.getMaxAge().toSeconds());
            }
        };
    }

    private static String[] exposed() {
        return List.of(
                CorrelationIds.HEADER,
                "X-RateLimit-Limit",
                "X-RateLimit-Remaining",
                "Location").toArray(String[]::new);
    }
}
