package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.*;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlantUmlRendererTest {

    private final PlantUmlRenderer renderer = new PlantUmlRenderer();

    @Test
    void rendersClassMembersInheritanceAndCallerRelation() {
        StructureNode foo = new StructureNode(
                "class",
                "@Component\npublic class Foo extends BaseFoo implements IFoo",
                "10-45",
                List.of(
                        new StructureNode("field", "private final String name", "25", null),
                        new StructureNode("method", "public static Foo of(String a, String b)", "30", null)
                ));

        ClassContext ctxFoo = new UnchangedClassContext(
                1, "com.example.Foo", 0, Set.of(), "main", List.of(foo));
        ClassContext ctxBar = new UnchangedClassContext(
                2, "com.example.Bar", 1, Set.of(1), "main", List.of());

        ContextResponse response = new ContextResponse(
                new MergeRequestInfo(42L, "Feature", "opened", "feature/x", "main",
                        null, null, List.of(), List.of()),
                List.of(new FileContext(
                        "src/main/java/com/example/Foo.java",
                        "main",
                        0,
                        List.of(ctxFoo, ctxBar))),
                2,
                2);

        String uml = renderer.render(response);

        assertThat(uml).startsWith("@startuml");
        assertThat(uml).endsWith("@enduml\n");
        assertThat(uml).contains("title \"MR !42 — Feature\"");
        assertThat(uml).contains("package \"com.example\"");
        assertThat(uml).contains("\"com.example.Foo\" as com_example_Foo");
        assertThat(uml).contains("private final String name");
        assertThat(uml).contains("public static Foo of(String a, String b)");
        assertThat(uml).contains("com_example_Foo --|> BaseFoo");
        assertThat(uml).contains("com_example_Foo ..|> IFoo");
        assertThat(uml).contains("com_example_Foo ..> com_example_Bar : ref");
    }

    @Test
    void prefersSourceStructureForModifiedClass() {
        StructureNode source = new StructureNode("class", "public class Changed", "1", List.of());
        ClassContext ctx = new ModifiedClassContext(
                1, "com.example.Changed", 0, Set.of(), "main",
                List.of(source),
                List.of(new StructureNode("class", "public class Old", "1", List.of())));

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("src/main/java/com/example/Changed.java", "main", 0, List.of(ctx))),
                1,
                1);

        String uml = renderer.render(response);

        assertThat(uml).contains("public class Changed");
        assertThat(uml).doesNotContain("public class Old");
    }

    @Test
    void aliasSanitizesQualifiedName() {
        assertThat(PlantUmlRenderer.alias("com.example.Foo$Bar")).isEqualTo("com_example_Foo_Bar");
    }
}
