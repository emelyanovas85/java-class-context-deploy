package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Set;

/**
 * Контекст класса, структура которого не изменилась между source и target.
 *
 * <p>Содержит одно поле {@code structureTarget} вместо двух,
 * чтобы избежать дублирования данных в JSON-ответе.
 *
 * @param id              сквозной уникальный идентификатор
 * @param name            qualified name класса
 * @param level           уровень контекста
 * @param callerIds       id классов, которые ссылаются на данный
 * @param structureTarget структура класса (одинакова в source и target)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Класс, структура которого не изменилась между source и target")
public record UnchangedClassContext(
        int id,
        String name,
        int level,
        Set<Integer> callerIds,
        List<StructureNode> structureTarget
) implements ClassContext {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String header = "### " + name + "  [id=" + id + ", level=" + level + ", callers=" + callerIds + "]";
        sb.append(header).append("  [unchanged]\n");
        sb.append(StructureNodePrinter.render(structureTarget, 0));
        return sb.toString();
    }
}
