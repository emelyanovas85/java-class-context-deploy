package ru.kalinin.context.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.kalinin.context.model.*;

import java.util.*;

/**
 * Парсит Java-исходники с помощью JavaParser и строит {@link ClassStructure}.
 */
@Slf4j
@Component
public class JavaStructureParser {

    private final JavaParser parser;

    public JavaStructureParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(config);
    }

    /**
     * Парсит исходник и возвращает список структур всех классов верхнего уровня.
     *
     * @param sourceCode   содержимое .java файла
     * @param sourceFile   путь к файлу (для метаданных)
     * @param contextLevel уровень контекста (0 = изменённый, 1+ = зависимость)
     */
    public List<ClassStructure> parse(String sourceCode, String sourceFile, int contextLevel) {
        ParseResult<CompilationUnit> result = parser.parse(sourceCode);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse {}: {}", sourceFile, result.getProblems());
            return List.of();
        }

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        Map<String, String> importMap = buildImportMap(cu);

        List<ClassStructure> structures = new ArrayList<>();
        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            structures.add(buildClassStructure(typeDecl, packageName, importMap, sourceFile, contextLevel));
        }
        return structures;
    }

    private ClassStructure buildClassStructure(
            TypeDeclaration<?> typeDecl,
            String packageName,
            Map<String, String> importMap,
            String sourceFile,
            int contextLevel) {

        String simpleName = typeDecl.getNameAsString();
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        List<AnnotationInfo> annotations = extractAnnotations(typeDecl.getAnnotations());
        List<String> modifiers = extractModifiers(typeDecl.getModifiers());
        String kind = detectKind(typeDecl);

        String extendedType = null;
        List<String> implementedTypes = new ArrayList<>();
        List<String> typeParameters = new ArrayList<>();

        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) {
            typeParameters = coid.getTypeParameters().stream().map(Object::toString).toList();
            if (!coid.getExtendedTypes().isEmpty()) {
                extendedType = resolveType(coid.getExtendedTypes().get(0), importMap);
            }
            implementedTypes = coid.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap)).toList();
        } else if (typeDecl instanceof RecordDeclaration rd) {
            typeParameters = rd.getTypeParameters().stream().map(Object::toString).toList();
            implementedTypes = rd.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap)).toList();
        } else if (typeDecl instanceof EnumDeclaration ed) {
            implementedTypes = ed.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap)).toList();
        }

        List<FieldInfo> fields = typeDecl.getFields().stream()
                .flatMap(fd -> extractFields(fd, importMap).stream())
                .toList();

        List<MethodInfo> methods = new ArrayList<>();
        typeDecl.getMethods().forEach(md -> methods.add(extractMethod(md, importMap)));
        typeDecl.getConstructors().forEach(cd -> methods.add(extractConstructor(cd, importMap)));

        // Record components as pseudo-fields
        if (typeDecl instanceof RecordDeclaration rd) {
            List<FieldInfo> recordComponents = rd.getParameters().stream()
                    .map(p -> new FieldInfo(
                            extractAnnotations(p.getAnnotations()),
                            List.of(),
                            resolveType(p.getType(), importMap),
                            p.getNameAsString(),
                            null))
                    .toList();
            List<FieldInfo> combined = new ArrayList<>(recordComponents);
            combined.addAll(fields);
            fields = combined;
        }

        List<ClassStructure> nested = typeDecl.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration)
                .map(m -> buildClassStructure(
                        (TypeDeclaration<?>) m, qualifiedName, importMap, sourceFile, contextLevel))
                .toList();

        return new ClassStructure(
                annotations, modifiers, kind, simpleName, qualifiedName,
                typeParameters, extendedType, implementedTypes,
                fields, methods, nested, sourceFile, contextLevel);
    }

    private List<FieldInfo> extractFields(FieldDeclaration fd, Map<String, String> importMap) {
        List<AnnotationInfo> annotations = extractAnnotations(fd.getAnnotations());
        List<String> modifiers = extractModifiers(fd.getModifiers());
        String type = resolveType(fd.getElementType(), importMap);
        return fd.getVariables().stream()
                .map(v -> new FieldInfo(
                        annotations, modifiers, type, v.getNameAsString(),
                        v.getInitializer().map(Expression::toString).orElse(null)))
                .toList();
    }

    private MethodInfo extractMethod(MethodDeclaration md, Map<String, String> importMap) {
        return new MethodInfo(
                extractAnnotations(md.getAnnotations()),
                extractModifiers(md.getModifiers()),
                resolveType(md.getType(), importMap),
                md.getNameAsString(),
                md.getTypeParameters().stream().map(Object::toString).toList(),
                md.getParameters().stream()
                        .map(p -> new ParameterInfo(
                                extractAnnotations(p.getAnnotations()),
                                resolveType(p.getType(), importMap),
                                p.getNameAsString(), p.isVarArgs()))
                        .toList(),
                md.getThrownExceptions().stream()
                        .map(e -> resolveType(e, importMap)).toList(),
                false);
    }

    private MethodInfo extractConstructor(ConstructorDeclaration cd, Map<String, String> importMap) {
        return new MethodInfo(
                extractAnnotations(cd.getAnnotations()),
                extractModifiers(cd.getModifiers()),
                null,
                cd.getNameAsString(),
                cd.getTypeParameters().stream().map(Object::toString).toList(),
                cd.getParameters().stream()
                        .map(p -> new ParameterInfo(
                                extractAnnotations(p.getAnnotations()),
                                resolveType(p.getType(), importMap),
                                p.getNameAsString(), p.isVarArgs()))
                        .toList(),
                cd.getThrownExceptions().stream()
                        .map(e -> resolveType(e, importMap)).toList(),
                true);
    }

    private List<AnnotationInfo> extractAnnotations(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .map(a -> new AnnotationInfo(a.getNameAsString(), extractAnnotationAttributes(a)))
                .toList();
    }

    private Map<String, String> extractAnnotationAttributes(AnnotationExpr annotation) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (annotation instanceof NormalAnnotationExpr nae) {
            nae.getPairs().forEach(pair -> attrs.put(pair.getNameAsString(), pair.getValue().toString()));
        } else if (annotation instanceof SingleMemberAnnotationExpr smae) {
            attrs.put("value", smae.getMemberValue().toString());
        }
        return attrs;
    }

    private List<String> extractModifiers(NodeList<com.github.javaparser.ast.Modifier> modifiers) {
        return modifiers.stream().map(m -> m.getKeyword().asString()).toList();
    }

    private String detectKind(TypeDeclaration<?> typeDecl) {
        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) return coid.isInterface() ? "interface" : "class";
        if (typeDecl instanceof EnumDeclaration) return "enum";
        if (typeDecl instanceof AnnotationDeclaration) return "@interface";
        if (typeDecl instanceof RecordDeclaration) return "record";
        return "class";
    }

    private String resolveType(Type type, Map<String, String> importMap) {
        String raw = type.asString();
        String base = raw.contains("<") ? raw.substring(0, raw.indexOf('<')).trim() : raw;
        String resolved = importMap.getOrDefault(base, base);
        return raw.contains("<") ? resolved + raw.substring(raw.indexOf('<')) : resolved;
    }

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String qn = imp.getNameAsString();
                String sn = qn.contains(".") ? qn.substring(qn.lastIndexOf('.') + 1) : qn;
                map.put(sn, qn);
            }
        }
        return map;
    }

    /**
     * Собирает все типы, упомянутые в структуре класса (для построения следующего уровня контекста).
     */
    public Set<String> collectReferencedTypes(ClassStructure cs) {
        Set<String> types = new LinkedHashSet<>();

        cs.annotations().forEach(a -> types.add(a.name()));
        if (cs.extendedType() != null) types.add(stripGenerics(cs.extendedType()));
        cs.implementedTypes().forEach(t -> types.add(stripGenerics(t)));

        cs.fields().forEach(f -> {
            types.add(stripGenerics(f.type()));
            f.annotations().forEach(a -> types.add(a.name()));
        });

        cs.methods().forEach(m -> {
            if (m.returnType() != null) types.add(stripGenerics(m.returnType()));
            m.parameters().forEach(p -> {
                types.add(stripGenerics(p.type()));
                p.annotations().forEach(a -> types.add(a.name()));
            });
            m.thrownExceptions().forEach(e -> types.add(stripGenerics(e)));
            m.typeParameters().forEach(tp -> extractTypeParamBounds(tp, types));
            m.annotations().forEach(a -> types.add(a.name()));
        });

        cs.nestedClasses().forEach(nc -> types.addAll(collectReferencedTypes(nc)));
        types.removeIf(this::isBuiltinType);
        return types;
    }

    private String stripGenerics(String type) {
        if (type == null) return "";
        int idx = type.indexOf('<');
        return idx >= 0 ? type.substring(0, idx).trim() : type.trim();
    }

    private void extractTypeParamBounds(String typeParam, Set<String> types) {
        if (typeParam.contains("extends")) {
            String bounds = typeParam.substring(typeParam.indexOf("extends") + 7).trim();
            for (String bound : bounds.split("&")) {
                types.add(stripGenerics(bound.trim()));
            }
        }
    }

    private boolean isBuiltinType(String type) {
        if (type == null || type.isBlank()) return true;
        return switch (type) {
            case "void", "int", "long", "double", "float", "boolean",
                 "byte", "short", "char", "var", "Object", "String",
                 "Integer", "Long", "Double", "Float", "Boolean",
                 "Byte", "Short", "Character", "Number" -> true;
            default -> type.startsWith("java.") || type.startsWith("javax.")
                    || type.endsWith("[]") || type.contains(".");
        };
    }
}
