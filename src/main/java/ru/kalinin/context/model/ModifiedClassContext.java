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
}
