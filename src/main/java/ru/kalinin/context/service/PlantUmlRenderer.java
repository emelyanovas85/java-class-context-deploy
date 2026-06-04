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

    private static final String STEREO_SOURCE_ONLY = " <<source only>>";
    private static final String STEREO_TARGET_ONLY = " <<target only>>";
    private static final String STEREO_CHANGED = " <<changed>>";

    private enum MemberBranch { BOTH, SOURCE_ONLY, TARGET_ONLY, CHANGED }

    private record MemberRender(StructureNode node, MemberBranch branch) {}

    public String render(ContextResponse response) {
        return render(response, true);
    }

    public String render(ContextResponse response, boolean pretty) {
        return format(renderDiagram(response), pretty);
    }

    private String renderDiagram(ContextResponse response) {
        List<ClassContext> classes = response.files().stream()
                .flatMap(f -> f.classes().stream())
                .sorted(Comparator.comparingInt(ClassContext::level).thenComparingInt(ClassContext::id))
                .toList();

        Map<String, String> qNameToDisplay = buildDisplayNames(classes);

        Map<Integer, String> idToDisplay = classes.stream()
                .collect(Collectors.toMap(
                        ClassContext::id,
                        ctx -> qNameToDisplay.get(ctx.name()),
                        (a, b) -> a,
                        LinkedHashMap::new));

        Set<String> declaredDisplayNames = new LinkedHashSet<>(qNameToDisplay.values());

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");

        boolean hasBranchStereotypes = false;
        Set<String> rendered = new LinkedHashSet<>();
        for (ClassContext ctx : classes) {
            StructureNode typeNode = primaryTypeNode(ctx);
            if (typeNode == null || !rendered.add(ctx.name())) {
                continue;
            }
            if (renderTypeBlock(ctx, typeNode, qNameToDisplay.get(ctx.name()), sb)) {
                hasBranchStereotypes = true;
            }
        }

        if (hasBranchStereotypes) {
            sb.append("legend right\n");
            sb.append("  <<source only>> — только в source-ветке MR\n");
            sb.append("  <<target only>> — только в target-ветке\n");
            sb.append("  <<changed>> — есть в обеих, сигнатура различается\n");
            sb.append("end legend\n\n");
        }

        renderRelations(classes, idToDisplay, qNameToDisplay, declaredDisplayNames, sb);

        sb.append("@enduml\n");
        return sb.toString();
    }

    /**
     * {@code pretty=false}: убирает отступы, пустые строки, пробел после {@code +-\~#},
     * пробелы вокруг стрелок связей.
     */
    static String format(String uml, boolean pretty) {
        if (pretty) {
            return uml;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : uml.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                sb.append(compactLine(trimmed)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String compactLine(String line) {
        if (line.length() >= 2) {
            char first = line.charAt(0);
            if (first == '+' || first == '-' || first == '#' || first == '~') {
                if (line.charAt(1) == ' ') {
                    line = first + line.substring(2);
                }
            }
        }
        return line
                .replace(" ..|> ", "..|>")
                .replace(" --|> ", "--|>")
                .replace(" ..> ", "..>")
                .replace(" --> ", "-->");
    }

    /** @return {@code true}, если на диаграмме есть стереотипы веток */
    private boolean renderTypeBlock(ClassContext ctx, StructureNode node, String displayName, StringBuilder sb) {
        String header = PlantUmlSignatureConverter.typeHeader(node, displayName);
        header += classBranchStereotype(ctx);
        sb.append(header).append(" {\n");

        boolean hasStereotypes = !classBranchStereotype(ctx).isEmpty();
        for (MemberRender member : collectMembers(ctx, node)) {
            String line = memberLine(member.node());
            if (line == null) continue;
            String stereo = memberBranchStereotype(member.branch());
            if (!stereo.isEmpty()) hasStereotypes = true;
            sb.append("  ").append(line).append(stereo).append('\n');
        }

        sb.append("}\n\n");
        return hasStereotypes;
    }

    private static String classBranchStereotype(ClassContext ctx) {
        if (!(ctx instanceof ModifiedClassContext m)) return "";
        if (m.structureSource() == null) return STEREO_TARGET_ONLY;
        if (m.structureTarget() == null) return STEREO_SOURCE_ONLY;
        return "";
    }

    private static String memberBranchStereotype(MemberBranch branch) {
        return switch (branch) {
            case SOURCE_ONLY -> STEREO_SOURCE_ONLY;
            case TARGET_ONLY -> STEREO_TARGET_ONLY;
            case CHANGED -> STEREO_CHANGED;
            case BOTH -> "";
        };
    }

    private List<MemberRender> collectMembers(ClassContext ctx, StructureNode typeNode) {
        if (ctx instanceof UnchangedClassContext) {
            return memberChildren(typeNode).stream()
                    .map(n -> new MemberRender(n, MemberBranch.BOTH))
                    .toList();
        }
        ModifiedClassContext m = (ModifiedClassContext) ctx;
        StructureNode srcRoot = rootOf(m.structureSource());
        StructureNode tgtRoot = rootOf(m.structureTarget());

        Map<String, StructureNode> srcByKey = indexMembers(srcRoot);
        Map<String, StructureNode> tgtByKey = indexMembers(tgtRoot);

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(srcByKey.keySet());
        keys.addAll(tgtByKey.keySet());

        List<MemberRender> result = new ArrayList<>();
        for (String key : keys) {
            StructureNode src = srcByKey.get(key);
            StructureNode tgt = tgtByKey.get(key);
            if (src != null && tgt == null) {
                result.add(new MemberRender(src, MemberBranch.SOURCE_ONLY));
            } else if (src == null && tgt != null) {
                result.add(new MemberRender(tgt, MemberBranch.TARGET_ONLY));
            } else if (src != null) {
                MemberBranch branch = memberSignatureKey(src).equals(memberSignatureKey(tgt))
                        ? MemberBranch.BOTH
                        : MemberBranch.CHANGED;
                result.add(new MemberRender(src, branch));
            }
        }
        return result;
    }

    private static StructureNode rootOf(List<StructureNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        return nodes.get(0);
    }

    private static List<StructureNode> memberChildren(StructureNode typeNode) {
        if (typeNode == null || typeNode.children() == null) return List.of();
        return typeNode.children().stream()
                .filter(c -> !isTypeContainer(c.type()))
                .toList();
    }

    private static Map<String, StructureNode> indexMembers(StructureNode typeNode) {
        Map<String, StructureNode> map = new LinkedHashMap<>();
        for (StructureNode child : memberChildren(typeNode)) {
            map.putIfAbsent(memberKey(child), child);
        }
        return map;
    }

    /** Логический ключ члена (имя поля/метода/константы), без типов параметров. */
    static String memberKey(StructureNode node) {
        return node.type() + "|" + memberLogicalId(node);
    }

    private static String memberSignatureKey(StructureNode node) {
        return normalizeSignature(node.signature());
    }

    private static String memberLogicalId(StructureNode node) {
        String sig = stripAnnotations(node.signature());
        return switch (node.type()) {
            case "enum_constant" -> sig.trim();
            case "field" -> fieldLogicalId(sig);
            case "method", "constructor" -> methodLogicalId(sig);
            default -> normalizeSignature(node.signature());
        };
    }

    private static String fieldLogicalId(String line) {
        String rest = PlantUmlSignatureConverter.removeModifiersPublic(line);
        int eq = rest.indexOf('=');
        if (eq >= 0) rest = rest.substring(0, eq).trim();
        int sp = rest.lastIndexOf(' ');
        return sp < 0 ? rest : rest.substring(sp + 1).trim();
    }

    private static String methodLogicalId(String line) {
        int paren = line.indexOf('(');
        if (paren < 0) return normalizeSignature(line);
        String before = line.substring(0, paren).trim();
        return before.substring(before.lastIndexOf(' ') + 1).trim();
    }

    private static String normalizeSignature(String signature) {
        return stripAnnotations(signature).replaceAll("\\s+", " ").trim();
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

    private static StructureNode primaryTypeNode(ClassContext ctx) {
        return switch (ctx) {
            case UnchangedClassContext u -> rootOf(nullToEmpty(u.structure()));
            case ModifiedClassContext m -> {
                if (m.structureSource() != null) yield rootOf(m.structureSource());
                if (m.structureTarget() != null) yield rootOf(m.structureTarget());
                yield null;
            }
        };
    }

    private void renderRelations(List<ClassContext> classes,
                                 Map<Integer, String> idToDisplay,
                                 Map<String, String> qNameToDisplay,
                                 Set<String> declaredDisplayNames,
                                 StringBuilder sb) {
        Set<String> drawn = new LinkedHashSet<>();

        for (ClassContext ctx : classes) {
            String from = qNameToDisplay.get(ctx.name());
            StructureNode primary = primaryTypeNode(ctx);
            if (primary != null) {
                appendInheritance(from, primary.signature(), qNameToDisplay, declaredDisplayNames, drawn, sb);
                if (ctx instanceof ModifiedClassContext m) {
                    appendTypeReferencesMerged(from, m, qNameToDisplay, declaredDisplayNames, drawn, sb);
                } else {
                    appendTypeReferences(from, primary, qNameToDisplay, declaredDisplayNames, drawn, sb);
                }
            }
            for (Integer callerId : ctx.callerIds()) {
                String caller = idToDisplay.get(callerId);
                if (caller == null) continue;
                String key = caller + "-->" + from;
                if (drawn.add(key)) {
                    sb.append(caller).append(" --> ").append(from).append('\n');
                }
            }
        }
    }

    private static void appendInheritance(String from, String signature,
                                          Map<String, String> qNameToDisplay,
                                          Set<String> declaredDisplayNames,
                                          Set<String> drawn, StringBuilder sb) {
        String line = lastDeclarationLine(signature);
        Matcher extendsMatcher = EXTENDS.matcher(line);
        if (extendsMatcher.find()) {
            inheritanceLink(from, extendsMatcher.group(1).trim(), qNameToDisplay, declaredDisplayNames,
                    drawn, sb, "--|>");
        }
        Matcher implementsMatcher = IMPLEMENTS.matcher(line);
        if (implementsMatcher.find()) {
            for (String iface : splitTypes(implementsMatcher.group(1))) {
                inheritanceLink(from, iface, qNameToDisplay, declaredDisplayNames, drawn, sb, "..|>");
            }
        }
    }

    private static void inheritanceLink(String from, String typeRef,
                                        Map<String, String> qNameToDisplay,
                                        Set<String> declaredDisplayNames,
                                        Set<String> drawn, StringBuilder sb, String arrow) {
        String target = displayNameOfTypeRef(typeRef, qNameToDisplay);
        if (target.isBlank() || target.equals(from)) return;
        if (!declaredDisplayNames.contains(target)) {
            sb.append("class ").append(target).append('\n');
            declaredDisplayNames.add(target);
        }
        String key = from + arrow + target;
        if (drawn.add(key)) {
            sb.append(from).append(' ').append(arrow).append(' ').append(target).append('\n');
        }
    }

    private static String displayNameOfTypeRef(String typeRef, Map<String, String> qNameToDisplay) {
        String normalized = typeRef.replaceAll("<[^>]*>", "").trim();
        if (normalized.isEmpty()) return "";
        String fromDiagram = resolveDisplayType(normalized, qNameToDisplay);
        if (fromDiagram != null) return fromDiagram;
        if (normalized.contains(".")) {
            return normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        return normalized;
    }

    private static void appendTypeReferences(String from, StructureNode node,
                                             Map<String, String> qNameToDisplay,
                                             Set<String> declaredDisplayNames,
                                             Set<String> drawn, StringBuilder sb) {
        for (StructureNode child : memberChildren(node)) {
            appendTypeReferencesForMember(from, child, qNameToDisplay, declaredDisplayNames, drawn, sb);
        }
    }

    private static void appendTypeReferencesMerged(String from, ModifiedClassContext m,
                                                   Map<String, String> qNameToDisplay,
                                                   Set<String> declaredDisplayNames,
                                                   Set<String> drawn, StringBuilder sb) {
        StructureNode src = rootOf(m.structureSource());
        StructureNode tgt = rootOf(m.structureTarget());
        if (src != null) appendTypeReferences(from, src, qNameToDisplay, declaredDisplayNames, drawn, sb);
        if (tgt != null && tgt != src) appendTypeReferences(from, tgt, qNameToDisplay, declaredDisplayNames, drawn, sb);
    }

    private static void appendTypeReferencesForMember(String from, StructureNode child,
                                                      Map<String, String> qNameToDisplay,
                                                      Set<String> declaredDisplayNames,
                                                      Set<String> drawn, StringBuilder sb) {
        if ("field".equals(child.type())) {
            String type = PlantUmlSignatureConverter.extractFieldType(child.signature());
            if (type != null && !type.isBlank()) {
                link(from, type, qNameToDisplay, declaredDisplayNames, drawn, sb, "-->");
            }
        } else if ("method".equals(child.type())) {
            PlantUmlSignatureConverter.extractReferencedTypes(child.signature())
                    .forEach(ref -> link(from, ref, qNameToDisplay, declaredDisplayNames, drawn, sb, "-->"));
        } else if ("constructor".equals(child.type())) {
            PlantUmlSignatureConverter.extractParameterTypes(child.signature())
                    .forEach(ref -> link(from, ref, qNameToDisplay, declaredDisplayNames, drawn, sb, "-->"));
        }
    }

    private static void link(String from, String typeRef,
                             Map<String, String> qNameToDisplay,
                             Set<String> declaredDisplayNames,
                             Set<String> drawn, StringBuilder sb, String arrow) {
        String target = resolveDisplayType(typeRef, qNameToDisplay, declaredDisplayNames);
        if (target == null || target.equals(from)) return;
        String key = from + arrow + target;
        if (drawn.add(key)) {
            sb.append(from).append(' ').append(arrow).append(' ').append(target).append('\n');
        }
    }

    private static String resolveDisplayType(String typeRef,
                                             Map<String, String> qNameToDisplay,
                                             Set<String> declaredDisplayNames) {
        String normalized = typeRef.replaceAll("<[^>]*>", "").trim();
        if (normalized.isEmpty()) return null;
        String fromDiagram = resolveDisplayType(normalized, qNameToDisplay);
        if (fromDiagram != null) return fromDiagram;
        String simple = normalized.contains(".")
                ? normalized.substring(normalized.lastIndexOf('.') + 1)
                : normalized;
        return declaredDisplayNames.contains(simple) ? simple : null;
    }

    /** Ищет тип среди классов диаграммы; при единственном совпадении возвращает display name. */
    private static String resolveDisplayType(String normalized, Map<String, String> qNameToDisplay) {
        if (qNameToDisplay.containsKey(normalized)) {
            return qNameToDisplay.get(normalized);
        }
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, String> e : qNameToDisplay.entrySet()) {
            if (e.getKey().endsWith("." + normalized)) {
                matches.add(e.getValue());
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return null;
    }

    /**
     * qualified name → имя на диаграмме: simple name, если он уникален среди классов ответа,
     * иначе полный qualified name.
     */
    static Map<String, String> buildDisplayNames(List<ClassContext> classes) {
        Map<String, Long> simpleCounts = classes.stream()
                .collect(Collectors.groupingBy(ctx -> simpleName(ctx.name()), Collectors.counting()));

        Set<String> ambiguousSimple = simpleCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> result = new LinkedHashMap<>();
        for (ClassContext ctx : classes) {
            String qName = ctx.name();
            String simple = simpleName(qName);
            result.put(qName, ambiguousSimple.contains(simple) ? qName : simple);
        }
        return result;
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

    private static List<StructureNode> nullToEmpty(List<StructureNode> nodes) {
        return nodes != null ? nodes : List.of();
    }

    private static boolean isTypeContainer(String type) {
        return "class".equals(type) || "interface".equals(type) || "enum".equals(type)
                || "record".equals(type) || "annotation".equals(type);
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
            return removeModifiersPublic(line);
        }

        static String removeModifiersPublic(String line) {
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
