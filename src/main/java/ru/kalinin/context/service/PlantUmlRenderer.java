package ru.kalinin.context.service;

import org.springframework.stereotype.Component;
import ru.kalinin.context.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Строит PlantUML class diagram из {@link ContextResponse} в классическом формате:
 *
 * <pre>
 * + class Foo {
 *   - field: Type
 *   + method(Param): ReturnType
 * }
 * Foo --> Bar
 * </pre>
 */
@Component
public class PlantUmlRenderer {

    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "volatile", "transient", "default", "native", "strictfp");

    /** Имя Java-типа (в т.ч. кириллица). */
    private static final String TYPE_NAME = "[\\p{L}_$][\\p{L}\\p{N}_$]*";

    private static final Pattern EXTENDS =
            Pattern.compile("\\bextends\\s+(" + TYPE_NAME + "(?:\\.[\\p{L}\\p{N}_$]+)*(?:<[^>]+>)?)");
    private static final Pattern IMPLEMENTS =
            Pattern.compile("\\bimplements\\s+(.+?)(?:\\s*\\{|\\s*$)");
    private static final Pattern ANNOTATION_INLINE =
            Pattern.compile("@\\w+(?:\\([^)]*\\))?\\s*");

    public String render(ContextResponse response) {
        List<ClassContext> classes = response.files().stream()
                .flatMap(f -> f.classes().stream())
                .sorted(Comparator.comparingInt(ClassContext::level).thenComparingInt(ClassContext::id))
                .toList();

        Map<Integer, String> idToSimple = classes.stream()
                .collect(Collectors.toMap(
                        ClassContext::id,
                        ctx -> simpleName(ctx.name()),
                        (a, b) -> a,
                        LinkedHashMap::new));

        Set<String> declaredSimpleNames = new LinkedHashSet<>();
        Map<String, String> qNameToSimple = new LinkedHashMap<>();
        for (ClassContext ctx : classes) {
            String simple = simpleName(ctx.name());
            qNameToSimple.put(ctx.name(), simple);
            declaredSimpleNames.add(simple);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");

        Set<String> rendered = new LinkedHashSet<>();
        for (ClassContext ctx : classes) {
            for (StructureNode node : structureNodes(ctx)) {
                String simple = simpleName(ctx.name());
                if (!rendered.add(ctx.name())) {
                    continue;
                }
                renderTypeBlock(ctx, node, simple, sb);
            }
        }

        renderRelations(classes, idToSimple, qNameToSimple, declaredSimpleNames, sb);

        sb.append("@enduml\n");
        return sb.toString();
    }

    private void renderTypeBlock(ClassContext ctx, StructureNode node, String simple, StringBuilder sb) {
        String header = PlantUmlSignatureConverter.typeHeader(node, simple);
        sb.append(header).append(" {\n");

        if (node.children() != null) {
            for (StructureNode child : node.children()) {
                String member = memberLine(child);
                if (member != null) {
                    sb.append("  ").append(member).append('\n');
                }
            }
        }

        sb.append("}\n\n");
    }

    private String memberLine(StructureNode child) {
        return switch (child.type()) {
            case "field" -> PlantUmlSignatureConverter.toField(child.signature());
            case "method", "constructor" -> PlantUmlSignatureConverter.toMethod(
                    child.signature(), "constructor".equals(child.type()));
            case "enum_constant" -> child.signature().trim();
            default -> null;
        };
    }

    private void renderRelations(List<ClassContext> classes,
                                 Map<Integer, String> idToSimple,
                                 Map<String, String> qNameToSimple,
                                 Set<String> declaredSimpleNames,
                                 StringBuilder sb) {
        Set<String> drawn = new LinkedHashSet<>();

        for (ClassContext ctx : classes) {
            String from = simpleName(ctx.name());
            for (StructureNode node : structureNodes(ctx)) {
                appendInheritance(from, node.signature(), qNameToSimple, declaredSimpleNames, drawn, sb);
                appendTypeReferences(from, node, qNameToSimple, declaredSimpleNames, drawn, sb);
            }
            for (Integer callerId : ctx.callerIds()) {
                String caller = idToSimple.get(callerId);
                if (caller == null) continue;
                String key = caller + "-->" + from;
                if (drawn.add(key)) {
                    sb.append(caller).append(" --> ").append(from).append('\n');
                }
            }
        }
    }

    private static void appendInheritance(String from, String signature,
                                          Map<String, String> qNameToSimple,
                                          Set<String> declaredSimpleNames,
                                          Set<String> drawn, StringBuilder sb) {
        String line = lastDeclarationLine(signature);
        Matcher extendsMatcher = EXTENDS.matcher(line);
        if (extendsMatcher.find()) {
            inheritanceLink(from, extendsMatcher.group(1).trim(), qNameToSimple, declaredSimpleNames,
                    drawn, sb, "--|>");
        }
        Matcher implementsMatcher = IMPLEMENTS.matcher(line);
        if (implementsMatcher.find()) {
            for (String iface : splitTypes(implementsMatcher.group(1))) {
                inheritanceLink(from, iface, qNameToSimple, declaredSimpleNames, drawn, sb, "..|>");
            }
        }
    }

    private static void inheritanceLink(String from, String typeRef,
                                        Map<String, String> qNameToSimple,
                                        Set<String> declaredSimpleNames,
                                        Set<String> drawn, StringBuilder sb, String arrow) {
        String target = simpleNameOfTypeRef(typeRef, qNameToSimple);
        if (target.isBlank() || target.equals(from)) return;
        if (!declaredSimpleNames.contains(target)) {
            sb.append("class ").append(target).append('\n');
            declaredSimpleNames.add(target);
        }
        String key = from + arrow + target;
        if (drawn.add(key)) {
            sb.append(from).append(' ').append(arrow).append(' ').append(target).append('\n');
        }
    }

    private static String simpleNameOfTypeRef(String typeRef, Map<String, String> qNameToSimple) {
        String normalized = typeRef.replaceAll("<[^>]*>", "").trim();
        if (normalized.isEmpty()) return "";
        for (Map.Entry<String, String> e : qNameToSimple.entrySet()) {
            if (e.getKey().endsWith("." + normalized) || e.getKey().equals(normalized)) {
                return e.getValue();
            }
        }
        if (normalized.contains(".")) {
            return normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        return normalized;
    }

    private static void appendTypeReferences(String from, StructureNode node,
                                             Map<String, String> qNameToSimple,
                                             Set<String> declaredSimpleNames,
                                             Set<String> drawn, StringBuilder sb) {
        if (node.children() == null) return;
        for (StructureNode child : node.children()) {
            if ("field".equals(child.type())) {
                String type = PlantUmlSignatureConverter.extractFieldType(child.signature());
                if (type != null && !type.isBlank()) {
                    link(from, type, qNameToSimple, declaredSimpleNames, drawn, sb, "-->");
                }
            } else if ("method".equals(child.type())) {
                PlantUmlSignatureConverter.extractReferencedTypes(child.signature())
                        .forEach(ref -> link(from, ref, qNameToSimple, declaredSimpleNames, drawn, sb, "-->"));
            } else if ("constructor".equals(child.type())) {
                PlantUmlSignatureConverter.extractParameterTypes(child.signature())
                        .forEach(ref -> link(from, ref, qNameToSimple, declaredSimpleNames, drawn, sb, "-->"));
            }
        }
    }

    private static void link(String from, String typeRef,
                             Map<String, String> qNameToSimple,
                             Set<String> declaredSimpleNames,
                             Set<String> drawn, StringBuilder sb, String arrow) {
        String target = resolveSimpleType(typeRef, qNameToSimple, declaredSimpleNames);
        if (target == null || target.equals(from)) return;
        String key = from + arrow + target;
        if (drawn.add(key)) {
            sb.append(from).append(' ').append(arrow).append(' ').append(target).append('\n');
        }
    }

    private static String resolveSimpleType(String typeRef,
                                            Map<String, String> qNameToSimple,
                                            Set<String> declaredSimpleNames) {
        String normalized = typeRef.replaceAll("<[^>]*>", "").trim();
        if (normalized.isEmpty()) return null;
        if (normalized.contains(".")) {
            String simple = normalized.substring(normalized.lastIndexOf('.') + 1);
            if (declaredSimpleNames.contains(simple)) return simple;
        }
        if (declaredSimpleNames.contains(normalized)) return normalized;
        for (Map.Entry<String, String> e : qNameToSimple.entrySet()) {
            if (e.getKey().endsWith("." + normalized) || e.getKey().equals(normalized)) {
                return e.getValue();
            }
        }
        return declaredSimpleNames.contains(normalized) ? normalized : null;
    }

    private static List<String> splitTypes(String list) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : list.toCharArray()) {
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private static List<StructureNode> structureNodes(ClassContext ctx) {
        return switch (ctx) {
            case UnchangedClassContext u -> nullToEmpty(u.structure());
            case ModifiedClassContext m -> {
                if (m.structureSource() != null) yield m.structureSource();
                if (m.structureTarget() != null) yield m.structureTarget();
                yield List.of();
            }
        };
    }

    private static List<StructureNode> nullToEmpty(List<StructureNode> nodes) {
        return nodes != null ? nodes : List.of();
    }

    static String simpleName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return "Unknown";
        int dollar = qualifiedName.lastIndexOf('$');
        int dot = qualifiedName.lastIndexOf('.');
        int cut = Math.max(dollar, dot);
        return cut < 0 ? qualifiedName : qualifiedName.substring(cut + 1);
    }

    static String lastDeclarationLine(String signature) {
        if (signature == null) return "";
        String[] lines = signature.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("@")) return line;
        }
        return "";
    }

    static String stripAnnotations(String signature) {
        if (signature == null) return "";
        String joined = Arrays.stream(signature.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("@"))
                .collect(Collectors.joining(" "));
        return ANNOTATION_INLINE.matcher(joined).replaceAll("").trim();
    }

    /** Конвертация Java-сигнатур в синтаксис PlantUML class diagram. */
    static final class PlantUmlSignatureConverter {

        static String typeHeader(StructureNode node, String simpleName) {
            String line = stripAnnotations(lastDeclarationLine(node.signature()));
            char visibility = visibilityChar(extractModifiersList(line));
            return switch (node.type()) {
                case "enum" -> "enum " + simpleName;
                case "interface" -> prefix(visibility) + "interface " + simpleName;
                case "record" -> prefix(visibility) + "class " + simpleName;
                case "annotation" -> prefix(visibility) + "class " + simpleName;
                default -> prefix(visibility) + "class " + simpleName;
            };
        }

        static String toField(String signature) {
            String line = stripAnnotations(signature);
            if (line.isEmpty()) return null;
            List<String> mods = extractModifiersList(line);
            char vis = visibilityChar(mods);
            String rest = removeModifiers(line);
            int eq = rest.indexOf('=');
            if (eq >= 0) rest = rest.substring(0, eq).trim();
            int lastSpace = rest.lastIndexOf(' ');
            if (lastSpace < 0) return null;
            String name = rest.substring(lastSpace + 1).trim();
            String type = rest.substring(0, lastSpace).trim();
            return prefix(vis) + name + ": " + simplifyType(type);
        }

        static String extractFieldType(String signature) {
            String line = stripAnnotations(signature);
            String rest = removeModifiers(line);
            int eq = rest.indexOf('=');
            if (eq >= 0) rest = rest.substring(0, eq).trim();
            int lastSpace = rest.lastIndexOf(' ');
            if (lastSpace < 0) return null;
            return rest.substring(0, lastSpace).trim();
        }

        static String toMethod(String signature, boolean constructor) {
            String line = stripAnnotations(signature);
            if (line.isEmpty()) return null;
            List<String> mods = extractModifiersList(line);
            char vis = visibilityChar(mods);

            int paren = line.indexOf('(');
            if (paren < 0) return null;

            int close = line.indexOf(')', paren);
            if (close < 0) return null;

            String before = line.substring(0, paren).trim();
            String params = line.substring(paren + 1, close);
            String throwsPart = "";
            int throwsIdx = line.indexOf("throws", paren);
            if (throwsIdx > 0) {
                throwsPart = line.substring(throwsIdx + 6).trim();
            }

            String name;
            String returnType;
            if (constructor) {
                name = lastToken(before);
                returnType = "void";
            } else {
                int lastSpace = before.lastIndexOf(' ');
                if (lastSpace < 0) return null;
                name = before.substring(lastSpace + 1).trim();
                returnType = before.substring(0, lastSpace).trim();
                returnType = removeModifiers(returnType);
                if (returnType.contains("<")) {
                    returnType = returnType.substring(returnType.lastIndexOf('>') + 1).trim();
                }
                if (returnType.isEmpty()) {
                    returnType = "void";
                }
            }

            String paramTypes = formatParamTypes(params);
            String result = prefix(vis) + name + '(' + paramTypes + "): " + simplifyType(returnType);
            if (!throwsPart.isEmpty()) {
                result += " throws " + simplifyType(throwsPart);
            }
            return result;
        }

        static List<String> extractReferencedTypes(String signature) {
            List<String> refs = extractParameterTypes(signature);
            String returnType = extractReturnType(signatureBeforeParams(signature));
            if (startsWithUpperCase(returnType)) {
                refs.add(returnType);
            }
            return refs;
        }

        static List<String> extractParameterTypes(String signature) {
            String line = stripAnnotations(signature);
            List<String> refs = new ArrayList<>();
            int paren = line.indexOf('(');
            if (paren < 0) return refs;
            int close = line.indexOf(')', paren);
            if (close < 0) return refs;
            String params = line.substring(paren + 1, close);
            for (String param : splitParams(params)) {
                String type = paramType(param);
                if (startsWithUpperCase(type)) {
                    refs.add(type);
                }
            }
            return refs;
        }

        private static String signatureBeforeParams(String signature) {
            String line = stripAnnotations(signature);
            int paren = line.indexOf('(');
            return paren < 0 ? line : line.substring(0, paren).trim();
        }

        /** Возвращает тип результата метода или {@code null} для {@code void} / конструктора. */
        private static String extractReturnType(String beforeParen) {
            if (beforeParen.isEmpty()) return null;
            String rt = removeModifiers(beforeParen);
            if (rt.contains("<")) {
                rt = rt.substring(rt.lastIndexOf('>') + 1).trim();
            }
            int sp = rt.lastIndexOf(' ');
            if (sp < 0) {
                return "void".equals(rt) ? null : rt;
            }
            String returnType = rt.substring(0, sp).trim();
            if (returnType.isEmpty() || "void".equals(returnType)) {
                return null;
            }
            return simplifyType(returnType);
        }

        private static boolean startsWithUpperCase(String s) {
            return s != null && !s.isEmpty() && Character.isUpperCase(s.charAt(0));
        }

        private static String formatParamTypes(String params) {
            if (params.isBlank()) return "";
            return splitParams(params).stream()
                    .map(PlantUmlSignatureConverter::paramType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
        }

        private static List<String> splitParams(String params) {
            List<String> result = new ArrayList<>();
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            for (char c : params.toCharArray()) {
                if (c == '<') depth++;
                if (c == '>') depth--;
                if (c == ',' && depth == 0) {
                    result.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            if (!cur.isEmpty()) result.add(cur.toString().trim());
            return result;
        }

        private static String paramType(String param) {
            String p = param.trim();
            int last = p.lastIndexOf(' ');
            if (last < 0) return simplifyType(p);
            return simplifyType(p.substring(0, last).trim());
        }

        private static String simplifyType(String type) {
            if (type == null || type.isBlank()) return "";
            String t = type.replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) return "";
            if (t.contains(".")) {
                return t.substring(t.lastIndexOf('.') + 1);
            }
            return t;
        }

        private static String prefix(char visibility) {
            return visibility == '\0' ? "" : visibility + " ";
        }

        private static char visibilityChar(List<String> modifiers) {
            if (modifiers.contains("private")) return '-';
            if (modifiers.contains("protected")) return '#';
            if (modifiers.contains("public")) return '+';
            return '~';
        }

        private static char visibilityChar(String modifiers) {
            return visibilityChar(extractModifiersList(modifiers));
        }

        private static List<String> extractModifiersList(String line) {
            List<String> mods = new ArrayList<>();
            for (String word : line.split("\\s+")) {
                if (MODIFIERS.contains(word)) mods.add(word);
            }
            return mods;
        }

        private static String removeModifiers(String line) {
            String result = line;
            for (String mod : MODIFIERS) {
                result = result.replaceAll("\\b" + mod + "\\b\\s*", "");
            }
            return result.trim();
        }

        private static String lastToken(String s) {
            int sp = s.lastIndexOf(' ');
            return sp < 0 ? s : s.substring(sp + 1);
        }
    }
}
