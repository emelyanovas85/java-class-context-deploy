package ru.kalinin.context.parser;

import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.ClassStructure;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceParseServiceTest {

    private final JavaSourceParseService parseService = new JavaSourceParseService(
            new JavaStructureParser(), new StructureNodeMapper(new SignatureBuilder()));

    private static final String SIMPLE_CLASS = """
            package com.example;

            import org.springframework.stereotype.Service;
            import com.example.UserRepository;

            @Service
            public class UserService implements UserRepository {
                private final UserRepository userRepository;
            }
            """;

    @Test
    void singleParseProducesStructuresAndNodes() {
        ParsedJavaFile file = parseService.parse(SIMPLE_CLASS, "UserService.java", 0,
                JavaStructureParser.NO_OP_RESOLVER);

        assertThat(file.structures()).hasSize(1);
        assertThat(file.nodes()).hasSize(1);
        ClassStructure cs = file.structures().get(0);
        assertThat(cs.qualifiedName()).isEqualTo("com.example.UserService");
        assertThat(file.nodes().get(0).type()).isEqualTo("class");
        assertThat(file.nodes().get(0).signature()).contains("UserService");
    }
}
