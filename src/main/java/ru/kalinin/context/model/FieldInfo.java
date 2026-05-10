package ru.kalinin.context.model;

import java.util.List;

/**
 * Информация о поле класса (field или record component).
 *
 * @param annotations аннотации поля
 * @param modifiers   модификаторы: private, final и т.д.
 * @param type        тип поля (resolved по import-таблице)
 * @param name        имя поля
 * @param initializer значение инициализации в виде строки, null если отсутствует
 */
public record FieldInfo(
        List<AnnotationInfo> annotations,
        List<String> modifiers,
        String type,
        String name,
        String initializer
) {}
