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
 *
 * <p>resolveType работает в три шага:</p>
 * <ol>
 *   <li>explicit import: {@code import com.example.Foo} → {@code com.example.Foo}</li>
 *   <li>тот же пакет: если не найден в importMap и не является
 *       встроенным типом/java.*-типом,
 *       добавляет префикс пакета текущего файла</li>
 *   <li>возвращает simple name как есть (встроенные, void и т.д.)</li>
 * </ol>
 */
@Slf4j
@Component
public class JavaStructureParser {

    private final JavaParser parser;

    /**
     * Полный список simple-имён типов java.lang.*, которые не требуют явного import.
     * Включает классы, интерфейсы, аннотации из java.lang и стандартные исключения.
     */
    private static final Set<String> JAVA_LANG_SIMPLE_NAMES = Set.of(
            // Object hierarchy
            "Object", "Enum", "Record",
            // String / numeric wrappers
            "String", "Number",
            "Integer", "Long", "Double", "Float",
            "Boolean", "Byte", "Short", "Character",
            "StringBuilder", "StringBuffer",
            // Functional
            "Comparable", "Iterable", "Cloneable", "AutoCloseable", "Runnable",
            "Thread", "ThreadLocal",
            // Throwable hierarchy (java.lang.*)
            "Throwable",
            "Error",
            "AssertionError", "LinkageError", "VirtualMachineError",
            "Exception",
            "CloneNotSupportedException", "InterruptedException",
            "ReflectiveOperationException",
            "RuntimeException",
            "ArithmeticException", "ArrayIndexOutOfBoundsException",
            "ArrayStoreException", "ClassCastException",
            "ClassNotFoundException", "CloneNotSupportedException",
            "EnumConstantNotPresentException", "IllegalAccessException",
            "IllegalArgumentException", "IllegalMonitorStateException",
            "IllegalStateException", "IllegalThreadStateException",
            "IndexOutOfBoundsException", "InstantiationException",
            "NegativeArraySizeException", "NoSuchFieldException",
            "NoSuchMethodException", "NullPointerException",
            "NumberFormatException", "SecurityException",
            "StackOverflowError", "StringIndexOutOfBoundsException",
            "TypeNotPresentException", "UnsupportedOperationException",
            // annotations
            "Deprecated", "Override", "SuppressWarnings",
            "FunctionalInterface", "SafeVarargs"
    );

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
            structures.add(buildClassStructure(typeDecl, packageName, importMap, packageName, sourceFile, contextLevel));
        }
        return structures;
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

        types.removeIf(this::isBuiltinOrJavaLangType);
        return types;
    }

    // -------------------------------------------------------------------------
    // Private: build structure
    // -------------------------------------------------------------------------

    private ClassStructure buildClassStructure(
            TypeDeclaration<?> typeDecl,
            String packageName,
            Map<String, String> importMap,
            String filePackage,
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
                extendedType = resolveType(coid.getExtendedTypes().get(0), importMap, filePackage);
            }
            implementedTypes = coid.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap, filePackage)).toList();
        } else if (typeDecl instanceof RecordDeclaration rd) {
            typeParameters = rd.getTypeParameters().stream().map(Object::toString).toList();
            implementedTypes = rd.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap, filePackage)).toList();
        } else if (typeDecl instanceof EnumDeclaration ed) {
            implementedTypes = ed.getImplementedTypes().stream()
                    .map(t -> resolveType(t, importMap, filePackage)).toList();
        }

        List<FieldInfo> fields = typeDecl.getFields().stream()
                .flatMap(fd -> extractFields(fd, importMap, filePackage).stream())
                .toList();

        List<MethodInfo> methods = new ArrayList<>();
        typeDecl.getMethods().forEach(md -> methods.add(extractMethod(md, importMap, filePackage)));
        typeDecl.getConstructors().forEach(cd -> methods.add(extractConstructor(cd, importMap, filePackage)));

        if (typeDecl instanceof RecordDeclaration rd) {
            List<FieldInfo> recordComponents = rd.getParameters().stream()
                    .map(p -> new FieldInfo(
                            extractAnnotations(p.getAnnotations()),
                            List.of(),
                            resolveType(p.getType(), importMap, filePackage),
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
                        (TypeDeclaration<?>) m, qualifiedName, importMap, filePackage, sourceFile, contextLevel))
                .toList();

        return new ClassStructure(
                annotations, modifiers, kind, simpleName, qualifiedName,
                typeParameters, extendedType, implementedTypes,
                fields, methods, nested, sourceFile, contextLevel);
    }

    // -------------------------------------------------------------------------
    // Private: fields, methods, constructors
    // -------------------------------------------------------------------------

    private List<FieldInfo> extractFields(FieldDeclaration fd, Map<String, String> importMap, String pkg) {
        List<AnnotationInfo> annotations = extractAnnotations(fd.getAnnotations());
        List<String> modifiers = extractModifiers(fd.getModifiers());
        String type = resolveType(fd.getElementType(), importMap, pkg);
        return fd.getVariables().stream()
                .map(v -> new FieldInfo(
                        annotations, modifiers, type, v.getNameAsString(),
                        v.getInitializer().map(Expression::toString).orElse(null)))
                .toList();
    }

    private MethodInfo extractMethod(MethodDeclaration md, Map<String, String> importMap, String pkg) {
        return new MethodInfo(
                extractAnnotations(md.getAnnotations()),
                extractModifiers(md.getModifiers()),
                resolveType(md.getType(), importMap, pkg),
                md.getNameAsString(),
                md.getTypeParameters().stream().map(Object::toString).toList(),
                md.getParameters().stream()
                        .map(p -> new ParameterInfo(
                                extractAnnotations(p.getAnnotations()),
                                resolveType(p.getType(), importMap, pkg),
                                p.getNameAsString(), p.isVarArgs()))
                        .toList(),
                md.getThrownExceptions().stream()
                        .map(e -> resolveType(e, importMap, pkg)).toList(),
                false);
    }

    private MethodInfo extractConstructor(ConstructorDeclaration cd, Map<String, String> importMap, String pkg) {
        return new MethodInfo(
                extractAnnotations(cd.getAnnotations()),
                extractModifiers(cd.getModifiers()),
                null,
                cd.getNameAsString(),
                cd.getTypeParameters().stream().map(Object::toString).toList(),
                cd.getParameters().stream()
                        .map(p -> new ParameterInfo(
                                extractAnnotations(p.getAnnotations()),
                                resolveType(p.getType(), importMap, pkg),
                                p.getNameAsString(), p.isVarArgs()))
                        .toList(),
                cd.getThrownExceptions().stream()
                        .map(e -> resolveType(e, importMap, pkg)).toList(),
                true);
    }

    // -------------------------------------------------------------------------
    // Private: import map & type resolution
    // -------------------------------------------------------------------------

    /**
     * Строит карту simple name → qualified name из import-деклараций файла.
     * Учитываются только single-type imports (не wildcard, не static).
     */
    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new LinkedHashMap<>();
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

    /**
     * Резолвит тип в qualified name:
     * 1. explicit import map
     * 2. same-package fallback — только если тип не встроенный и не java.*
     * 3. simple name (встроенные, void, already-qualified)
     */
    private String resolveType(Type type, Map<String, String> importMap, String filePackage) {
        String raw = type.asString();
        String base = raw.contains("<") ? raw.substring(0, raw.indexOf('<')).trim() : raw;
        String suffix = raw.contains("<") ? raw.substring(raw.indexOf('<')) : "";

        if (importMap.containsKey(base)) {
            return importMap.get(base) + suffix;
        }

        if (!base.contains(".") && !filePackage.isEmpty() && !isBuiltinOrJavaLangType(base)) {
            return filePackage + "." + base + suffix;
        }

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
        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) return coid.isInterface() ? "interface" : "class";
        if (typeDecl instanceof EnumDeclaration) return "enum";
        if (typeDecl instanceof AnnotationDeclaration) return "@interface";
        if (typeDecl instanceof RecordDeclaration) return "record";
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

    /**
     * Тип является встроенным/java.lang.* если:
     * - это примитив, void или var;
     * - его simple name присутствует в {@link #JAVA_LANG_SIMPLE_NAMES};
     * - его qualified name начинается с java.* / javax.* / jakarta.* / sun.*;
     * - тип является массивом ([]).
     *
     * <p>Пользовательские типы (com.*, org.*, ru.* и т.д.) НЕ фильтруются.
     */
    private boolean isBuiltinOrJavaLangType(String type) {
        if (type == null || type.isBlank()) return true;
        return switch (type) {
            case "void", "int", "long", "double", "float", "boolean",
                 "byte", "short", "char", "var" -> true;
            default -> JAVA_LANG_SIMPLE_NAMES.contains(type)
                    || type.startsWith("java.") || type.startsWith("javax.")
                    || type.startsWith("jakarta.") || type.startsWith("sun.")
                    || type.endsWith("[]");
        };
    }
}
