package com.laserpay.pdei.common.hash;

import com.laserpay.pdei.common.json.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashesTest {

    /** Known-answer test: SHA-256 of the empty input. */
    private static final String SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** Known-answer test: SHA-256 of "abc". */
    private static final String SHA256_OF_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void sha256MatchesKnownAnswers() {
        assertThat(Hashes.sha256(new byte[0])).isEqualTo(SHA256_OF_EMPTY);
        assertThat(Hashes.sha256Hex("abc")).isEqualTo(SHA256_OF_ABC);
    }

    @Test
    void streamingAndByteArrayHashesAgree() throws IOException {
        byte[] data = "delivery-proof-scan-contents".getBytes(StandardCharsets.UTF_8);
        String streamed = Hashes.sha256(new ByteArrayInputStream(data));
        assertThat(streamed).isEqualTo(Hashes.sha256(data));
    }

    @Test
    void streamingHandlesInputLargerThanTheBuffer() throws IOException {
        byte[] data = new byte[8192 * 3 + 17];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        assertThat(Hashes.sha256(new ByteArrayInputStream(data))).isEqualTo(Hashes.sha256(data));
    }

    @Test
    void chainIsDeterministic() {
        String previous = Hashes.sha256Hex("audit-record-1");
        String payload = Hashes.sha256Hex("audit-record-2");

        String first = Hashes.chain(previous, payload);
        String second = Hashes.chain(previous, payload);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void chainIsSensitiveToBothInputsAndToTheirOrder() {
        String a = Hashes.sha256Hex("a");
        String b = Hashes.sha256Hex("b");

        assertThat(Hashes.chain(a, b)).isNotEqualTo(Hashes.chain(b, a));
        assertThat(Hashes.chain(a, b)).isNotEqualTo(Hashes.chain(a, Hashes.sha256Hex("b ")));
    }

    @Test
    void chainTreatsNullAndBlankPreviousHashAsGenesis() {
        String payload = Hashes.sha256Hex("first-record-in-the-chain");

        assertThat(Hashes.chain(null, payload))
                .isEqualTo(Hashes.chain("", payload))
                .isEqualTo(Hashes.chain(Hashes.GENESIS_HASH, payload));
        assertThat(Hashes.GENESIS_HASH).hasSize(64).matches("0{64}");
    }

    @Test
    void chainOfManyLinksBreaksWhenAnEarlyLinkIsAltered() {
        String[] payloads = {"e1", "e2", "e3", "e4"};

        String intact = Hashes.GENESIS_HASH;
        for (String p : payloads) {
            intact = Hashes.chain(intact, Hashes.sha256Hex(p));
        }

        String tampered = Hashes.GENESIS_HASH;
        for (String p : payloads) {
            tampered = Hashes.chain(tampered, Hashes.sha256Hex("e2".equals(p) ? "e2-edited" : p));
        }

        assertThat(tampered).isNotEqualTo(intact);
    }

    @Test
    void chainRejectsANullPayloadHash() {
        assertThatThrownBy(() -> Hashes.chain(Hashes.GENESIS_HASH, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void canonicalJsonHashIgnoresFieldDeclarationOrder() {
        Map<String, Object> declaredOneWay = new LinkedHashMap<>();
        declaredOneWay.put("evidenceId", "EV-1092");
        declaredOneWay.put("amountMinor", 1_299_900L);
        declaredOneWay.put("currency", "INR");

        Map<String, Object> declaredAnotherWay = new LinkedHashMap<>();
        declaredAnotherWay.put("currency", "INR");
        declaredAnotherWay.put("evidenceId", "EV-1092");
        declaredAnotherWay.put("amountMinor", 1_299_900L);

        assertThat(Hashes.canonicalJsonSha256(declaredOneWay, Json.mapper()))
                .isEqualTo(Hashes.canonicalJsonSha256(declaredAnotherWay, Json.mapper()));
    }

    @Test
    void canonicalJsonHashChangesWhenAValueChanges() {
        Map<String, Object> original = Map.of("amountMinor", 1_299_900L, "currency", "INR");
        Map<String, Object> tampered = Map.of("amountMinor", 1_299_901L, "currency", "INR");

        assertThat(Hashes.canonicalJsonSha256(original))
                .isNotEqualTo(Hashes.canonicalJsonSha256(tampered));
    }
}
