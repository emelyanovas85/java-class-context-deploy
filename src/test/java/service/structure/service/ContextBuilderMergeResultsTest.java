package service.structure.service;

import org.junit.jupiter.api.Test;
import service.structure.model.ClassStructure;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderMergeResultsTest {

    private static final String OUTER = "forms.credit.Outer";
    private static final String INNER = OUTER + ".Inner";
    private static final String SOURCE = "group:artifact:1.0";

    @Test
    void keepsSeparateResultsForOuterAndNestedFromSameFile() {
        ClassStructure inner = minimalStructure(INNER);
        ClassStructure outer = new ClassStructure(
                List.of(), List.of(), "class", "Outer", OUTER,
                List.of(), null, List.of(), List.of(), List.of(),
                List.of(inner), null, 1, List.of());

        ContextBuilderService service = newService();
        var nestedResult = depthResult(INNER, Set.of(3), outer);
        var outerResult = depthResult(OUTER, Set.of(4, 8), outer);

        List<ContextBuilderService.DepthResult> merged =
                service.mergeResults(List.of(nestedResult, outerResult));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(ContextBuilderService.DepthResult::qName)
                .containsExactlyInAnyOrder(INNER, OUTER);
        assertThat(merged).allMatch(r -> r.parsed().get(0).qualifiedName().equals(OUTER));

        Map<String, Set<Integer>> byQName = merged.stream()
                .collect(Collectors.toMap(
                        ContextBuilderService.DepthResult::qName,
                        ContextBuilderService.DepthResult::callerIds));
        assertThat(byQName.get(INNER)).containsExactly(3);
        assertThat(byQName.get(OUTER)).containsExactlyInAnyOrder(4, 8);
    }

    @Test
    void mergesCallerIdsForSameQNameAndFile() {
        ClassStructure outer = minimalStructure(OUTER);
        ContextBuilderService service = newService();
        var a = depthResult(OUTER, Set.of(3), outer);
        var b = depthResult(OUTER, Set.of(8, 9), outer);

        List<ContextBuilderService.DepthResult> merged =
                service.mergeResults(List.of(a, b));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).qName()).isEqualTo(OUTER);
        assertThat(merged.get(0).callerIds()).containsExactlyInAnyOrder(3, 8, 9);
    }

    private static ContextBuilderService.DepthResult depthResult(
            String qName, Set<Integer> callerIds, ClassStructure outer) {
        return new ContextBuilderService.DepthResult(
                qName, callerIds, List.of(outer), List.of(), SOURCE, 1);
    }

    private static ClassStructure minimalStructure(String qualifiedName) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        return new ClassStructure(
                List.of(), List.of(), "class", simple, qualifiedName,
                List.of(), null, List.of(), List.of(), List.of(),
                List.of(), null, 1, List.of());
    }

    private static ContextBuilderService newService() {
        return new ContextBuilderService(null, null, null, null, null, null, null, null);
    }
}
