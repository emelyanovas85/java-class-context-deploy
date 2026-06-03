package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.ClassContext;
import ru.kalinin.context.model.ClassStructure;
import ru.kalinin.context.model.FileContext;
import ru.kalinin.context.model.UnchangedClassContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderGroupByFileTest {

    private static final String FILE = "src/main/java/com/example/A.java";
    private static final String MODULE = "src/main";

    @Test
    void groupsPackagePrivateSiblingWithPublicClassInSameFile() {
        ClassStructure a = structure("com.example.A", FILE);
        ClassStructure b = structure("com.example.B", FILE);

        ClassContext ctxA = new UnchangedClassContext(1, "com.example.A", 0, Set.of(), MODULE, List.of());
        ClassContext ctxB = new UnchangedClassContext(2, "com.example.B", 1, Set.of(1), MODULE, List.of());

        List<FileContext> files = ContextBuilderService.groupContextsByFile(
                List.of(ctxA, ctxB), List.of(a, b));

        assertThat(files).hasSize(1);
        FileContext file = files.get(0);
        assertThat(file.path()).isEqualTo(FILE);
        assertThat(file.module()).isEqualTo(MODULE);
        assertThat(file.level()).isZero();
        assertThat(file.classes()).extracting(ClassContext::name)
                .containsExactly("com.example.A", "com.example.B");
    }

    @Test
    void keepsDifferentFilesSeparate() {
        ClassStructure a = structure("com.example.A", FILE);
        ClassStructure c = structure("com.other.C", "src/main/java/com/other/C.java");

        ClassContext ctxA = new UnchangedClassContext(1, "com.example.A", 0, Set.of(), MODULE, List.of());
        ClassContext ctxC = new UnchangedClassContext(2, "com.other.C", 1, Set.of(1), MODULE, List.of());

        List<FileContext> files = ContextBuilderService.groupContextsByFile(
                List.of(ctxA, ctxC), List.of(a, c));

        assertThat(files).hasSize(2);
        assertThat(files).extracting(FileContext::path)
                .containsExactly(FILE, "src/main/java/com/other/C.java");
    }

    @Test
    void toStringJoinsClassContextsWithBlankLine() {
        ClassContext ctxA = new UnchangedClassContext(1, "com.example.A", 0, Set.of(), MODULE, List.of());
        ClassContext ctxB = new UnchangedClassContext(2, "com.example.B", 1, Set.of(1), MODULE, List.of());
        FileContext file = new FileContext(FILE, MODULE, 0, List.of(ctxA, ctxB));

        String text = file.toString();
        assertThat(text).contains("### com.example.A");
        assertThat(text).contains("### com.example.B");
        assertThat(text).contains("\n\n");
    }

    private static ClassStructure structure(String qualifiedName, String sourceFile) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        return new ClassStructure(
                List.of(), List.of(), "class", simple, qualifiedName,
                List.of(), null, List.of(), List.of(), List.of(),
                List.of(), sourceFile, 0, List.of());
    }
}
