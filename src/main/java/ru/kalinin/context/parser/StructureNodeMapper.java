package ru.kalinin.context.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.kalinin.context.model.ClassStructure;
import ru.kalinin.context.model.StructureNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /**
     * Извлекает {@link StructureNode} top-level типа по индексу в файле
     * (порядок совпадает с {@link JavaStructureParser#parse} и {@link #map}).
     */
    public List<StructureNode> structureForTopLevelIndex(List<StructureNode> fileNodes, int typeIndex) {
        if (fileNodes == null || typeIndex < 0 || typeIndex >= fileNodes.size()) {
            return List.of();
        }
        return List.of(fileNodes.get(typeIndex));
    }

    /**
     * Извлекает поддерево nested-типа по простому имени (поиск среди вложенных, не top-level).
     */
    public List<StructureNode> structureForNestedType(List<StructureNode> fileNodes, String simpleName) {
        if (fileNodes == null || simpleName == null || simpleName.isEmpty()) {
            return List.of();
        }
        for (StructureNode root : fileNodes) {
            if (root.children() == null) continue;
            List<StructureNode> found = findNestedAmongChildren(root.children(), simpleName);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    /**
     * @deprecated используйте {@link #structureForTopLevelIndex} или {@link #structureForNestedType}
     */
    public List<StructureNode> structureForType(List<StructureNode> fileNodes, String simpleName) {
        if (fileNodes == null || simpleName == null) return List.of();
        for (StructureNode node : fileNodes) {
            if (isTypeNode(node) && typeSimpleName(node).equals(simpleName)) {
                return List.of(node);
            }
        }
        return structureForNestedType(fileNodes, simpleName);
    }

    /**
     * Рекурсивно ищет узел типа по {@code simpleName} в дереве файла (legacy).
     */
    public List<StructureNode> findNestedTypeNodes(List<StructureNode> nodes, String simpleName) {
        if (nodes == null) return List.of();
        for (StructureNode node : nodes) {
            if (isTypeNode(node) && typeSimpleName(node).equals(simpleName)) {
                return List.of(node);
            }
            if (node.children() != null) {
                List<StructureNode> found = findNestedTypeNodes(node.children(), simpleName);
                if (!found.isEmpty()) return found;
            }
        }
        return List.of();
    }

    /**
     * Для nested-типов, не входящих в {@code expandNestedQNames}, оставляет только сигнатуру
     * (без полей/методов/вложенных). Для «нужных» nested рекурсивно сохраняет полное дерево.
     */
    public List<StructureNode> pruneInternalNested(
            List<StructureNode> typeNodes,
            ClassStructure ownerCs,
            Set<String> expandNestedQNames) {
        if (typeNodes == null || typeNodes.isEmpty()) return typeNodes;
        return typeNodes.stream()
                .map(node -> pruneNode(node, ownerCs, expandNestedQNames))
                .toList();
    }

    private List<StructureNode> findNestedAmongChildren(List<StructureNode> nodes, String simpleName) {
        for (StructureNode node : nodes) {
            if (isTypeNode(node) && typeSimpleName(node).equals(simpleName)) {
                return List.of(node);
            }
            if (node.children() != null) {
                List<StructureNode> found = findNestedAmongChildren(node.children(), simpleName);
                if (!found.isEmpty()) return found;
            }
        }
        return List.of();
    }

    private static String typeSimpleName(StructureNode node) {
        return nameFromTypeSignature(node.signature(), node.type());
    }

    private static String nameFromTypeSignature(String signature, String kind) {
        String keyword = switch (kind) {
            case "class", "interface" -> kind;
            case "record" -> "record";
            case "enum" -> "enum";
            case "annotation" -> "@interface";
            default -> null;
        };
        if (keyword != null) {
            int idx = signature.indexOf(keyword);
            if (idx >= 0) {
                String rest = signature.substring(idx + keyword.length()).trim();
                StringBuilder name = new StringBuilder();
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (Character.isJavaIdentifierPart(c)) {
                        name.append(c);
                    } else {
                        break;
                    }
                }
                if (!name.isEmpty()) return name.toString();
            }
        }
        return fallbackSimpleName(signature);
    }

    private static String fallbackSimpleName(String signature) {
        String[] parts = signature.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    private StructureNode pruneNode(
            StructureNode node,
            ClassStructure ownerCs,
            Set<String> expandNestedQNames) {
        if (node.children() == null || node.children().isEmpty()) {
            return node;
        }
        List<StructureNode> prunedChildren = new ArrayList<>(node.children().size());
        for (StructureNode child : node.children()) {
            if (isTypeNode(child)) {
                ClassStructure nestedCs = findDirectNested(ownerCs, child);
                if (nestedCs != null) {
                    if (expandNestedQNames.contains(nestedCs.qualifiedName())) {
                        prunedChildren.add(pruneNode(child, nestedCs, expandNestedQNames));
                    } else {
                        prunedChildren.add(signatureOnly(child));
                    }
                    continue;
                }
            }
            prunedChildren.add(child);
        }
        return new StructureNode(node.type(), node.signature(), node.rows(), prunedChildren);
    }

    private static StructureNode signatureOnly(StructureNode node) {
        return new StructureNode(node.type(), node.signature(), node.rows(), null);
    }

    private static ClassStructure findDirectNested(ClassStructure owner, StructureNode typeChild) {
        if (owner == null || owner.nestedClasses() == null) return null;
        String simpleName = typeSimpleName(typeChild);
        return owner.nestedClasses().stream()
                .filter(n -> n.name().equals(simpleName))
                .findFirst()
                .orElse(null);
    }

    static boolean isTypeNode(StructureNode node) {
        return switch (node.type()) {
            case "class", "interface", "enum", "record", "annotation" -> true;
            default -> false;
        };
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
