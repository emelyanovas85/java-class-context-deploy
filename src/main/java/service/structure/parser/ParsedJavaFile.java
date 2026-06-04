package service.structure.parser;

import service.structure.model.ClassStructure;
import service.structure.model.StructureNode;

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
