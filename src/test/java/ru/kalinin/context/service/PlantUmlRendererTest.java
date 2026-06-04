package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlantUmlRendererTest {

    private final PlantUmlRenderer renderer = new PlantUmlRenderer();

    @Test
    void rendersClassicPlantUmlFormat() {
        StructureNode authorization = new StructureNode(
                "class",
                "public class Authorization",
                "1",
                List.of(
                        new StructureNode("field", "private static ThreadLocal<User> currentUser", "2", null),
                        new StructureNode("method", "public User getCurrentUser()", "3", null),
                        new StructureNode("method", "public void authorize(User user)", "4", null),
                        new StructureNode("method", "private void authorize(String login, String password)", "5", null),
                        new StructureNode("method", "private void checkCredentials(String login, String password)", "6", null)
                ));

        StructureNode userEnum = new StructureNode(
                "enum",
                "enum User",
                "10",
                List.of(
                        new StructureNode("enum_constant", "TestUser", "11", null),
                        new StructureNode("enum_constant", "LKNeolantTenaks", "12", null),
                        new StructureNode("enum_constant", "ESODUser", "13", null),
                        new StructureNode("enum_constant", "Boss", "14", null)
                ));

        ClassContext ctxAuth = new UnchangedClassContext(
                1, "com.example.Authorization", 0, Set.of(), "main", List.of(authorization));
        ClassContext ctxUser = new UnchangedClassContext(
                2, "com.example.User", 0, Set.of(), "main", List.of(userEnum));

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("Auth.java", "main", 0, List.of(ctxAuth, ctxUser))),
                1,
                2);

        String uml = renderer.render(response);

        assertThat(uml).isEqualTo("""
                @startuml
                + class Authorization {
                  - currentUser: ThreadLocal<User>
                  + getCurrentUser(): User
                  + authorize(User): void
                  - authorize(String, String): void
                  - checkCredentials(String, String): void
                }

                enum User {
                  TestUser
                  LKNeolantTenaks
                  ESODUser
                  Boss
                }

                Authorization --> User
                @enduml
                """);
    }

    @Test
    void convertsCyrillicFieldSignature() {
        String field = PlantUmlRenderer.PlantUmlSignatureConverter.toField(
                "private static Отчеты_депозитариев информация_депозитариев");
        assertThat(field).isEqualTo("- информация_депозитариев: Отчеты_депозитариев");
    }

    @Test
    void rendersExtendsRelation() {
        StructureNode node = new StructureNode(
                "class",
                "public class T6553 extends Scenario",
                "1",
                List.of());

        ClassContext ctx = new UnchangedClassContext(
                1, "test.credit.T6553", 0, Set.of(), "test", List.of(node));

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("T6553.java", "test", 0, List.of(ctx))),
                0,
                1);

        String uml = renderer.render(response);

        assertThat(uml).contains("+ class T6553 {");
        assertThat(uml).contains("T6553 --|> Scenario");
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
                List.of(new FileContext("Changed.java", "main", 0, List.of(ctx))),
                1,
                1);

        String uml = renderer.render(response);

        assertThat(uml).contains("+ class Changed {");
        assertThat(uml).doesNotContain("Old");
    }

    @Test
    void simpleNameExtractsLastSegment() {
        assertThat(PlantUmlRenderer.simpleName("test.credit.T6553")).isEqualTo("T6553");
        assertThat(PlantUmlRenderer.simpleName("com.example.Foo$Bar")).isEqualTo("Bar");
        assertThat(PlantUmlRenderer.simpleName("enums.Пользователи")).isEqualTo("Пользователи");
    }

    @Test
    void rendersCyrillicEnumNameFromQualifiedName() {
        StructureNode node = new StructureNode(
                "enum",
                "public enum Пользователи",
                "3-7",
                List.of(
                        new StructureNode("enum_constant", "user", "4", null),
                        new StructureNode("enum_constant", "viktor", "5", null),
                        new StructureNode("enum_constant", "oper_od", "6", null)));

        ClassContext ctx = new UnchangedClassContext(
                142, "enums.Пользователи", 2, Set.of(79), "bugbusters.modules:ASDCO-core:2.3.0",
                List.of(node));

        String uml = renderer.render(new ContextResponse(
                null,
                List.of(new FileContext("enums/Пользователи.java", "jar", 2, List.of(ctx))),
                2,
                1));

        assertThat(uml).contains("enum Пользователи {");
        assertThat(uml).contains("user");
        assertThat(uml).contains("viktor");
        assertThat(uml).contains("oper_od");
        assertThat(uml).doesNotContain("Unknown");
    }

    @Test
    void doesNotFailOnVoidMethodsAndMalformedSignatures() {
        StructureNode node = new StructureNode(
                "class",
                "public class T6553 extends Scenario",
                "1",
                List.of(
                        new StructureNode("method", "@Override protected void beforeScenario()", "2", null),
                        new StructureNode("method", "void packagePrivate()", "3", null),
                        new StructureNode("method", "public void t6553()", "4", null),
                        new StructureNode("constructor", "public T6553()", "5", null),
                        new StructureNode("method", "broken signature", "6", null)
                ));

        ClassContext ctx = new UnchangedClassContext(
                1, "test.credit.T6553", 0, Set.of(), "test", List.of(node));

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("T6553.java", "test", 0, List.of(ctx))),
                0,
                1);

        assertThat(renderer.render(response)).contains("@startuml").contains("@enduml");
    }

    @Test
    void compactFormatRemovesIndentAndBlankLines() {
        StructureNode authorization = new StructureNode(
                "class",
                "public class Authorization",
                "1",
                List.of(new StructureNode("field", "private String name", "2", null)));

        ClassContext ctx = new UnchangedClassContext(
                1, "com.example.Authorization", 0, Set.of(), "main", List.of(authorization));

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("A.java", "main", 0, List.of(ctx))),
                0,
                1);

        String pretty = renderer.render(response, true);
        String compact = renderer.render(response, false);

        assertThat(pretty).contains("  - name");
        assertThat(pretty).contains("\n\n");
        assertThat(compact).doesNotContain("\n\n");
        assertThat(compact.lines().noneMatch(line -> line.startsWith(" "))).isTrue();
        assertThat(compact).isEqualTo("""
                @startuml
                +class Authorization {
                -name: String
                }
                @enduml
                """);
    }

    @Test
    void usesQualifiedNameWhenSimpleNameCollides() {
        StructureNode fooA = new StructureNode("class", "public class Foo", "1", List.of());
        StructureNode fooB = new StructureNode("class", "public class Foo", "1", List.of());
        ClassContext ctxA = new UnchangedClassContext(
                1, "com.example.Foo", 0, Set.of(), "main", List.of(fooA));
        ClassContext ctxB = new UnchangedClassContext(
                2, "com.other.Foo", 1, Set.of(1), "main", List.of(fooB));

        Map<String, String> display = PlantUmlRenderer.buildDisplayNames(List.of(ctxA, ctxB));
        assertThat(display).containsEntry("com.example.Foo", "com.example.Foo");
        assertThat(display).containsEntry("com.other.Foo", "com.other.Foo");

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("Foo.java", "main", 0, List.of(ctxA, ctxB))),
                1,
                2);

        String uml = renderer.render(response);

        assertThat(uml).contains("+ class com.example.Foo {");
        assertThat(uml).contains("+ class com.other.Foo {");
        assertThat(uml).contains("com.example.Foo --> com.other.Foo");
        assertThat(uml).doesNotContain("+ class Foo {");
    }

    @Test
    void usesSimpleNameWhenUnique() {
        ClassContext ctx = new UnchangedClassContext(
                1, "com.example.Unique", 0, Set.of(), "main",
                List.of(new StructureNode("class", "public class Unique", "1", List.of())));

        assertThat(PlantUmlRenderer.buildDisplayNames(List.of(ctx)))
                .containsEntry("com.example.Unique", "Unique");
    }

    @Test
    void addsMemberStereotypesForBranchDiff() {
        StructureNode sourceRoot = new StructureNode(
                "class",
                "public class Foo",
                "1",
                List.of(
                        new StructureNode("field", "private String onlyInSource", "2", null),
                        new StructureNode("method", "public void shared()", "3", null),
                        new StructureNode("method", "public void changed(int x)", "4", null)));

        StructureNode targetRoot = new StructureNode(
                "class",
                "public class Foo",
                "1",
                List.of(
                        new StructureNode("field", "private int onlyInTarget", "5", null),
                        new StructureNode("method", "public void shared()", "3", null),
                        new StructureNode("method", "public void changed(String y)", "6", null)));

        ClassContext ctx = new ModifiedClassContext(
                1, "com.example.Foo", 0, Set.of(), "main",
                List.of(sourceRoot),
                List.of(targetRoot));

        String uml = renderer.render(new ContextResponse(
                null,
                List.of(new FileContext("Foo.java", "main", 0, List.of(ctx))),
                0,
                1));

        assertThat(uml).contains("- onlyInSource: String <<source only>>");
        assertThat(uml).contains("- onlyInTarget: int <<target only>>");
        assertThat(uml).contains("+ shared(): void");
        assertThat(uml).doesNotContain("shared(): void <<");
        assertThat(uml).contains("+ changed(int): void <<changed>>");
        assertThat(uml).doesNotContain("changed(String)");
        assertThat(uml).contains("legend right");
        assertThat(uml).contains("<<source only>> — только в source-ветке MR");
    }

    @Test
    void compactFormatTightensRelationArrows() {
        StructureNode a = new StructureNode("class", "public class A", "1", List.of());
        StructureNode b = new StructureNode("class", "public class B extends A", "2", List.of());
        ClassContext ctxA = new UnchangedClassContext(1, "com.example.A", 0, Set.of(), "main", List.of(a));
        ClassContext ctxB = new UnchangedClassContext(2, "com.example.B", 1, Set.of(1), "main", List.of(b));

        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext("B.java", "main", 0, List.of(ctxA, ctxB))),
                1,
                2);

        String compact = renderer.render(response, false);

        assertThat(compact).contains("B--|>A");
        assertThat(compact).contains("A-->B");
        assertThat(compact).doesNotContain(" --|> ").doesNotContain(" --> ");
    }
}
