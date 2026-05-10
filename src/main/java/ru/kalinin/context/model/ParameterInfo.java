package ru.kalinin.context.model;

import java.util.List;

/**
 * Параметр метода или конструктора.
 *
 * @param annotations аннотации параметра (например {@code @NotNull})
 * @param type        тип параметра, resolved по import-таблице
 * @param name        имя параметра
 * @param varArgs     true, если параметр varargs
 */
public record ParameterInfo(
        List<AnnotationInfo> annotations,
        String type,
        String name,
        boolean varArgs
) {}
