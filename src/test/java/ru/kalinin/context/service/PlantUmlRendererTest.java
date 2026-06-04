package ru.kalinin.context.service;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.*;

import java.util.List;
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
}
