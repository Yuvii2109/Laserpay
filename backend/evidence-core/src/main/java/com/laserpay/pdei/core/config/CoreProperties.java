package com.laserpay.pdei.core.config;

import com.laserpay.pdei.core.storage.Buckets;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the domain engine, bound from the {@code pdei.core} prefix.
 *
 * <p>Defaults are the platform contract values, so a service module that adds the dependency and
 * sets nothing behaves exactly as the contract specifies. Environment variables from contract 15
 * ({@code PDEI_MINIO_*}, {@code PDEI_AI_SERVICE_URL}, {@code PDEI_SERVICE_TOKEN}) are the intended
 * source for the deployment-specific values.</p>
 */
@ConfigurationProperties(prefix = "pdei.core")
public class CoreProperties {

    private final Storage storage = new Storage();
    private final Readiness readiness = new Readiness();
    private final Ai ai = new Ai();
    private final Safety safety = new Safety();
    private final Audit audit = new Audit();

    public Storage getStorage() {
        return storage;
    }

    public Readiness getReadiness() {
        return readiness;
    }

    public Ai getAi() {
        return ai;
    }

    public Safety getSafety() {
        return safety;
    }

    public Audit getAudit() {
        return audit;
    }

    /** MinIO connection and object layout. */
    public static class Storage {
        /** {@code PDEI_MINIO_ENDPOINT}. */
        private String endpoint = "http://minio:9000";
        /** {@code PDEI_MINIO_ACCESS_KEY}. */
        private String accessKey = "pdei-minio";
        /** {@code PDEI_MINIO_SECRET_KEY}. */
        private String secretKey = "pdei-minio-secret";
        private String region;
        private List<String> buckets = List.of(Buckets.EVIDENCE, Buckets.PACKAGES);
        /** Create missing buckets when the context starts. */
        private boolean ensureBucketsOnStartup = true;
        /** Object versioning on the evidence bucket (platform contract 11). */
        private boolean versioningEnabled = true;
        /** Lifetime of a presigned download URL. */
        private Duration presignTtl = Duration.ofMinutes(15);

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public List<String> getBuckets() {
            return buckets;
        }

        public void setBuckets(List<String> buckets) {
            this.buckets = buckets;
        }

        public boolean isEnsureBucketsOnStartup() {
            return ensureBucketsOnStartup;
        }

        public void setEnsureBucketsOnStartup(boolean ensureBucketsOnStartup) {
            this.ensureBucketsOnStartup = ensureBucketsOnStartup;
        }

        public boolean isVersioningEnabled() {
            return versioningEnabled;
        }

        public void setVersioningEnabled(boolean versioningEnabled) {
            this.versioningEnabled = versioningEnabled;
        }

        public Duration getPresignTtl() {
            return presignTtl;
        }

        public void setPresignTtl(Duration presignTtl) {
            this.presignTtl = presignTtl;
        }
    }

    /** Readiness scoring and gap detection. */
    public static class Readiness {
        /** Contract 7: expiry within this many days is EXPIRING_SOON. */
        private int expiringSoonDays = 7;
        /** Extraction quality below this floor raises a LOW_QUALITY gap. */
        private double lowQualityThreshold = 0.5d;
        /** TTL of {@code pdei:readiness:{transactionId}} (platform contract 12). */
        private Duration cacheTtl = Duration.ofMinutes(10);
        /** Batch size for the nightly expiry sweep. */
        private int sweepBatchSize = 500;

        public int getExpiringSoonDays() {
            return expiringSoonDays;
        }

        public void setExpiringSoonDays(int expiringSoonDays) {
            this.expiringSoonDays = expiringSoonDays;
        }

        public double getLowQualityThreshold() {
            return lowQualityThreshold;
        }

        public void setLowQualityThreshold(double lowQualityThreshold) {
            this.lowQualityThreshold = lowQualityThreshold;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public int getSweepBatchSize() {
            return sweepBatchSize;
        }

        public void setSweepBatchSize(int sweepBatchSize) {
            this.sweepBatchSize = sweepBatchSize;
        }
    }

