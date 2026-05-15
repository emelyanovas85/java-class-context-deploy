package ru.kalinin.context.model;

import java.util.List;
import java.util.Objects;

/**
 * Сеалед иерархия контекста класса в рамках мёрж-реквеста.
 *
 * <p>Два варианта:
 * <ul>
 *   <li>{@link UnchangedClassContext} — source и target структуры идентичны,
 *       хранится одно поле {@code structureTarget}.</li>
 *   <li>{@link ModifiedClassContext} — структуры различаются либо одна из них null
 *       (файл создан/удалён/изменён).</li>
 * </ul>
 *
 * <p>Используйте фабрику {@link #of} вместо прямого создания.
 */
public sealed interface ClassContext
        permits UnchangedClassContext, ModifiedClassContext {

    /** qualified name класса, например {@code com.example.Foo}. */
    String name();

    /** Уровень контекста: 0 = изменён в MR, 1+ = зависимость. */
    int level();

    /**
     * Фабрика: сравнивает структуры и возвращает подходящий подтип.
     *
     * <ul>
     *   <li>Если оба не null и равны — {@link UnchangedClassContext}.</li>
     *   <li>Иначе — {@link ModifiedClassContext}.</li>
     * </ul>
     *
     * @param name             qualified name
     * @param level            уровень контекста
     * @param structureSource  структура source-ветки (null — файл удалён)
     * @param structureTarget  структура target-ветки (null — файл создан)
     */
    static ClassContext of(String name, int level,
                           List<StructureNode> structureSource,
                           List<StructureNode> structureTarget) {
        if (structureSource != null
                && structureTarget != null
                && Objects.equals(structureSource, structureTarget)) {
            return new UnchangedClassContext(name, level, structureSource);
        }
        return new ModifiedClassContext(name, level, structureSource, structureTarget);
    }
}
