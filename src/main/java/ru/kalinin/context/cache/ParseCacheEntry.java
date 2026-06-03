package ru.kalinin.context.cache;

import ru.kalinin.context.model.ClassContext;
import ru.kalinin.context.model.ClassStructure;
import ru.kalinin.context.model.ModifiedClassContext;
import ru.kalinin.context.model.StructureNode;
import ru.kalinin.context.model.UnchangedClassContext;
import ru.kalinin.context.parser.JavaStructureParser;
import ru.kalinin.context.parser.StructureNodeMapper;

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
        return switch (ctx) {
            case UnchangedClassContext u -> new UnchangedClassContext(
                    0, u.name(), 0, Set.of(), u.module(), u.structure());
            case ModifiedClassContext m -> new ModifiedClassContext(
                    0, m.name(), 0, Set.of(), m.module(),
                    m.structureSource(), m.structureTarget());
        };
    }

}
