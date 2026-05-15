package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Контекст класса, структура которого не изменилась между source и target.
 *
 * <p>Содержит одно поле {@code structureTarget} вместо двух,
 * чтобы избежать дублирования данных в JSON-ответе.
 *
 * @param name      qualified name класса
 * @param level     уровень контекста
 * @param structureTarget структура класса (одинакова в source и target)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Класс, структура которого не изменилась между source и target")
public record UnchangedClassContext(
        String name,
        int level,
        List<StructureNode> structureTarget
) implements ClassContext {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String header = "### " + name + "  [level=" + level + "]";
        sb.append(header).append("  [unchanged]\n");
        sb.append(StructureNodePrinter.render(structureTarget, 0));
        return sb.toString();
    }
}
