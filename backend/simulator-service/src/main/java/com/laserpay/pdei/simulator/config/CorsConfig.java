package com.laserpay.pdei.simulator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Next.js console (platform contract 14: {@code /simulation}).
 *
 * <p>The simulator sits <em>beside</em> the gateway rather than behind it: the browser derives
 * {@code http://localhost:8088/sim/v1} from the gateway origin and calls this service directly,
 * so every call from {@code /simulation} is cross-origin and needs an explicit grant here. The
 * JSON posts ({@code /runs}, {@code /chaos}, {@code /replay}) additionally send
 * {@code Content-Type}, {@code Idempotency-Key} and {@code X-Correlation-Id}, each of which
 * triggers a preflight {@code OPTIONS} that must be answered with matching allowances - hence
 * {@code allowedHeaders("*")}.
 *
 * <p>Mirrors {@code api-gateway-service}'s {@code CorsConfig}: named origins only (never a
 * wildcard), a bounded method set, and the mapping scoped to this service's REST prefix so the
 * actuator surface stays browser-unreachable. Credentials are off by default because the client
 * sends {@code credentials: 'same-origin'} and this service has no session to carry.
 *
 * <p>Declared as a {@code @Bean} of type {@link WebMvcConfigurer} rather than by implementing the
 * interface on the configuration class, so it stays out of {@code @WebMvcTest} slices.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    public WebMvcConfigurer simulatorCorsConfigurer(SimulatorProperties properties) {
        SimulatorProperties.Cors cors = properties.getCors();
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/sim/**")
                        .allowedOrigins(cors.getAllowedOrigins().toArray(String[]::new))
                        .allowedMethods(cors.getAllowedMethods().toArray(String[]::new))
                        .allowedHeaders("*")
                        .allowCredentials(cors.isAllowCredentials())
                        .maxAge(cors.getMaxAge().toSeconds());
            }
        };
    }
}
