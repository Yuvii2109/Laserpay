package com.laserpay.pdei.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The trust boundary in isolation: header-shape tolerance, replay window, and the fact that a
 * rejection never explains itself to the caller.
 */
class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:15:30Z");
    private static final byte[] BODY = "{\"paymentId\":\"PAY-1\"}".getBytes(StandardCharsets.UTF_8);

    private IngestionProperties properties;
    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new IngestionProperties();
        properties.getWebhook().setSecrets(new java.util.LinkedHashMap<>(
                Map.of("psp-adapter", "test-secret")));
        verifier = new WebhookSignatureVerifier(properties, Clocks.fixed(NOW));
    }

    @Test
    @DisplayName("accepts its own signature, with and without a timestamp")
    void acceptsValidSignatures() {
        String unsigned = verifier.sign("psp-adapter", BODY, null);
        assertThatCode(() -> verifier.verify("psp-adapter", BODY, unsigned, null)).doesNotThrowAnyException();

        String timestamp = NOW.toString();
        String signed = verifier.sign("psp-adapter", BODY, timestamp);
        assertThatCode(() -> verifier.verify("psp-adapter", BODY, signed, timestamp))
                .doesNotThrowAnyException();

        // The two are different signatures: the timestamp is bound into the MAC, which is what
        // makes the replay window tamper-proof.
        assertThat(signed).isNotEqualTo(unsigned);
    }

    @Test
    @DisplayName("accepts a bare hex digest and a Stripe-style t=,v1= header")
    void toleratesHeaderShapes() {
        String bareHex = verifier.sign("psp-adapter", BODY, null).substring("sha256=".length());
        assertThatCode(() -> verifier.verify("psp-adapter", BODY, bareHex, null)).doesNotThrowAnyException();

        long epochSeconds = NOW.getEpochSecond();
        String stripeStyle = "t=" + epochSeconds + ",v1="
                + verifier.sign("psp-adapter", BODY, Long.toString(epochSeconds)).substring("sha256=".length());
        assertThatCode(() -> verifier.verify("psp-adapter", BODY, stripeStyle, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a body altered by a single byte fails")
    void rejectsTamperedBody() {
        String signature = verifier.sign("psp-adapter", BODY, null);
        byte[] tampered = "{\"paymentId\":\"PAY-2\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify("psp-adapter", tampered, signature, null))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("a signature made with another secret fails")
    void rejectsForeignSecret() {
        properties.getWebhook().getSecrets().put("other", "another-secret");
        String foreign = verifier.sign("other", BODY, null);

        assertThatThrownBy(() -> verifier.verify("psp-adapter", BODY, foreign, null))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("a timestamp outside the tolerance window is a replay and is refused")
    void rejectsStaleTimestamp() {
        properties.getWebhook().setTolerance(Duration.ofMinutes(5));
        String stale = NOW.minus(Duration.ofMinutes(30)).toString();
        String signature = verifier.sign("psp-adapter", BODY, stale);

        assertThatThrownBy(() -> verifier.verify("psp-adapter", BODY, signature, stale))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("an unregistered source is refused before any comparison happens")
    void rejectsUnregisteredSource() {
        assertThat(verifier.hasSecretFor("nobody")).isFalse();
        assertThatThrownBy(() -> verifier.verify("nobody", BODY, "sha256=" + "a".repeat(64), null))
                .isInstanceOf(WebhookSignatureException.class)
                .hasMessageNotContainingAny("secret", "registered");
    }

    @Test
    @DisplayName("a garbage signature header is a 401, not a crash")
    void rejectsGarbageHeader() {
        assertThatThrownBy(() -> verifier.verify("psp-adapter", BODY, "not-a-signature", null))
                .isInstanceOf(WebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("psp-adapter", BODY, "", null))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("verification can be switched off for local development, and nothing else changes")
    void canBeDisabledForDevelopment() {
        properties.getWebhook().setSignatureVerificationEnabled(false);

        assertThat(verifier.isEnabled()).isFalse();
        assertThatCode(() -> verifier.verify("anything", BODY, null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("epoch seconds, epoch millis and ISO-8601 timestamps are all understood")
    void parsesEveryTimestampShape() {
        assertThat(WebhookSignatureVerifier.parseTimestamp("1787825730")).isEqualTo(Instant.ofEpochSecond(1787825730L));
        assertThat(WebhookSignatureVerifier.parseTimestamp("1787825730123")).isEqualTo(Instant.ofEpochMilli(1787825730123L));
        assertThat(WebhookSignatureVerifier.parseTimestamp("2026-08-26T10:15:30Z")).isEqualTo(NOW);
        assertThat(WebhookSignatureVerifier.parseTimestamp("nonsense")).isNull();
    }
}
