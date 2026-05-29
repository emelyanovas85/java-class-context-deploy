package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderNestedWaveTest {

    private static final String OUTER = "com.example.Outer";
    private static final String INNER = "com.example.Outer.Inner";
    private static final String EXTERNAL = "com.example.Client";

    @Test
    void skipsNestedWhenOnlyOuterIsCaller() {
        Map<String, String> nestedToRoot = Map.of(INNER, OUTER);
        Map<Integer, String> idToQn = Map.of(1, OUTER);
        assertThat(ContextBuilderService.shouldFetchType(
                INNER, Set.of(1), nestedToRoot, idToQn)).isFalse();
    }

    @Test
    void skipsNestedWhenOnlySiblingNestedIsCaller() {
        Map<String, String> nestedToRoot = Map.of(
                INNER, OUTER,
                "com.example.Outer.Helper", OUTER);
        Map<Integer, String> idToQn = Map.of(2, "com.example.Outer.Helper");
        assertThat(ContextBuilderService.shouldFetchType(
                INNER, Set.of(2), nestedToRoot, idToQn)).isFalse();
    }

    @Test
    void fetchesNestedWhenExternalClassIsCaller() {
        Map<String, String> nestedToRoot = Map.of(INNER, OUTER);
        Map<Integer, String> idToQn = Map.of(1, OUTER, 9, EXTERNAL);
        assertThat(ContextBuilderService.shouldFetchType(
                INNER, Set.of(9), nestedToRoot, idToQn)).isTrue();
    }

    @Test
    void fetchesNestedWhenMixedInternalAndExternalCallers() {
        Map<String, String> nestedToRoot = Map.of(INNER, OUTER);
        Map<Integer, String> idToQn = Map.of(1, OUTER, 9, EXTERNAL);
        assertThat(ContextBuilderService.shouldFetchType(
                INNER, Set.of(1, 9), nestedToRoot, idToQn)).isTrue();
    }

    @Test
    void alwaysFetchesTopLevelType() {
        Map<String, String> nestedToRoot = Map.of(INNER, OUTER);
        Map<Integer, String> idToQn = Map.of(9, EXTERNAL);
        assertThat(ContextBuilderService.shouldFetchType(
                OUTER, Set.of(9), nestedToRoot, idToQn)).isTrue();
    }

    @Test
    void suppressesSeparateNestedContextWhenOuterIsRegistered() {
        Map<String, String> nestedToRoot = Map.of(
                INNER, OUTER,
                "com.example.Outer.Helper", OUTER);
        Set<String> suppressed = ContextBuilderService.nestedContextsSuppressedWhenOuterPresent(
                nestedToRoot, Set.of(OUTER, EXTERNAL));
        assertThat(suppressed).containsExactlyInAnyOrder(INNER, "com.example.Outer.Helper");
    }

    @Test
    void partitionWaveSeparatesFetchAndSkipped() {
        Map<String, Set<Integer>> refs = new LinkedHashMap<>();
        refs.put(INNER, Set.of(1));
        refs.put(OUTER, Set.of(9));
        Map<String, String> nestedToRoot = Map.of(INNER, OUTER);
        Map<Integer, String> idToQn = Map.of(1, OUTER, 9, EXTERNAL);

        ContextBuilderService.WavePartition partition = ContextBuilderService.partitionWave(
                refs, Set.of(), nestedToRoot, idToQn);

        assertThat(partition.toFetch()).containsExactly(OUTER);
        assertThat(partition.internalNestedSkipped()).containsExactly(INNER);
    }
}
