package service.structure.cache;

import service.structure.model.ClassContext;
import service.structure.model.ClassStructure;
import service.structure.model.ModifiedClassContext;
import service.structure.model.StructureNode;
import service.structure.model.UnchangedClassContext;
import service.structure.parser.JavaStructureParser;
import service.structure.parser.StructureNodeMapper;

import java.util.List;
import java.util.Set;

/**
 * Запись кэша парсинга: структуры для BFS и шаблон {@link ClassContext} для ответа.
 *
 * @param parsed   результат {@link JavaStructureParser}
 * @param fileNodes узлы файла для {@link StructureNodeMapper}
 * @param template  {@link ClassContext} без привязки к MR ({@code id=0}, пустые {@code callerIds})
 */
public record ParseCacheEntry(
        List<ClassStructure> parsed,
        List<StructureNode> fileNodes,
        ClassContext template
) {
    public boolean hasParsedStructures() {
        return parsed != null && !parsed.isEmpty();
    }

    /**
     * Шаблон для сериализации на диск (только {@link ClassContext}).
     */
    public static ClassContext toTemplate(ClassContext ctx) {
        if (ctx instanceof UnchangedClassContext u) {
            return new UnchangedClassContext(
                    0, u.name(), 0, Set.of(), u.module(), u.structure());
        }
        if (ctx instanceof ModifiedClassContext m) {
            return new ModifiedClassContext(
                    0, m.name(), 0, Set.of(), m.module(),
                    m.structureSource(), m.structureTarget());
        }
        throw new IllegalArgumentException("Unknown ClassContext: " + ctx);
    }

}
