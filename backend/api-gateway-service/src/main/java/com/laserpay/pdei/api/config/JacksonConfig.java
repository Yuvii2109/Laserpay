package com.laserpay.pdei.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.common.json.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * The gateway serialises with {@link Json#mapper()}, the same mapper every other PDEI service and
 * every Kafka payload uses.
 *
 * <p>This matters more than it looks. The wire shapes in this API are the evidence-core model
 * records, and those same records travel over Kafka and into the Python service. Configuring a
 * second, independently tuned mapper for HTTP would let the two drift: an {@code Instant} written as
 * an epoch number here and as ISO-8601 there, a null included in one and dropped in the other. One
 * mapper, one shape, everywhere.</p>
 *
 * <p>What {@code Json.mapper()} guarantees: {@code JavaTimeModule}, ISO-8601 timestamps rather than
 * numeric ones, {@code NON_NULL} inclusion, and tolerance of unknown properties on the way in so a
 * newer producer never breaks an older consumer.</p>
 *
 * <p>Declaring an {@code ObjectMapper} bean backs Spring Boot's {@code JacksonAutoConfiguration}
 * off, which is the intent: no {@code spring.jackson.*} property can silently re-tune the shared
 * mapper from a config file.</p>
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return Json.mapper();
    }

    @Bean
    public MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter(ObjectMapper mapper) {
        return new MappingJackson2HttpMessageConverter(mapper);
    }
}
