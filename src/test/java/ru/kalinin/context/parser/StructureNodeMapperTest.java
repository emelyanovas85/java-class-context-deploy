package ru.kalinin.context.parser;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.StructureNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructureNodeMapperTest {

    private final StructureNodeMapper mapper = new StructureNodeMapper(new SignatureBuilder());

    private static final String CLASS_SOURCE = """
            package com.example;

            import org.springframework.stereotype.Component;

            @Component
            public class Foo extends BaseFoo implements IFoo {

                @org.springframework.beans.factory.annotation.Value("${app.name}")
                private final String name;

                @Deprecated
                private Foo(String a, String b) {}

                @Deprecated
                public static Foo of(String a, String b) {
                    return new Foo(a, b);
                }

                public static class Builder implements IBuilder {
                    private String value;

                    public Builder value(String v) { this.value = v; return this; }
                }
            }
            """;

    @Test
    void mapsTopLevelClass() {
        List<StructureNode> nodes = mapper.map(CLASS_SOURCE, "Foo.java");

        assertThat(nodes).hasSize(1);
        StructureNode cls = nodes.get(0);
        assertThat(cls.type()).isEqualTo("class");
        assertThat(cls.signature()).contains("@Component", "public class Foo",
                "extends BaseFoo", "implements IFoo");
        assertThat(cls.rows()).isNotNull();
    }

    @Test
    void mapsFieldWithAnnotation() {
        StructureNode cls = mapper.map(CLASS_SOURCE, "Foo.java").get(0);

        StructureNode field = cls.children().stream()
                .filter(n -> "field".equals(n.type()))
                .findFirst().orElseThrow();
        assertThat(field.signature()).contains("name");
        assertThat(field.signature()).contains("@org.springframework.beans.factory.annotation.Value");
        assertThat(field.rows()).isNotNull();
    }

    @Test
    void mapsConstructorAndMethod() {
        StructureNode cls = mapper.map(CLASS_SOURCE, "Foo.java").get(0);

        assertThat(cls.children()).anySatisfy(n -> {
            assertThat(n.type()).isEqualTo("constructor");
            assertThat(n.signature()).contains("Foo(", "@Deprecated");
        });

        assertThat(cls.children()).anySatisfy(n -> {
            assertThat(n.type()).isEqualTo("method");
            assertThat(n.signature()).contains("of(", "@Deprecated");
        });
    }

    @Test
    void mapsNestedClass() {
        StructureNode cls = mapper.map(CLASS_SOURCE, "Foo.java").get(0);

        StructureNode nested = cls.children().stream()
                .filter(n -> "class".equals(n.type()))
                .findFirst().orElseThrow();
        assertThat(nested.signature()).contains("Builder");
        assertThat(nested.children()).anySatisfy(n ->
                assertThat(n.type()).isIn("field", "method"));
    }

    @Test
    void rowsNonNull() {
        StructureNode cls = mapper.map(CLASS_SOURCE, "Foo.java").get(0);
        assertThat(cls.rows()).isNotNull();
        cls.children().forEach(child -> assertThat(child.rows()).isNotNull());
    }

    @Test
    void mapsRecord() {
        String src = """
                package com.example;
                import jakarta.validation.constraints.NotBlank;
                public record UserDto(@NotBlank String name, int age) {}
                """;
        List<StructureNode> nodes = mapper.map(src, "UserDto.java");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).type()).isEqualTo("record");
        assertThat(nodes.get(0).signature()).contains("record UserDto");
    }

    @Test
    void mapsEnum() {
        String src = """
                package com.example;
                public enum Status { ACTIVE, INACTIVE }
                """;
        List<StructureNode> nodes = mapper.map(src, "Status.java");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).type()).isEqualTo("enum");
        assertThat(nodes.get(0).children())
                .extracting(StructureNode::type)
                .containsExactlyInAnyOrder("enum_constant", "enum_constant");
    }
}
