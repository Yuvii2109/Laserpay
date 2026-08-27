package com.laserpay.pdei.ingestion.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything tunable about ingestion, bound from the {@code ingestion.*} block of
 * {@code application.yml} (and therefore overridable by environment variables in compose/k8s).
 *
 * <p>Validated at startup: a nonsense batch cap or a missing webhook signature header should fail
 * the service immediately rather than at the first request.
 */
@Validated
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    private final Batch batch = new Batch();
    private final Schemas schemas = new Schemas();
    private final Dedupe dedupe = new Dedupe();
    private final Publisher publisher = new Publisher();
    private final Webhook webhook = new Webhook();

    public Batch getBatch() {
        return batch;
    }

    public Schemas getSchemas() {
        return schemas;
    }

    public Dedupe getDedupe() {
        return dedupe;
    }

    public Publisher getPublisher() {
        return publisher;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    // ---------------------------------------------------------------------------------------

    /** Limits on {@code POST /ingest/v1/events/batch}. */
    public static class Batch {

        /**
         * Maximum events in one batch (PLATFORM-CONTRACT section 8.2: "array, max 1000"). Exceeding
         * it is a 400, not a partial accept: a caller that oversends must learn about it, and
         * silently truncating a financial event batch is indefensible.
         */
        @Min(1)
        @Max(10_000)
        private int maxSize = 1000;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Where the JSON Schemas come from and how strictly they are applied. */
    public static class Schemas {

        /**
         * Classpath pattern for the schemas bundled in the jar (copied from {@code /schemas/events}
         * at build time by the {@code <resources>} block in this module's POM).
         */
        @NotBlank
        private String classpathLocation = "classpath*:schemas/events/*.schema.json";

        /**
         * Filesystem directories scanned after the classpath. A schema found here <em>overrides</em>
         * the bundled one with the same key, so a new source schema can be registered by dropping a
         * file into the mounted volume and restarting the service - no image rebuild.
         */
        private List<String> directories = new ArrayList<>(List.of("/schemas/events"));

        /** Validate the submission envelope against {@code raw-event.schema.json}. */
        private boolean validateEnvelope = true;

        /**
         * When true, a source event type with no registered schema is rejected. Default false:
         * ingestion's job is to preserve facts for replay, and normalization-worker is the component
         * that owns the source-to-canonical mapping and dead-letters what it cannot map. Turn this on
         * for a locked-down environment where every adapter is expected to be registered.
         */
        private boolean failOnUnknownEventType = false;

        /**
         * Source-vocabulary aliases, e.g. {@code payment_intent.succeeded -> PaymentCaptured}.
         * Keys are matched case- and separator-insensitively.
         */
        private Map<String, String> aliases = new LinkedHashMap<>();

        public String getClasspathLocation() {
            return classpathLocation;
        }

        public void setClasspathLocation(String classpathLocation) {
            this.classpathLocation = classpathLocation;
        }

        public List<String> getDirectories() {
            return directories;
        }

        public void setDirectories(List<String> directories) {
            this.directories = directories == null ? new ArrayList<>() : directories;
        }

        public boolean isValidateEnvelope() {
            return validateEnvelope;
        }

        public void setValidateEnvelope(boolean validateEnvelope) {
            this.validateEnvelope = validateEnvelope;
        }

        public boolean isFailOnUnknownEventType() {
            return failOnUnknownEventType;
        }

        public void setFailOnUnknownEventType(boolean failOnUnknownEventType) {
            this.failOnUnknownEventType = failOnUnknownEventType;
        }

        public Map<String, String> getAliases() {
            return aliases;
        }

        public void setAliases(Map<String, String> aliases) {
            this.aliases = aliases == null ? new LinkedHashMap<>() : aliases;
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Idempotency behaviour (PLATFORM-CONTRACT section 12: {@code pdei:idem:{eventId}}, TTL 7d). */
    public static class Dedupe {

        private boolean enabled = true;

        /** Redis key prefix. Changing it re-opens the dedupe window for everything in flight. */
        @NotBlank
        private String keyPrefix = "pdei:idem:";

        /** Must match the retention of {@code processed_events} rows. */
        @NotNull
        private Duration ttl = Duration.ofDays(7);

        /** Fast path. Disable to exercise the Postgres path in tests. */
        private boolean redisEnabled = true;

        /** Durable path: {@code ProcessedEventRepository.markProcessed}. */
        private boolean postgresFallbackEnabled = true;

        /**
         * What to do when <em>both</em> stores are unavailable. Default true (accept the event):
         * every downstream consumer is idempotent by contract (rule 9), so an extra duplicate on the
         * topic is recoverable, whereas a dropped payment event is not.
         */
        private boolean failOpen = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        public boolean isPostgresFallbackEnabled() {
            return postgresFallbackEnabled;
        }

        public void setPostgresFallbackEnabled(boolean postgresFallbackEnabled) {
            this.postgresFallbackEnabled = postgresFallbackEnabled;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Kafka publication settings. */
    public static class Publisher {

        /**
         * How long a synchronous send may take before the event is reported as rejected. Ingestion
         * sends synchronously on purpose: the HTTP response reports what actually reached Kafka, so
         * {@code accepted} means accepted, not enqueued.
         */
        @NotNull
        private Duration sendTimeout = Duration.ofSeconds(10);

        /** Publish a {@code DeadLetterEnvelope} to {@code pdei.dlq.v1} when the send fails. */
        private boolean dlqEnabled = true;

        /** Declare the produced topics on startup so a fresh dev cluster works out of the box. */
        private boolean createTopics = true;

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        public boolean isDlqEnabled() {
            return dlqEnabled;
        }

        public void setDlqEnabled(boolean dlqEnabled) {
            this.dlqEnabled = dlqEnabled;
        }

        public boolean isCreateTopics() {
            return createTopics;
        }

        public void setCreateTopics(boolean createTopics) {
            this.createTopics = createTopics;
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Per-source webhook intake (PLATFORM-CONTRACT section 8.2). */
    public static class Webhook {

        /**
         * HMAC verification switch. <strong>Must stay true outside local development.</strong>
         * {@code POST /events/{sourceSystem}/webhook} is an unauthenticated endpoint; the signature
         * is the only thing standing between the evidence graph and forged financial events.
         */
        private boolean signatureVerificationEnabled = true;

        @NotBlank
        private String signatureHeader = "X-PDEI-Signature";

        /** Optional replay-protection header; when present it is part of the signed payload. */
        @NotBlank
        private String timestampHeader = "X-PDEI-Timestamp";

        /** Header carrying the source's own event type when the body does not name it. */
        @NotBlank
        private String eventTypeHeader = "X-PDEI-Event-Type";

        /** Header carrying the merchant when the body does not name it. */
        @NotBlank
        private String merchantHeader = "X-PDEI-Merchant-Id";

        /** JCA MAC algorithm. */
        @NotBlank
        private String algorithm = "HmacSHA256";

        /** Maximum clock skew accepted on a signed timestamp. */
        @NotNull
        private Duration tolerance = Duration.ofMinutes(5);

        /**
         * Shared secret per {@code sourceSystem} path segment. A source with no registered secret is
         * rejected outright - an unknown signer is not a trusted signer.
         */
        private Map<String, String> secrets = new LinkedHashMap<>();

        public boolean isSignatureVerificationEnabled() {
            return signatureVerificationEnabled;
        }

        public void setSignatureVerificationEnabled(boolean signatureVerificationEnabled) {
            this.signatureVerificationEnabled = signatureVerificationEnabled;
        }

        public String getSignatureHeader() {
            return signatureHeader;
        }

        public void setSignatureHeader(String signatureHeader) {
            this.signatureHeader = signatureHeader;
        }

        public String getTimestampHeader() {
            return timestampHeader;
        }

        public void setTimestampHeader(String timestampHeader) {
            this.timestampHeader = timestampHeader;
        }

        public String getEventTypeHeader() {
            return eventTypeHeader;
        }

        public void setEventTypeHeader(String eventTypeHeader) {
            this.eventTypeHeader = eventTypeHeader;
        }

        public String getMerchantHeader() {
            return merchantHeader;
        }

        public void setMerchantHeader(String merchantHeader) {
            this.merchantHeader = merchantHeader;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public Duration getTolerance() {
            return tolerance;
        }

        public void setTolerance(Duration tolerance) {
            this.tolerance = tolerance;
        }

        public Map<String, String> getSecrets() {
            return secrets;
        }

        public void setSecrets(Map<String, String> secrets) {
            this.secrets = secrets == null ? new LinkedHashMap<>() : secrets;
        }
    }
}
