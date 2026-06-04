package ru.kalinin.context.service;

import org.springframework.stereotype.Component;
import ru.kalinin.context.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Строит PlantUML class diagram из {@link ContextResponse}.
 *
 * <p>Для каждого {@link ClassContext} рисуется блок с членами из {@link StructureNode},
 * наследование/реализация — из сигнатуры типа, зависимости — по {@link ClassContext#callerIds()}.
 */
@Component
public class PlantUmlRenderer {

    private static final Pattern EXTENDS = Pattern.compile("\\bextends\\s+([\\w.$]+(?:<[^>]+>)?)");
    private static final Pattern IMPLEMENTS =
            Pattern.compile("\\bimplements\\s+(.+?)(?:\\s*\\{|\\s*$)");

    public String render(ContextResponse response) {
        List<ClassContext> classes = response.files().stream()
                .flatMap(f -> f.classes().stream())
                .sorted(Comparator.comparingInt(ClassContext::level).thenComparingInt(ClassContext::id))
                .toList();

        Map<Integer, String> idToName = classes.stream()
                .collect(Collectors.toMap(ClassContext::id, ClassContext::name, (a, b) -> a, LinkedHashMap::new));
        Map<String, String> nameToAlias = new LinkedHashMap<>();
        for (ClassContext ctx : classes) {
            nameToAlias.putIfAbsent(ctx.name(), alias(ctx.name()));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("hide empty members\n");
        sb.append("skinparam classAttributeIconSize 0\n\n");

        if (response.mergeRequest() != null) {
            String title = "MR !" + response.mergeRequest().iid();
            if (response.mergeRequest().title() != null && !response.mergeRequest().title().isBlank()) {
                title += " — " + response.mergeRequest().title();
            }
            sb.append("title ").append(quote(title)).append("\n\n");
        }

        Map<String, List<ClassContext>> byPackage = new TreeMap<>();
        for (ClassContext ctx : classes) {
            byPackage.computeIfAbsent(packageName(ctx.name()), k -> new ArrayList<>()).add(ctx);
        }

        for (Map.Entry<String, List<ClassContext>> entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            if (!pkg.isEmpty()) {
                sb.append("package ").append(quote(pkg)).append(" {\n");
            }
            for (ClassContext ctx : entry.getValue()) {
                renderClass(ctx, nameToAlias, sb, pkg.isEmpty() ? "" : "  ");
            }
            if (!pkg.isEmpty()) {
                sb.append("}\n\n");
            }
        }

        renderRelations(classes, idToName, nameToAlias, sb);

        sb.append("@enduml\n");
        return sb.toString();
    }

    private void renderClass(ClassContext ctx, Map<String, String> nameToAlias, StringBuilder sb, String indent) {
        List<StructureNode> nodes = structureNodes(ctx);
        if (nodes.isEmpty()) {
            renderEmptyClass(ctx, nameToAlias, sb, indent);
            sb.append('\n');
            return;
        }
        for (StructureNode node : nodes) {
            renderTypeNode(ctx, node, nameToAlias, sb, indent);
            sb.append("\n");
        }
    }

    private void renderTypeNode(ClassContext ctx, StructureNode node,
                                Map<String, String> nameToAlias, StringBuilder sb, String indent) {
        String alias = nameToAlias.get(ctx.name());
        String keyword = plantTypeKeyword(node.type());
        sb.append(indent).append(keyword).append(' ')
                .append(quote(ctx.name())).append(" as ").append(alias);

        String stereotype = stereotype(ctx);
        if (!stereotype.isEmpty()) {
            sb.append(' ').append(stereotype);
        }

        sb.append(" {\n");
        sb.append(indent).append("  .. meta ..\n");
        sb.append(indent).append("  level=").append(ctx.level());
        sb.append(" module=").append(escapeMember(ctx.module())).append('\n');
        if (!ctx.callerIds().isEmpty()) {
            sb.append(indent).append("  callers=").append(ctx.callerIds()).append('\n');
        }
        sb.append(indent).append("  --\n");
        String declaration = compactSignature(node.signature());
        if (!declaration.isEmpty()) {
            sb.append(indent).append("  ").append(escapeMember(declaration)).append('\n');
        }

        if (node.children() != null) {
            for (StructureNode child : node.children()) {
                if (isTypeContainer(child.type())) {
                    sb.append(indent).append("  {static} ")
                            .append(compactSignature(child.signature())).append('\n');
                } else {
                    sb.append(indent).append("  ")
                            .append(compactSignature(child.signature())).append('\n');
                }
            }
        }
        sb.append(indent).append("}\n");
    }

    private static void renderEmptyClass(ClassContext ctx, Map<String, String> nameToAlias,
                                         StringBuilder sb, String indent) {
        sb.append(indent).append("class ").append(quote(ctx.name()))
                .append(" as ").append(nameToAlias.get(ctx.name()))
                .append(stereotype(ctx)).append(" {\n");
        sb.append(indent).append("  .. no structure ..\n");
        sb.append(indent).append("}\n");
    }

    private void renderRelations(List<ClassContext> classes,
                                 Map<Integer, String> idToName,
                                 Map<String, String> nameToAlias,
                                 StringBuilder sb) {
        Set<String> drawn = new LinkedHashSet<>();

        for (ClassContext ctx : classes) {
            String toAlias = nameToAlias.get(ctx.name());
            for (StructureNode node : structureNodes(ctx)) {
                appendInheritance(node.signature(), toAlias, nameToAlias, drawn, sb);
            }
            for (Integer callerId : ctx.callerIds()) {
                String callerName = idToName.get(callerId);
                if (callerName == null) continue;
                String fromAlias = nameToAlias.get(callerName);
                if (fromAlias == null || toAlias == null) continue;
                String key = fromAlias + "->" + toAlias;
                if (drawn.add(key)) {
                    sb.append(fromAlias).append(" ..> ").append(toAlias).append(" : ref\n");
                }
            }
        }
    }

    private static void appendInheritance(String signature, String fromAlias,
                                          Map<String, String> nameToAlias,
                                          Set<String> drawn, StringBuilder sb) {
        String line = lastLine(signature);
        Matcher extendsMatcher = EXTENDS.matcher(line);
        if (extendsMatcher.find()) {
            String base = extendsMatcher.group(1);
            String toAlias = resolveTypeAlias(base, nameToAlias);
            String key = fromAlias + "--|>" + toAlias;
            if (drawn.add(key)) {
                sb.append(fromAlias).append(" --|> ").append(toAlias).append('\n');
            }
        }
        Matcher implementsMatcher = IMPLEMENTS.matcher(line);
        if (implementsMatcher.find()) {
            for (String iface : splitTypes(implementsMatcher.group(1))) {
                String toAlias = resolveTypeAlias(iface, nameToAlias);
                String key = fromAlias + "..|>" + toAlias;
                if (drawn.add(key)) {
                    sb.append(fromAlias).append(" ..|> ").append(toAlias).append('\n');
                }
            }
        }
    }

    private static String resolveTypeAlias(String typeName, Map<String, String> nameToAlias) {
        String simple = typeName.contains(".")
                ? typeName.substring(typeName.lastIndexOf('.') + 1)
                : typeName;
        simple = simple.replaceAll("<.*>", "").trim();
        for (Map.Entry<String, String> e : nameToAlias.entrySet()) {
            if (e.getKey().endsWith("." + simple) || e.getKey().equals(simple)) {
                return e.getValue();
            }
        }
        return alias(typeName.replaceAll("<.*>", "").trim());
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

    private static String stereotype(ClassContext ctx) {
        return " <<L,#E8F4FF>>";
    }

    private static String plantTypeKeyword(String type) {
        if (type == null) return "class";
        return switch (type) {
            case "interface" -> "interface";
            case "enum" -> "enum";
            case "annotation" -> "class";
            default -> "class";
        };
    }

    private static boolean isTypeContainer(String type) {
        return "class".equals(type) || "interface".equals(type) || "enum".equals(type)
                || "record".equals(type) || "annotation".equals(type);
    }

    private static String packageName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    static String alias(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "type_unknown";
        }
        String a = qualifiedName.replace('.', '_').replace('$', '_');
        if (!Character.isJavaIdentifierStart(a.charAt(0))) {
            a = "t_" + a;
        }
        return a;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "'") + "\"";
    }

    private static String escapeMember(String s) {
        return s.replace('\n', ' ').replace('}', ')');
    }

    private static String compactSignature(String signature) {
        if (signature == null) return "";
        return Arrays.stream(signature.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining(" "));
    }

    private static String lastLine(String signature) {
        if (signature == null) return "";
        String[] lines = signature.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }
}
