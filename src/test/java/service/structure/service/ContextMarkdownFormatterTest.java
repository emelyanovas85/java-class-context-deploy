package service.structure.service;

import org.junit.jupiter.api.Test;
import service.structure.model.*;
import service.structure.parser.StructureNodeMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextMarkdownFormatterTest {

    @Test
    void toMarkdownLines_oneFilePerElement() {
        ClassContext ctx = ClassContext.of(
                1, Set.of(), "com.example.Foo", 0, "src/main",
                List.of(), List.of());
        FileContext file = new FileContext(
                "src/main/java/com/example/Foo.java", "src/main", 0, List.of(ctx));
        ContextResponse response = new ContextResponse(
                new MergeRequestInfo(1L, "t", "opened", "s", "t", "u",
                        List.of(), List.of("src/main/java/com/example/Foo.java"), List.of()),
                List.of(file),
                0,
                1);

        List<String> lines = ContextMarkdownFormatter.toMarkdownLines(response);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo(file.toString());
    }
}
