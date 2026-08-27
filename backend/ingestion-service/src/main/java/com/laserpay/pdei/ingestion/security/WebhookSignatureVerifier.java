package com.laserpay.pdei.ingestion.security;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies the HMAC signature on a source-specific webhook.
 *
 * <p>{@code POST /ingest/v1/events/{sourceSystem}/webhook} has no session, no bearer token and no
 * mutual TLS - it is called by third-party systems. The shared-secret HMAC over the exact bytes
 * received is therefore the entire trust boundary between the evidence graph and forged financial
 * events, which is why this class refuses on every ambiguity rather than guessing.
 *
 * <p><strong>Signed payload.</strong> {@code timestamp + "." + body} when a timestamp header is
 * present, otherwise the raw body alone. Binding the timestamp into the MAC is what makes it
 * tamper-proof, and comparing it against {@code ingestion.webhook.tolerance} is what turns a
 * captured request into a useless one after five minutes.
 *
 * <p><strong>Accepted header shapes.</strong> {@code sha256=<hex>}, Stripe-style
 * {@code t=<epoch>,v1=<hex>}, or bare hex. All are folded to a hex digest before comparison, so a
 * new source adapter rarely needs code here.
 *
 * <p><strong>Constant-time comparison.</strong> {@link MessageDigest#isEqual} over the raw digest
 * bytes. A byte-by-byte {@code String.equals} leaks how many leading characters were right, which
 * is enough to forge a signature one byte at a time.
 *
 * <p><strong>Disabling.</strong> {@code ingestion.webhook.signature-verification-enabled=false}
 * exists so the local demo can curl the endpoint. It logs at WARN on every call, because a
 * production deployment that got there by copying a dev profile should be noisy, not silent.
 */
@Component
public class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final HexFormat HEX = HexFormat.of();

    private final IngestionProperties properties;
    private final Clocks clock;

    public WebhookSignatureVerifier(IngestionProperties properties, Clocks clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.getWebhook().isSignatureVerificationEnabled();
    }

    /** True when a shared secret is registered for this source system. */
    public boolean hasSecretFor(String sourceSystem) {
        return secretFor(sourceSystem) != null;
    }

    /**
     * Verifies a webhook delivery.
     *
     * @param sourceSystem    path segment identifying the caller
     * @param body            the exact bytes received; re-serialising the parsed JSON would change
     *                        them and break every signature
     * @param signatureHeader value of {@code ingestion.webhook.signature-header}, may be null
     * @param timestampHeader value of {@code ingestion.webhook.timestamp-header}, may be null
     * @throws WebhookSignatureException when the delivery cannot be trusted
     */
    public void verify(String sourceSystem, byte[] body, String signatureHeader, String timestampHeader) {
        if (!isEnabled()) {
            log.warn("Webhook signature verification is DISABLED - accepting '{}' delivery unauthenticated. "
                    + "This must never be the case outside local development.", sourceSystem);
            return;
        }

        String secret = secretFor(sourceSystem);
        if (secret == null) {
            log.error("Rejected webhook from '{}': no shared secret registered under "
                    + "ingestion.webhook.secrets", sourceSystem);
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Rejected webhook from '{}': missing {} header",
                    sourceSystem, properties.getWebhook().getSignatureHeader());
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }

        String timestamp = resolveTimestamp(signatureHeader, timestampHeader);
        assertFresh(sourceSystem, timestamp);

        byte[] payload = signedPayload(body, timestamp);
        byte[] expected = mac(sourceSystem, secret, payload);
        byte[] provided = decodeSignature(sourceSystem, signatureHeader);

        if (!MessageDigest.isEqual(expected, provided)) {
            log.warn("Rejected webhook from '{}': signature mismatch over {} bytes (timestamp {})",
                    sourceSystem, payload.length, timestamp == null ? "absent" : "present");
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
        log.debug("Verified webhook signature from '{}'", sourceSystem);
    }

    /**
     * Computes the signature a source should send for these bytes. Used by tests and by the local
     * simulator; there is no production caller, and there must never be one that signs on behalf of
     * an external system.
     */
    public String sign(String sourceSystem, byte[] body, String timestamp) {
        String secret = secretFor(sourceSystem);
        if (secret == null) {
            throw new IllegalStateException("No webhook secret registered for source '" + sourceSystem + "'");
        }
        return "sha256=" + HEX.formatHex(mac(sourceSystem, secret, signedPayload(body, timestamp)));
    }

    // --- internals ------------------------------------------------------------------------

    private String secretFor(String sourceSystem) {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            return null;
        }
        var secrets = properties.getWebhook().getSecrets();
        String secret = secrets.get(sourceSystem);
        if (secret == null) {
            // Relaxed key matching: compose and Kubernetes both mangle map keys
            // (PDEI_WEBHOOK_SECRETS_PSP_ADAPTER -> psp-adapter / pspadapter).
            String needle = canonical(sourceSystem);
            for (var entry : secrets.entrySet()) {
                if (canonical(entry.getKey()).equals(needle)) {
                    secret = entry.getValue();
                    break;
                }
            }
        }
        return secret == null || secret.isBlank() ? null : secret;
    }

    private static String canonical(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private byte[] signedPayload(byte[] body, String timestamp) {
        byte[] safeBody = body == null ? new byte[0] : body;
        if (timestamp == null || timestamp.isBlank()) {
            return safeBody;
        }
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + safeBody.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(safeBody, 0, out, prefix.length, safeBody.length);
        return out;
    }

    private byte[] mac(String sourceSystem, String secret, byte[] payload) {
        String algorithm = properties.getWebhook().getAlgorithm();
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return mac.doFinal(payload);
        } catch (Exception e) {
            log.error("Cannot compute {} for source '{}': {}", algorithm, sourceSystem, e.toString());
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
    }

    /**
     * Extracts the hex digest from any of the accepted header shapes and decodes it. A header that
     * is not decodable hex is a failed verification, not a 500.
     */
    private byte[] decodeSignature(String sourceSystem, String header) {
        String candidate = null;
        for (String part : header.split("[,;\\s]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            String value = eq >= 0 ? token.substring(eq + 1) : token;
            String label = eq >= 0 ? token.substring(0, eq).toLowerCase(Locale.ROOT) : "";
            if ("t".equals(label) || "timestamp".equals(label)) {
                continue;
            }
            if (isHex(value)) {
                candidate = value;
            }
        }
        if (candidate == null) {
            log.warn("Rejected webhook from '{}': {} header carries no hex digest",
                    sourceSystem, properties.getWebhook().getSignatureHeader());
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
        try {
            return HEX.parseHex(candidate.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
    }

    /** Prefers the dedicated timestamp header, falling back to a Stripe-style {@code t=} element. */
    private static String resolveTimestamp(String signatureHeader, String timestampHeader) {
        if (timestampHeader != null && !timestampHeader.isBlank()) {
            return timestampHeader.trim();
        }
        for (String part : signatureHeader.split("[,;\\s]+")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.startsWith("t=") && token.length() > 2) {
                return part.trim().substring(2);
            }
        }
        return null;
    }

    /**
     * Replay protection. A signature is only as good as the window it is valid in; without this a
     * captured delivery could be replayed for as long as the secret lives.
     */
    private void assertFresh(String sourceSystem, String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return;
        }
        Instant at = parseTimestamp(timestamp);
        if (at == null) {
            log.warn("Rejected webhook from '{}': unparseable timestamp '{}'", sourceSystem, timestamp);
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
        Duration tolerance = properties.getWebhook().getTolerance();
        Duration skew = Duration.between(at, clock.now()).abs();
        if (tolerance != null && skew.compareTo(tolerance) > 0) {
            log.warn("Rejected webhook from '{}': timestamp skew {}s exceeds tolerance {}s (replay?)",
                    sourceSystem, skew.toSeconds(), tolerance.toSeconds());
            throw new WebhookSignatureException(sourceSystem, "webhook signature verification failed");
        }
    }

    /** Accepts epoch seconds, epoch milliseconds, or an ISO-8601 instant. Never {@code LocalDateTime}. */
    static Instant parseTimestamp(String value) {
        String trimmed = value.trim();
        try {
            if (trimmed.chars().allMatch(Character::isDigit)) {
                long numeric = Long.parseLong(trimmed);
                return trimmed.length() >= 13 ? Instant.ofEpochMilli(numeric) : Instant.ofEpochSecond(numeric);
            }
            return Instant.parse(trimmed);
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isHex(String value) {
        if (value == null || value.length() < 32 || value.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
