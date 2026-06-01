package ru.kalinin.context.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.kalinin.context.model.ClassStructure;
import ru.kalinin.context.model.StructureNode;
import ru.kalinin.context.model.UnchangedClassContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClassContextParseCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void diskFileNameUsesDependencyAndCacheSuffix() {
        assertThat(ClassContextParseCache.diskFileName("org.aspectj:aspectjweaver:1.9.22"))
                .isEqualTo("org.aspectj__aspectjweaver__1.9.22__cache.json");
        assertThat(ClassContextParseCache.diskFileName("src/main"))
                .isEqualTo("src__main__cache.json");
    }

    @Test
    void roundTripsClassContextOnDiskAndReloadsToMemory() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Cache<String, ParseCacheEntry> memory = Caffeine.newBuilder().build();
        ClassContextParseCache cache = new ClassContextParseCache(
                mapper, memory, true, tempDir.toString());

        List<StructureNode> nodes = List.of(new StructureNode("class", "public class Foo", "1", null));
        UnchangedClassContext template = new UnchangedClassContext(
                0,
                "com.example.Foo",
                0,
                Set.of(),
                "org.example:lib:1.0",
                nodes);

        List<ClassStructure> parsed = List.of(new ClassStructure(
                List.of(), List.of("public"), "class", "Foo", "com.example.Foo",
                List.of(), null, List.of(), List.of(), List.of(), List.of(),
                "Foo.java", 1, List.of()));

        ParseCacheEntry entry = new ParseCacheEntry(parsed, nodes, template);
        cache.put("org.example:lib:1.0", "com.example.Foo", entry);

        Path diskFile = tempDir.resolve("org.example__lib__1.0__cache.json");
        assertThat(diskFile).exists();
        assertThat(Files.readString(diskFile)).contains("com.example.Foo");

        memory.invalidateAll();
        var reloaded = cache.get("org.example:lib:1.0", "com.example.Foo");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().template().name()).isEqualTo("com.example.Foo");
        assertThat(reloaded.get().hasParsedStructures()).isFalse();

        memory.put(ClassContextParseCache.cacheKey("org.example:lib:1.0", "com.example.Foo"),
                new ParseCacheEntry(parsed, nodes, template));
        var fromMem = cache.get("org.example:lib:1.0", "com.example.Foo");
        assertThat(fromMem).isPresent();
        assertThat(fromMem.get().hasParsedStructures()).isTrue();
        assertThat(fromMem.get().parsed()).hasSize(1);
    }

    @Test
    void disabledCacheReturnsEmpty() {
        ObjectMapper mapper = new ObjectMapper();
        Cache<String, ParseCacheEntry> memory = Caffeine.newBuilder().build();
        ClassContextParseCache cache = new ClassContextParseCache(
                mapper, memory, false, tempDir.toString());

        assertThat(cache.get("main", "com.example.Foo")).isEmpty();
    }
}
