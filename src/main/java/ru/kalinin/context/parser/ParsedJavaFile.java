package ru.kalinin.context.parser;

import ru.kalinin.context.model.ClassStructure;
import ru.kalinin.context.model.StructureNode;

import java.util.List;

/**
 * Результат одного прохода JavaParser по исходнику.
 */
public record ParsedJavaFile(
        List<ClassStructure> structures,
        List<StructureNode> nodes
) {
    public static ParsedJavaFile empty() {
        return new ParsedJavaFile(List.of(), List.of());
    }
}