    /** AI service connection, retry policy and admission control. */
    public static class Ai {
        /** {@code PDEI_AI_SERVICE_URL}. */
        private String serviceUrl = "http://ai-reasoning-service:8000";
        /** {@code PDEI_SERVICE_TOKEN}, sent as {@code X-PDEI-Service-Token}. */
        private String serviceToken = "dev-service-token";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(30);
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(500);
        private double backoffMultiplier = 2.0d;
        private int circuitFailureThreshold = 5;
        private Duration circuitOpenDuration = Duration.ofSeconds(60);
        /** Contract 9.4: admit when priority is at or above this. */
        private int priorityThreshold = 55;
        /** Dispute value at which the financial impact term saturates, in minor units. */
        private long financialImpactCapMinor = 10_000_000L;
        /** Contradictions plus gaps at which the ambiguity term saturates. */
        private int ambiguityCap = 8;
        /** {@code pdei:ai:budget:{date}} - maximum model calls per UTC day. */
        private long dailyBudget = 500L;
        /** {@code pdei:ai:bucket} - burst capacity. */
        private double bucketCapacity = 30.0d;
        /** {@code pdei:ai:bucket} - sustained refill rate, tokens per second. */
        private double bucketRefillPerSecond = 0.5d;

        public String getServiceUrl() {
            return serviceUrl;
        }

        public void setServiceUrl(String serviceUrl) {
            this.serviceUrl = serviceUrl;
        }

        public String getServiceToken() {
            return serviceToken;
        }

        public void setServiceToken(String serviceToken) {
            this.serviceToken = serviceToken;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public int getCircuitFailureThreshold() {
            return circuitFailureThreshold;
        }

        public void setCircuitFailureThreshold(int circuitFailureThreshold) {
            this.circuitFailureThreshold = circuitFailureThreshold;
        }

        public Duration getCircuitOpenDuration() {
            return circuitOpenDuration;
        }

        public void setCircuitOpenDuration(Duration circuitOpenDuration) {
            this.circuitOpenDuration = circuitOpenDuration;
        }

        public int getPriorityThreshold() {
            return priorityThreshold;
        }

        public void setPriorityThreshold(int priorityThreshold) {
            this.priorityThreshold = priorityThreshold;
        }

        public long getFinancialImpactCapMinor() {
            return financialImpactCapMinor;
        }

        public void setFinancialImpactCapMinor(long financialImpactCapMinor) {
            this.financialImpactCapMinor = financialImpactCapMinor;
        }

        public int getAmbiguityCap() {
            return ambiguityCap;
        }

        public void setAmbiguityCap(int ambiguityCap) {
            this.ambiguityCap = ambiguityCap;
        }

        public long getDailyBudget() {
            return dailyBudget;
        }

        public void setDailyBudget(long dailyBudget) {
            this.dailyBudget = dailyBudget;
        }

        public double getBucketCapacity() {
            return bucketCapacity;
        }

        public void setBucketCapacity(double bucketCapacity) {
            this.bucketCapacity = bucketCapacity;
        }

        public double getBucketRefillPerSecond() {
            return bucketRefillPerSecond;
        }

        public void setBucketRefillPerSecond(double bucketRefillPerSecond) {
            this.bucketRefillPerSecond = bucketRefillPerSecond;
        }
    }

    /** Thresholds applied by the safety gate on top of the hard validation rules. */
    public static class Safety {
        /** Below this confidence, an otherwise clean result still goes to a human. */
        private double unattendedConfidence = 0.95d;

        public double getUnattendedConfidence() {
            return unattendedConfidence;
        }

        public void setUnattendedConfidence(double unattendedConfidence) {
            this.unattendedConfidence = unattendedConfidence;
        }
    }

    /** Audit chain behaviour. */
    public static class Audit {
        /** TTL of the {@code pdei:lock:audit:{merchantId}} chain lock. */
        private Duration lockTtl = Duration.ofSeconds(30);
        /** Publish every audit entry to {@code pdei.audit.events.v1}. */
        private boolean publishToKafka = true;

        public Duration getLockTtl() {
            return lockTtl;
        }

        public void setLockTtl(Duration lockTtl) {
            this.lockTtl = lockTtl;
        }

        public boolean isPublishToKafka() {
            return publishToKafka;
        }

        public void setPublishToKafka(boolean publishToKafka) {
            this.publishToKafka = publishToKafka;
        }
    }
}
