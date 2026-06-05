package service.structure.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabServicePathTest {

    @Test
    void findAllJavaPathsByName_simple_returnsAll() {
        Map<String, List<String>> index = Map.of(
                "Foo.java", List.of(
                        "src/main/java/a/Foo.java",
                        "src/test/java/b/Foo.java"));

        List<String> paths = new GitLabService(null).findAllJavaPathsByName(index, "Foo", false);

        assertThat(paths).containsExactly(
                "src/main/java/a/Foo.java",
                "src/test/java/b/Foo.java");
    }

    @Test
    void findAllJavaPathsByName_qualified_filtersByPackage() {
        Map<String, List<String>> index = Map.of(
                "Foo.java", List.of(
                        "src/main/java/com/a/Foo.java",
                        "src/main/java/com/b/Foo.java"));

        List<String> paths = new GitLabService(null)
                .findAllJavaPathsByName(index, "com.a.Foo", true);

        assertThat(paths).containsExactly("src/main/java/com/a/Foo.java");
    }

    @Test
    void normalizeName_stripsJavaSuffix() {
        assertThat(GitLabService.normalizeName("Foo.java")).isEqualTo("Foo");
        assertThat(GitLabService.normalizeName("com.foo.Bar")).isEqualTo("com.foo.Bar");
    }

    @Test
    void qualifiedNameFromRepoPath() {
        assertThat(GitLabService.qualifiedNameFromRepoPath(
                "src/main/java/com/example/Foo.java")).isEqualTo("com.example.Foo");
    }
}
