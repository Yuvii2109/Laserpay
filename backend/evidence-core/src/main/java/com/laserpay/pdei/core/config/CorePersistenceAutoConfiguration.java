package com.laserpay.pdei.core.config;

import com.laserpay.pdei.core.spi.AuditRepositoryPort;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.PolicyRepositoryPort;
import com.laserpay.pdei.core.spi.ReadinessRepositoryPort;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import com.laserpay.pdei.core.spi.jdbc.JdbcAuditRepository;
import com.laserpay.pdei.core.spi.jdbc.JdbcCaseRepository;
import com.laserpay.pdei.core.spi.jdbc.JdbcEvidenceRepository;
import com.laserpay.pdei.core.spi.jdbc.JdbcPolicyRepository;
import com.laserpay.pdei.core.spi.jdbc.JdbcReadinessRepository;
import com.laserpay.pdei.core.spi.jdbc.JdbcTransactionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * The default persistence adapters for the SPI ports, active whenever a {@code DataSource} is present.
 *
 * <p>Registered as its own auto-configuration, ordered before {@link CoreAutoConfiguration}, because
 * the domain services are {@code @ConditionalOnBean} on these ports: a nested member class inside
 * {@code CoreAutoConfiguration} would be processed after the outer class's own bean methods and the
 * conditions would evaluate against an empty registry.</p>
 *
 * <p>A service module that prefers Spring Data repositories or any other strategy simply declares its
 * own bean for a port; every bean here backs off on {@code @ConditionalOnMissingBean}. Setting
 * {@code pdei.core.jdbc.enabled=false} disables the whole set.</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.laserpay.pdei.persistence.config.PersistenceAutoConfiguration"
})
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "pdei.core.jdbc", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class CorePersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NamedParameterJdbcTemplate pdeiNamedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvidenceRepositoryPort pdeiEvidenceRepositoryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcEvidenceRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionRepositoryPort pdeiTransactionRepositoryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcTransactionRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyRepositoryPort pdeiPolicyRepositoryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcPolicyRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReadinessRepositoryPort pdeiReadinessRepositoryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcReadinessRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public CaseRepositoryPort pdeiCaseRepositoryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcCaseRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditRepositoryPort pdeiAuditRepositoryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAuditRepository(jdbc);
    }
}
