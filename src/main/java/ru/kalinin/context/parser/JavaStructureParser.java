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
import java.util.function.BiFunction;

/**
 * Парсит Java-исходники с помощью JavaParser и строит {@link ClassStructure}.
 *
 * <h3>resolveType работает в пять шагов</h3>
 * <ol>
 *   <li><b>Explicit import</b>: {@code import com.example.Foo} → {@code com.example.Foo}.</li>
 *   <li><b>Sibling / nested type</b>: все top-level типы файла и прямые nested типы
 *       каждого класса зарегистрированы в {@code importMap} через {@code putIfAbsent}.</li>
 *   <li><b>Wildcard resolver</b>: если есть {@code import pkg.*} —
 *       вызывается {@code wildcardResolver(simpleName, wildcardPackages)}.
 *       Если не нашёл — оставляет simple name.</li>
 *   <li><b>Same-package fallback</b>: если wildcard-импортов нет вовсе —
 *       добавляет префикс пакета файла.</li>
 *   <li><b>Частично-квалифицированный тип</b> ({@code Outer.Inner}): если левая часть
 *       ({@code Outer}) есть в {@code importMap} — подставляем префикс.
 *       Например: {@code Сообщение.Имена_Диалоговых_Окон} →
 *       {@code forms.general.dialog.Сообщение.Имена_Диалоговых_Окон}.</li>
 *   <li>Возвращает как есть (встроенные, void, already fully-qualified).</li>
 * </ol>
 *
 * <p>Парсер stateless — wildcardResolver передаётся снаружи при каждом вызове
 * {@link #parse} и не хранится в полях компонента.
 */
@Slf4j
@Component
public class JavaStructureParser {

    /**
     * Резолвер wildcard-импортов, не делающий ничего.
     * Сигнатура: (simpleName, wildcardPackages) → Optional&lt;qualifiedName&gt;
     */
    public static final BiFunction<String, List<String>, Optional<String>> NO_OP_RESOLVER =
            (simpleName, pkgs) -> Optional.empty();

    private final JavaParser parser;

    public JavaStructureParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(config);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Парсит исходник и возвращает список структур всех классов верхнего уровня.
     *
     * @param sourceCode       содержимое .java файла
     * @param sourceFile       путь к файлу (для метаданных)
     * @param contextLevel     уровень контекста (0 = изменённый, 1+ = зависимость)
     * @param wildcardResolver (симпль имя, список wildcard-пакетов) → qualified name;
     *                         если не нужен, используйте {@link #NO_OP_RESOLVER}
     */
    public List<ClassStructure> parse(String sourceCode, String sourceFile, int contextLevel,
                                      BiFunction<String, List<String>, Optional<String>> wildcardResolver) {
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
        List<String> wildcardPackages = buildWildcardPackages(cu);

        // Регистрируем все top-level типы файла (sibling non-public классы).
        // putIfAbsent — явный import всегда имеет приоритет.
        for (TypeDeclaration<?> td : cu.getTypes()) {
            String tdSimple = td.getNameAsString();
            String tdQualified = packageName.isEmpty() ? tdSimple : packageName + "." + tdSimple;
            importMap.putIfAbsent(tdSimple, tdQualified);
        }

        List<ClassStructure> structures = new ArrayList<>();
        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            structures.add(buildClassStructure(
                    typeDecl, packageName, importMap, wildcardPackages,
                    wildcardResolver, packageName, sourceFile, contextLevel));
        }
        return structures;
    }

    /**
     * Удобный перегруз без резолвера — для мест, где индекс недоступен.
     */
    public List<ClassStructure> parse(String sourceCode, String sourceFile, int contextLevel) {
        return parse(sourceCode, sourceFile, contextLevel, NO_OP_RESOLVER);
    }

