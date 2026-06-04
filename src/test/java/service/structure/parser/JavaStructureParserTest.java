package service.structure.parser;

import org.junit.jupiter.api.Test;
import service.structure.model.ClassStructure;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JavaStructureParserTest {

    private final JavaStructureParser parser = new JavaStructureParser();

    // Важно: в фикстурном исходнике обязательно указывать импорты для всех пользовательских типов.
    // buildImportMap строит карту simpleName → qualifiedName из import-деклараций,
    // и только так resolveType возвращает fully-qualified name.
    private static final String SIMPLE_CLASS = """
            package com.example;

            import org.springframework.stereotype.Service;
            import java.util.List;
            import com.example.UserRepository;

            @Service
            public class UserService implements UserRepository {

                private final UserRepository userRepository;

                public UserService(UserRepository userRepository) {
                    this.userRepository = userRepository;
                }

                public List<String> findAll() throws IllegalStateException {
                    return List.of();
                }
            }
            """;

    private static final String RECORD_SOURCE = """
            package com.example.dto;

            import jakarta.validation.constraints.NotBlank;

            public record UserDto(
                    @NotBlank String name,
                    int age
            ) {}
            """;

    private static final String NESTED_CLASS = """
            package com.example;

            public class Outer {
                private int value;

                public static class Inner {
                    private String text;
                }
            }
            """;

    private static final String SIBLING_CLASSES = """
            package com.example;

            public class A {
                void use(B b) {}
            }
            class B {
                int x;
            }
            """;

    @Test
    void parsesSimpleClass() {
        List<ClassStructure> result = parser.parse(SIMPLE_CLASS, "UserService.java", 0);

        assertThat(result).hasSize(1);
        ClassStructure cs = result.get(0);

        assertThat(cs.name()).isEqualTo("UserService");
        assertThat(cs.qualifiedName()).isEqualTo("com.example.UserService");
        assertThat(cs.kind()).isEqualTo("class");
        assertThat(cs.modifiers()).contains("public");
        assertThat(cs.annotations()).anySatisfy(a -> assertThat(a.name()).isEqualTo("Service"));
        assertThat(cs.implementedTypes()).containsExactly("com.example.UserRepository");
        assertThat(cs.contextLevel()).isZero();
    }

    @Test
    void parsesFields() {
        List<ClassStructure> result = parser.parse(SIMPLE_CLASS, "UserService.java", 0);
        ClassStructure cs = result.get(0);

        assertThat(cs.fields()).hasSize(1);
        assertThat(cs.fields().get(0).name()).isEqualTo("userRepository");
        assertThat(cs.fields().get(0).type()).isEqualTo("com.example.UserRepository");
    }

    @Test
    void parsesMethods() {
        List<ClassStructure> result = parser.parse(SIMPLE_CLASS, "UserService.java", 0);
        ClassStructure cs = result.get(0);

        // constructor + findAll
        assertThat(cs.methods()).hasSize(2);

        var constructor = cs.methods().stream()
                .filter(m -> m.isConstructor()).findFirst().orElseThrow();
        assertThat(constructor.name()).isEqualTo("UserService");
        assertThat(constructor.parameters()).hasSize(1);
        // параметр тоже резолвится через importMap
        assertThat(constructor.parameters().get(0).type()).isEqualTo("com.example.UserRepository");

        var findAll = cs.methods().stream()
                .filter(m -> !m.isConstructor()).findFirst().orElseThrow();
        assertThat(findAll.name()).isEqualTo("findAll");
        assertThat(findAll.returnType()).isEqualTo("java.util.List<String>");
        assertThat(findAll.thrownExceptions()).contains("IllegalStateException");
    }

    @Test
    void parsesRecord() {
        List<ClassStructure> result = parser.parse(RECORD_SOURCE, "UserDto.java", 1);

        assertThat(result).hasSize(1);
        ClassStructure cs = result.get(0);
        assertThat(cs.kind()).isEqualTo("record");
        assertThat(cs.name()).isEqualTo("UserDto");
        assertThat(cs.contextLevel()).isEqualTo(1);
        assertThat(cs.fields()).hasSize(2);
        assertThat(cs.fields()).anySatisfy(f -> {
            assertThat(f.name()).isEqualTo("name");
            assertThat(f.annotations()).anySatisfy(a -> assertThat(a.name()).isEqualTo("NotBlank"));
        });
    }

    @Test
    void parsesNestedClasses() {
        List<ClassStructure> result = parser.parse(NESTED_CLASS, "Outer.java", 0);

        assertThat(result).hasSize(1);
        ClassStructure outer = result.get(0);
        assertThat(outer.nestedClasses()).hasSize(1);
        assertThat(outer.nestedClasses().get(0).name()).isEqualTo("Inner");
    }

    @Test
    void parsesPackagePrivateSiblingTopLevelClass() {
        List<ClassStructure> result = parser.parse(SIBLING_CLASSES, "A.java", 0);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ClassStructure::qualifiedName)
                .containsExactly("com.example.A", "com.example.B");
        assertThat(result.get(1).modifiers()).doesNotContain("public");
    }

    @Test
    void containsTopLevelType_findsPackagePrivateSibling() {
        assertThat(parser.containsTopLevelType(SIBLING_CLASSES, "B")).isTrue();
        assertThat(parser.containsTopLevelType(SIBLING_CLASSES, "A")).isTrue();
        assertThat(parser.containsTopLevelType(SIBLING_CLASSES, "Missing")).isFalse();
    }

    @Test
    void collectsReferencedTypes() {
        List<ClassStructure> result = parser.parse(SIMPLE_CLASS, "UserService.java", 0);
        Set<String> types = parser.collectReferencedTypes(result.get(0)).stream()
                .map(UnresolvedTypeRef::name)
                .collect(Collectors.toSet());

        // Service аннотация и UserRepository теперь резолвится в qualified name
        assertThat(types).contains("Service", "com.example.UserRepository");
        // встроенные типы не возвращаются
        assertThat(types).doesNotContain("void", "String", "int");
    }
}
