package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Merchant tenant root ({@code MER-} ids). Owner of every transaction, evidence item and dispute. */
@Entity
@Table(name = "merchants", schema = PdeiSchema.NAME)
public class MerchantEntity extends VersionedEntity {

    @Id
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String id;

    @Column(name = "legal_name", nullable = false, length = 256)
    private String legalName;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "default_currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String defaultCurrency;

    @Column(name = "mcc", length = 8)
    private String mcc;

    /** ACTIVE | SUSPENDED | CLOSED (CHECK-constrained; no shared enum is defined by the contract). */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "contact_email", length = 256)
    private String contactEmail;

    /** Historical representment win rate in basis points (7100 = 71%). Integer, never a float. */
    @Column(name = "baseline_win_rate_bps")
    private Integer baselineWinRateBps;

    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_profile", columnDefinition = "jsonb")
    private Map<String, Object> riskProfile;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public String getMcc() {
        return mcc;
    }

    public void setMcc(String mcc) {
        this.mcc = mcc;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public Integer getBaselineWinRateBps() {
        return baselineWinRateBps;
    }

    public void setBaselineWinRateBps(Integer baselineWinRateBps) {
        this.baselineWinRateBps = baselineWinRateBps;
    }

    public Instant getOnboardedAt() {
        return onboardedAt;
    }

    public void setOnboardedAt(Instant onboardedAt) {
        this.onboardedAt = onboardedAt;
    }

    public Map<String, Object> getRiskProfile() {
        return riskProfile;
    }

    public void setRiskProfile(Map<String, Object> riskProfile) {
        this.riskProfile = riskProfile;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
