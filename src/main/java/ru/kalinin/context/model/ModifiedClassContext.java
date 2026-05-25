package ru.kalinin.context.model;

import java.util.List;
import java.util.Set;

/**
 * Класс, чья структура изменилась между source и target ветками MR,
 * либо файл был создан или удалён.
 *
 * <p>{@code structureSource} — структура source-ветки (null, если файл был удалён).<br>
 * {@code structureTarget} — структура target-ветки (null, если файл был только что создан).
 */
public record ModifiedClassContext(
        int id,
        String name,
        int level,
        Set<Integer> callerIds,
        String source,
        List<StructureNode> structureSource,
        List<StructureNode> structureTarget
) implements ClassContext {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String header = "### " + name
                + "  [level=" + level + ", id=" + id
                + ", callers=" + callerIds
                + ", source=" + source + "]";
        sb.append(header).append('\n');
        if (structureSource != null) {
            sb.append("#### [branch=source]\n");
            sb.append(StructureNodePrinter.render(structureSource, 0)).append("\n");
        }
        if (structureTarget != null) {
            sb.append("#### [branch=target]\n");
            sb.append(StructureNodePrinter.render(structureTarget, 0));
        }
        return sb.toString();
    }
}
