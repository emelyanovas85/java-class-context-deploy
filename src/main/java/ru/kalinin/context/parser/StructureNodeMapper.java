package ru.kalinin.context.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.kalinin.context.model.StructureNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Преобразует Java-исходник в список {@link StructureNode} верхнего уровня.
 *
 * <h3>Thread safety</h3>
 * <p>{@link JavaParser} не является thread-safe, поэтому экземпляр создаётся
 * на каждый вызов {@link #map} из иммутабельного {@link ParserConfiguration}.
 *
 * <p>Каждый {@code StructureNode} с type=class/interface/enum/record/annotation
 * содержит дочерние узлы (поля, методы, конструкторы, вложенные типы).
 * Все узлы содержат строковую сигнатуру и диапазон строк {@code rows}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructureNodeMapper {

    private final SignatureBuilder sig;

    private final ParserConfiguration parserConfig = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    /**
     * Парсит исходник и возвращает {@link StructureNode} для каждого top-level типа.
     *
     * @param sourceCode содержимое .java-файла
     * @param sourceFile путь к файлу (для логов)
     * @return список узлов верхнего уровня; пустой список при ошибке парсинга
     */
    public List<StructureNode> map(String sourceCode, String sourceFile) {
        var result = new JavaParser(parserConfig).parse(sourceCode);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to build StructureNode for {}: {}", sourceFile, result.getProblems());
            return List.of();
        }
        CompilationUnit cu = result.getResult().get();
        List<StructureNode> nodes = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            nodes.add(mapType(type));
        }
        return nodes;
    }

    // ── type dispatch ──────────────────────────────────────────────────────

    private StructureNode mapType(TypeDeclaration<?> type) {
        String kind;
        String signature;

        if (type instanceof ClassOrInterfaceDeclaration coid) {
            kind = coid.isInterface() ? "interface" : "class";
            signature = sig.classSignature(coid);
        } else if (type instanceof RecordDeclaration rd) {
            kind = "record";
            signature = sig.recordSignature(rd);
        } else if (type instanceof EnumDeclaration ed) {
            kind = "enum";
            signature = sig.enumSignature(ed);
        } else if (type instanceof AnnotationDeclaration ad) {
            kind = "annotation";
            signature = sig.annotationTypeSignature(ad);
        } else {
            kind = "class";
            signature = type.getNameAsString();
        }

        List<StructureNode> children = buildChildren(type);
        return new StructureNode(kind, signature, sig.rows(type), children);
    }

    // ── children ───────────────────────────────────────────────────────────

    private List<StructureNode> buildChildren(TypeDeclaration<?> type) {
        List<StructureNode> children = new ArrayList<>();

        if (type instanceof EnumDeclaration ed) {
            ed.getEntries().forEach(entry ->
                    children.add(new StructureNode(
                            "enum_constant",
                            entry.getNameAsString(),
                            sig.rows(entry),
                            null)));
        }

        type.getFields().forEach(fd ->
                fd.getVariables().forEach(v ->
                        children.add(new StructureNode(
                                "field",
                                sig.fieldSignature(fd, v),
                                sig.rows(fd),
                                null))));

        type.getConstructors().forEach(cd ->
                children.add(new StructureNode(
                        "constructor",
                        sig.constructorSignature(cd),
                        sig.rows(cd),
                        null)));

        type.getMethods().forEach(md ->
                children.add(new StructureNode(
                        "method",
                        sig.methodSignature(md),
                        sig.rows(md),
                        null)));

        if (type instanceof RecordDeclaration rd) {
            rd.getParameters().forEach(p -> {
                String annots = p.getAnnotations().stream()
                        .map(a -> a.toString() + '\n')
                        .reduce("", String::concat);
                String fieldSig = annots + p.getType().asString() + ' ' + p.getNameAsString();
                children.add(new StructureNode("field", fieldSig, sig.rows(p), null));
            });
        }

        type.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration)
                .map(m -> (TypeDeclaration<?>) m)
                .forEach(nested -> children.add(mapType(nested)));

        return children;
    }
}
