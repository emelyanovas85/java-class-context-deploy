package ru.kalinin.context.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * <p>Каждый экземпляр несёт сквозной {@link #id()} и множество {@link #callerIds()} —
 * id классов, которые непосредственно ссылаются на данный. Классы уровня 0
 * имеют пустой {@code callerIds}.
 *
 * <p>Используйте фабрику {@link #of} вместо прямого создания.
 */
public sealed interface ClassContext
        permits UnchangedClassContext, ModifiedClassContext {

    /** Сквозной уникальный идентификатор класса в рамках одного ответа. */
    int id();

    /** qualified name класса, например {@code com.example.Foo}. */
    String name();

    /** Уровень контекста: 0 = изменён в MR, 1+ = зависимость. */
    int level();

    /**
     * Идентификаторы классов, которые непосредственно ссылаются на данный.
     * Пустое множество для классов уровня 0 (изменённые файлы MR).
     */
    Set<Integer> callerIds();

    /**
     * Фабрика: сравнивает структуры и возвращает подходящий подтип.
     *
     * <ul>
     *   <li>Если оба не null и равны — {@link UnchangedClassContext}.</li>
     *   <li>Иначе — {@link ModifiedClassContext}.</li>
     * </ul>
     *
     * @param id               сквозной id
     * @param callerIds        id классов-потребителей
     * @param name             qualified name
     * @param level            уровень контекста
     * @param structureSource  структура source-ветки (null — файл удалён)
     * @param structureTarget  структура target-ветки (null — файл создан)
     */
    static ClassContext of(int id, Set<Integer> callerIds,
                           String name, int level,
                           List<StructureNode> structureSource,
                           List<StructureNode> structureTarget) {
        if (structureSource != null
                && structureTarget != null
                && Objects.equals(structureSource, structureTarget)) {
            return new UnchangedClassContext(id, name, level, callerIds, structureSource);
        }
        return new ModifiedClassContext(id, name, level, callerIds, structureSource, structureTarget);
    }
}
