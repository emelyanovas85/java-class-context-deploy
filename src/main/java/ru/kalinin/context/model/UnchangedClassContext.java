package ru.kalinin.context.model;

import java.util.List;
import java.util.Set;

/**
 * Класс, чья структура не изменилась между source и target ветками MR
 * (или класс получен из внешней зависимости / source-ветки без сравнения).
 *
 * <p>Хранит единственное поле {@code structure} вместо двух, экономя память.
 */
public record UnchangedClassContext(
        int id,
        String name,
        int level,
        Set<Integer> callerIds,
        String source,
        List<StructureNode> structure
) implements ClassContext {

    @Override
    public List<StructureNode> structure() {
        return structure;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String header = "### " + name + "  [level=" + level + ", id=" + id + ", callers=" + callerIds + ", source=" + source + "]";
        sb.append(header).append("  [unchanged]\n");
        sb.append(StructureNodePrinter.render(structure, 0));
        return sb.toString();
    }
}
