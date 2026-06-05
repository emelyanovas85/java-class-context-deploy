package service.structure.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import service.structure.model.FileSourceResponse;
import service.structure.model.MergeRequestInfo;
import service.structure.model.PinnedRefs;
import service.structure.session.ReviewSession;
import service.structure.session.ReviewSessionCancellation;
import service.structure.session.ReviewSessionService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileSourceServiceTest {

    @Mock GitLabService gitLabService;
    @Mock ReviewSessionService reviewSessionService;
    @InjectMocks FileSourceService fileSourceService;

    @Test
    void resolve_simpleName_returnsAllRepoMatches() {
        ReviewSession session = testSession(Map.of(
                "Foo.java", List.of("src/main/java/a/Foo.java", "src/main/java/b/Foo.java")));

        when(reviewSessionService.getOrBuildDependencySources(session)).thenReturn(Map.of());
        when(gitLabService.findAllJavaPathsByName(any(), eq("Foo"), eq(false)))
                .thenReturn(List.of("src/main/java/a/Foo.java", "src/main/java/b/Foo.java"));
        when(gitLabService.readRawFileContent(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of("class Foo {}"));
        when(gitLabService.qualifiedNameFromRepoPath(anyString()))
                .thenReturn("a.Foo", "b.Foo");

        FileSourceResponse response = fileSourceService.resolve(session, List.of("Foo"));

        assertThat(response.names()).hasSize(1);
        assertThat(response.names().get(0).name()).isEqualTo("Foo");
        assertThat(response.names().get(0).files()).hasSize(2);
        assertThat(response.names().get(0).files()).allMatch(f -> "repo".equals(f.origin()));
    }

    private static ReviewSession testSession(Map<String, List<String>> index) {
        PinnedRefs refs = new PinnedRefs("head", "start", "base");
        MergeRequestInfo mr = new MergeRequestInfo(
                1L, "t", "opened", "s", "t", "u",
                List.of(), List.of(), List.of(), refs);
        return new ReviewSession(
                "sess1234", "https://gitlab.com", "p", "token", 1L,
                refs, mr, List.of(), index, Instant.now().plusSeconds(3600),
                new ReviewSessionCancellation("sess1234"));
    }
}
