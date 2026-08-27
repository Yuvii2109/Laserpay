package com.laserpay.pdei.api.config;

import com.laserpay.pdei.api.security.ServiceTokenFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Servlet filter chain, in order.
 *
 * <ol>
 *   <li>{@link CorrelationIdFilter} (HIGHEST_PRECEDENCE) so every later filter and every log line
 *       can name the same correlation id, including the ones that reject the request.</li>
 *   <li>{@link ServiceTokenFilter}, scoped to {@code /api/v1/ai-tools/*} only.</li>
 *   <li>{@link RateLimitFilter} for the merchant-facing routes.</li>
 * </ol>
 *
 * <p>The filters are registered here instead of being annotated {@code @Component} for two reasons:
 * the service-token filter needs an explicit URL pattern (a component filter is mapped to
 * {@code /*} and would 401 the whole API), and keeping them out of component scanning keeps the
 * MockMvc controller slice tests free of infrastructure they are not testing.</p>
 */
@Configuration(proxyBeanMethods = false)
public class WebFilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("pdeiCorrelationIdFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilterRegistration(
            ApiProperties properties) {
        FilterRegistrationBean<ServiceTokenFilter> registration =
                new FilterRegistrationBean<>(new ServiceTokenFilter(properties));
        registration.addUrlPatterns(ServiceTokenFilter.GUARDED_PREFIX + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("pdeiServiceTokenFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            ApiProperties properties, ObjectProvider<StringRedisTemplate> redisTemplates) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, redisTemplates));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("pdeiRateLimitFilter");
        return registration;
    }
}
