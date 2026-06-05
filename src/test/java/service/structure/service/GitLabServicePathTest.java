package service.structure.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import service.structure.model.StructureSeed;

import java.nio.file.Path;

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

    @Test
    void resolveStructureSeeds_qualifiedName_fromRepo() {
        Map<String, List<String>> index = Map.of(
                "Foo.java", List.of(
                        "src/main/java/com/a/Foo.java",
                        "src/main/java/com/b/Foo.java"));

        List<StructureSeed> seeds = new GitLabService(null)
                .resolveStructureSeeds(index, Map.of(), List.of("com.a.Foo"));

        assertThat(seeds).containsExactly(new StructureSeed.RepoFile("src/main/java/com/a/Foo.java"));
    }

    @Test
    void resolveStructureSeeds_repoPath() {
        List<StructureSeed> seeds = new GitLabService(null).resolveStructureSeeds(
                Map.of(), Map.of(), List.of("src/main/java/com/example/Foo.java"));

        assertThat(seeds).containsExactly(
                new StructureSeed.RepoFile("src/main/java/com/example/Foo.java"));
    }

    @Test
    void resolveStructureSeeds_fallsBackToDependency() {
        Map<String, Path> deps = Map.of("org.example.Foo", Path.of("aspectjweaver-1.9.22-sources.jar"));

        List<StructureSeed> seeds = new GitLabService(null).resolveStructureSeeds(
                Map.of(), deps, List.of("org.example.Foo"));

        assertThat(seeds).containsExactly(new StructureSeed.DependencyClass("org.example.Foo"));
    }

    @Test
    void resolveStructureSeeds_unknown_returnsEmpty() {
        List<StructureSeed> seeds = new GitLabService(null).resolveStructureSeeds(
                Map.of("Foo.java", List.of("src/main/java/a/Foo.java")),
                Map.of(),
                List.of("Missing"));

        assertThat(seeds).isEmpty();
    }

    @Test
    void anySeedUnresolvedInIndex_trueWhenNotInRepo() {
        assertThat(new GitLabService(null).anySeedUnresolvedInIndex(
                Map.of(), List.of("org.example.Foo"))).isTrue();
    }
}
