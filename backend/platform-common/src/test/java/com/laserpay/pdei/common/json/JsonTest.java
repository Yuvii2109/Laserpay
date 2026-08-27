package com.laserpay.pdei.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    record Sample(String evidenceId, Instant createdAt, String note) {
    }

    @Test
    void canonicalSortsObjectKeysAtEveryDepth() {
        JsonNode node = Json.readTree("""
                {"z":1,"a":{"n":2,"b":{"y":3,"x":4}},"m":5}
                """);

        assertThat(Json.canonical(node))
                .isEqualTo("{\"a\":{\"b\":{\"x\":4,\"y\":3},\"n\":2},\"m\":5,\"z\":1}");
    }

    @Test
    void canonicalFormIsIndependentOfInputKeyOrder() {
        JsonNode first = Json.readTree("{\"b\":1,\"a\":{\"d\":2,\"c\":3}}");
        JsonNode second = Json.readTree("{\"a\":{\"c\":3,\"d\":2},\"b\":1}");

        assertThat(Json.canonical(first)).isEqualTo(Json.canonical(second));
    }

    @Test
    void canonicalPreservesArrayOrderBecauseArrayOrderIsSemantic() {
        JsonNode node = Json.readTree("{\"timeline\":[{\"b\":2,\"a\":1},{\"d\":4,\"c\":3}]}");

        assertThat(Json.canonical(node))
                .isEqualTo("{\"timeline\":[{\"a\":1,\"b\":2},{\"c\":3,\"d\":4}]}");
    }

    @Test
    void canonicalEmitsNoInsignificantWhitespace() {
        JsonNode node = Json.readTree("""
                {
                  "a" : 1,
                  "b" : [ 1, 2 ]
                }
                """);

        assertThat(Json.canonical(node)).isEqualTo("{\"a\":1,\"b\":[1,2]}");
    }

    @Test
    void canonicalHandlesNullAndMissingNodes() {
        assertThat(Json.canonical((JsonNode) null)).isEqualTo("null");
        assertThat(Json.canonical(Json.mapper().missingNode())).isEqualTo("null");
        assertThat(Json.canonical(Json.mapper().nullNode())).isEqualTo("null");
    }

    @Test
    void canonicalOfAnObjectSortsItsProperties() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("zebra", 1);
        map.put("alpha", List.of(3, 1, 2));

        assertThat(Json.canonical(map)).isEqualTo("{\"alpha\":[3,1,2],\"zebra\":1}");
    }

    @Test
    void instantsSerialiseAsIso8601NotEpochNumbers() {
        Sample sample = new Sample("EV-1092", Instant.parse("2026-08-26T10:15:30.123Z"), null);

        String json = Json.write(sample);

        assertThat(json).contains("\"createdAt\":\"2026-08-26T10:15:30.123Z\"");
        assertThat(json).doesNotContain("1787");
    }

    @Test
    void nullPropertiesAreOmitted() {
        assertThat(Json.write(new Sample("EV-1", Instant.EPOCH, null))).doesNotContain("note");
    }

    @Test
    void unknownPropertiesAreToleratedSoNewerProducersDoNotBreakOlderConsumers() {
        Sample sample = Json.read(
                "{\"evidenceId\":\"EV-7\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"futureField\":42}",
                Sample.class);

        assertThat(sample.evidenceId()).isEqualTo("EV-7");
        assertThat(sample.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void malformedJsonFailsAsAValidationException() {
        assertThatThrownBy(() -> Json.read("{not json", Sample.class))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Json.readTree("{\"a\":"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void newMapperIsIndependentOfTheSharedOne() {
        assertThat(Json.newMapper()).isNotSameAs(Json.mapper());
        assertThat(Json.mapper()).isSameAs(Json.mapper());
    }
}
