package com.laserpay.pdei.api.config;

import com.laserpay.pdei.api.security.ServiceTokenFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 description of the contract section 8.1 surface, served at {@code /swagger-ui.html}
 * with the raw document at {@code /v3/api-docs}.
 *
 * <p>Two groups are published because the API has two audiences with different rules. The
 * {@code merchant} group is what the frontend consumes. The {@code ai-tools} group is what the
 * Python reasoner consumes; it is read-only by construction and requires the service token, and
 * splitting it out makes that boundary visible in the generated docs rather than only in code.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String SERVICE_TOKEN_SCHEME = "ServiceToken";

    @Bean
    public OpenAPI pdeiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PDEI api-gateway-service")
                        .version("v1")
                        .description("""
                                Pre-Dispute Evidence Intelligence merchant API.

                                Money is always (amountMinor, currency) in minor units: never format or
                                arithmetic it as a decimal. Timestamps are ISO-8601 UTC instants.
                                Routes under /api/v1/ai-tools are read-only and require the
                                X-PDEI-Service-Token header.""")
                        .contact(new Contact().name("PDEI platform"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("local"),
                        new Server().url("http://api-gateway-service:8080").description("docker network")))
                .tags(List.of(
                        new Tag().name("merchants").description("Merchant directory and control-tower KPIs"),
                        new Tag().name("transactions").description("Transactions, timeline, readiness, graph"),
                        new Tag().name("evidence").description("Evidence explorer, versions, lineage, upload"),
                        new Tag().name("disputes").description("Dispute lifecycle"),
                        new Tag().name("cases").description("Case queue, X-Ray, human decisions, package"),
                        new Tag().name("investigations").description("AI investigation results and findings"),
                        new Tag().name("policies").description("Versioned policies and requirement matrix"),
                        new Tag().name("audit").description("Hash-chained audit log"),
                        new Tag().name("gaps").description("At-risk evidence gap feed"),
                        new Tag().name("metrics").description("Funnel metrics"),
                        new Tag().name("stream").description("WebSocket and SSE streams"),
                        new Tag().name("ai-tools").description("Read-only fact lookups for ai-reasoning-service")))
                .components(new Components().addSecuritySchemes(SERVICE_TOKEN_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(ServiceTokenFilter.HEADER)
                                .description("Shared service token (PDEI_SERVICE_TOKEN). "
                                        + "Required on every /api/v1/ai-tools route.")));
    }

    @Bean
    public GroupedOpenApi merchantApi() {
        return GroupedOpenApi.builder()
                .group("merchant")
                .pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/ai-tools/**")
                .build();
    }

    @Bean
    public GroupedOpenApi aiToolsApi() {
        return GroupedOpenApi.builder()
                .group("ai-tools")
                .pathsToMatch("/api/v1/ai-tools/**")
                .build();
    }
}