    /**
     * Собирает все типы, упомянутые в структуре класса.
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

        types.removeIf(this::isBuiltinOrJavaLangType);
        return types;
    }

    // -------------------------------------------------------------------------
    // Private: build structure
    // -------------------------------------------------------------------------

    private ClassStructure buildClassStructure(TypeDeclaration<?> typeDecl,
                                               String packageName,
                                               Map<String, String> importMap,
                                               List<String> wildcardPackages,
                                               BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                                               String filePackage,
                                               String sourceFile,
                                               int contextLevel) {
        String simpleName = typeDecl.getNameAsString();
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        // Регистрируем прямые nested типы: SimpleName → OuterQName.SimpleName
        // putIfAbsent — явный import / sibling имеют приоритет
        for (BodyDeclaration<?> member : typeDecl.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType) {
                String nestedSimple = nestedType.getNameAsString();
                importMap.putIfAbsent(nestedSimple, qualifiedName + "." + nestedSimple);
            }
        }

        List<AnnotationInfo> annotations = extractAnnotations(typeDecl.getAnnotations());
        List<String> modifiers = extractModifiers(typeDecl.getModifiers());
        String kind = detectKind(typeDecl);

        String extendedType = null;
        List<String> implementedTypes = new ArrayList<>();
        List<String> typeParameters = new ArrayList<>();

        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) {
            typeParameters = coid.getTypeParameters().stream().map(Object::toString).toList();
            if (!coid.getExtendedTypes().isEmpty()) {
                extendedType = resolveType(coid.getExtendedTypes().get(0),
                        importMap, wildcardPackages, wildcardResolver, filePackage);
            }
            implementedTypes = coid.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap, wildcardPackages, wildcardResolver, filePackage))
                    .toList();

        } else if (typeDecl instanceof RecordDeclaration rd) {
            typeParameters = rd.getTypeParameters().stream().map(Object::toString).toList();
            implementedTypes = rd.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap, wildcardPackages, wildcardResolver, filePackage))
                    .toList();

        } else if (typeDecl instanceof EnumDeclaration ed) {
            implementedTypes = ed.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap, wildcardPackages, wildcardResolver, filePackage))
                    .toList();
        }

        List<FieldInfo> fields = typeDecl.getFields().stream()
                .flatMap(fd -> extractFields(fd, importMap, wildcardPackages, wildcardResolver, filePackage).stream())
                .toList();

        List<MethodInfo> methods = new ArrayList<>();
        typeDecl.getMethods().forEach(md ->
                methods.add(extractMethod(md, importMap, wildcardPackages, wildcardResolver, filePackage)));
        typeDecl.getConstructors().forEach(cd ->
                methods.add(extractConstructor(cd, importMap, wildcardPackages, wildcardResolver, filePackage)));

        if (typeDecl instanceof RecordDeclaration rd) {
            List<FieldInfo> recordComponents = rd.getParameters().stream()
                    .map(p -> new FieldInfo(
                            extractAnnotations(p.getAnnotations()),
                            List.of(),
                            resolveType(p.getType(), importMap, wildcardPackages, wildcardResolver, filePackage),
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
                        (TypeDeclaration<?>) m, qualifiedName,
                        importMap, wildcardPackages, wildcardResolver,
                        filePackage, sourceFile, contextLevel))
                .toList();

        return new ClassStructure(
                annotations, modifiers, kind, simpleName, qualifiedName,
                typeParameters, extendedType, implementedTypes,
                fields, methods, nested, sourceFile, contextLevel);
    }

    // -------------------------------------------------------------------------
    // Private: fields, methods, constructors
    // -------------------------------------------------------------------------

    private List<FieldInfo> extractFields(FieldDeclaration fd,
                                          Map<String, String> importMap,
                                          List<String> wildcardPackages,
                                          BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                                          String pkg) {
        List<AnnotationInfo> annotations = extractAnnotations(fd.getAnnotations());
        List<String> modifiers = extractModifiers(fd.getModifiers());
        String type = resolveType(fd.getElementType(), importMap, wildcardPackages, wildcardResolver, pkg);
        return fd.getVariables().stream()
                .map(v -> new FieldInfo(
                        annotations, modifiers, type, v.getNameAsString(),
                        v.getInitializer().map(Expression::toString).orElse(null)))
                .toList();
    }

    private MethodInfo extractMethod(MethodDeclaration md,
                                     Map<String, String> importMap,
                                     List<String> wildcardPackages,
                                     BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                                     String pkg) {
        return new MethodInfo(
                extractAnnotations(md.getAnnotations()),
                extractModifiers(md.getModifiers()),
                resolveType(md.getType(), importMap, wildcardPackages, wildcardResolver, pkg),
                md.getNameAsString(),
                md.getTypeParameters().stream().map(Object::toString).toList(),
                md.getParameters().stream()
                        .map(p -> new ParameterInfo(
                                extractAnnotations(p.getAnnotations()),
                                resolveType(p.getType(), importMap, wildcardPackages, wildcardResolver, pkg),
                                p.getNameAsString(), p.isVarArgs()))
                        .toList(),
                md.getThrownExceptions().stream()
                        .map(e -> resolveType(e, importMap, wildcardPackages, wildcardResolver, pkg)).toList(),
                false);
    }

    private MethodInfo extractConstructor(ConstructorDeclaration cd,
                                          Map<String, String> importMap,
                                          List<String> wildcardPackages,
                                          BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                                          String pkg) {
        return new MethodInfo(
                extractAnnotations(cd.getAnnotations()),
                extractModifiers(cd.getModifiers()),
                null,
                cd.getNameAsString(),
                cd.getTypeParameters().stream().map(Object::toString).toList(),
                cd.getParameters().stream()
                        .map(p -> new ParameterInfo(
                                extractAnnotations(p.getAnnotations()),
                                resolveType(p.getType(), importMap, wildcardPackages, wildcardResolver, pkg),
                                p.getNameAsString(), p.isVarArgs()))
                        .toList(),
                cd.getThrownExceptions().stream()
                        .map(e -> resolveType(e, importMap, wildcardPackages, wildcardResolver, pkg)).toList(),
                true);
    }

    // -------------------------------------------------------------------------
    // Private: import map & type resolution
    // -------------------------------------------------------------------------

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String qualified = imp.getNameAsString();
                String simpleName = qualified.contains(".")
                        ? qualified.substring(qualified.lastIndexOf('.') + 1)
                        : qualified;
                map.put(simpleName, qualified);
            }
        }
        return map;
    }

    private List<String> buildWildcardPackages(CompilationUnit cu) {
        List<String> packages = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() && !imp.isStatic()) {
                packages.add(imp.getNameAsString());
            }
        }
        return packages;
    }

    /**
     * Резолвит тип в qualified name.
     *
     * <p>Порядок шагов:
     * <ol>
     *   <li>Explicit import (+ sibling/nested через putIfAbsent).</li>
     *   <li>Wildcard resolver — если есть wildcard-импорты.</li>
     *   <li>Same-package fallback — если wildcard-импортов нет вовсе.</li>
     *   <li>Частично-квалифицированный тип (Outer.Inner) — если Outer есть в importMap.</li>
     *   <li>Возвращаем как есть.</li>
     * </ol>
     */
    private String resolveType(Type type,
                               Map<String, String> importMap,
                               List<String> wildcardPackages,
                               BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                               String filePackage) {
        String raw = type.asString();
        String base = raw.contains("<") ? raw.substring(0, raw.indexOf('<')).trim() : raw;
        String suffix = raw.contains("<") ? raw.substring(raw.indexOf('<')) : "";

        // 1. Explicit import (+ sibling/nested зарегистрированы через putIfAbsent)
        if (importMap.containsKey(base)) {
            return importMap.get(base) + suffix;
        }

        if (!base.contains(".") && !isBuiltinOrJavaLangType(base)) {
            // 2. Wildcard resolver
            if (!wildcardPackages.isEmpty()) {
                Optional<String> resolved = wildcardResolver.apply(base, wildcardPackages);
                return resolved.map(q -> q + suffix).orElse(base + suffix);
            }

            // 3. Same-package fallback — только если wildcard-импортов нет вовсе
            if (!filePackage.isEmpty()) {
                return filePackage + "." + base + suffix;
            }
        }

        // 4. Частично-квалифицированный тип: Outer.Inner[…] — если Outer есть в importMap.
        // Работает для любой глубины: Outer.Inner, Outer.Inner.Deep…
        // fully-qualified типы (com.example.Foo) не затрагиваются, т.к. "com" нет в importMap.
        int dot = base.indexOf('.');
        if (dot > 0) {
            String outerSimple = base.substring(0, dot);
            String rest = base.substring(dot); // ".Inner" или ".Inner.Deep"
            if (importMap.containsKey(outerSimple)) {
                return importMap.get(outerSimple) + rest + suffix;
            }
        }

        // 5. Возвращаем как есть
        return raw;
    }

    // -------------------------------------------------------------------------
    // Private: helpers
    // -------------------------------------------------------------------------

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
        if (typeDecl instanceof ClassOrInterfaceDeclaration coid)
            return coid.isInterface() ? "interface" : "class";
        if (typeDecl instanceof EnumDeclaration)
            return "enum";
        if (typeDecl instanceof AnnotationDeclaration)
            return "@interface";
        if (typeDecl instanceof RecordDeclaration)
            return "record";
        return "class";
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

    private boolean isBuiltinOrJavaLangType(String type) {
        if (type == null || type.isBlank()) return true;
        return switch (type) {
            case "void", "int", "long", "double", "float", "boolean",
                 "byte", "short", "char", "var" -> true;
            default -> JavaLangTypeRegistry.NAMES.contains(type)
                    || type.startsWith("java.") || type.startsWith("javax.")
                    || type.startsWith("jakarta.") || type.startsWith("sun.")
                    || type.endsWith("[]");
        };
    }
}
