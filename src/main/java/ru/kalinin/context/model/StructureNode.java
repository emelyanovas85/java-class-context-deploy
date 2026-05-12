package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Рекурсивный узел структуры Java-типа.
 *
 * <p>Для type=class/interface/enum/record/annotation заполнен {@code children}.
 * Для type=field/method/constructor {@code children} — null.
 *
 * @param type      тип узла: class, interface, enum, record, annotation,
 *                  field, method, constructor
 * @param signature строковая сигнатура узла с аннотациями,
 *                  например {@code "@Component\npublic class Foo extends Bar"}
 * @param rows      диапазон строк в исходном файле, например {@code "17"} или {@code "19-22"}
 * @param children  дочерние узлы (только для типов-контейнеров)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructureNode(
        String type,
        String signature,
        String rows,
        List<StructureNode> children
) {}
