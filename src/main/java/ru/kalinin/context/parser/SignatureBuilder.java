package ru.kalinin.context.parser;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ReferenceType;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Строит строковые сигнатуры узлов AST для {@link ru.kalinin.context.model.StructureNode}.
 *
 * <p>Аннотации выносятся на отдельные строки перед сигнатурой через {@code \n}.
 * Тела методов/конструкторов не включаются.
 */
@Component
public class SignatureBuilder {

    // ── Типы (class / interface / enum / record / @interface) ─────────────

    public String classSignature(ClassOrInterfaceDeclaration n) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, n.getAnnotations());
        appendModifiers(sb, n.getModifiers());
        sb.append(n.isInterface() ? "interface" : "class").append(' ').append(n.getNameAsString());
        if (!n.getTypeParameters().isEmpty()) {
            sb.append('<').append(joinTypeParams(n.getTypeParameters())).append('>');
        }
        if (!n.getExtendedTypes().isEmpty()) {
            sb.append(" extends ").append(n.getExtendedTypes().get(0).asString());
        }
        if (!n.getImplementedTypes().isEmpty()) {
            sb.append(" implements ")
              .append(n.getImplementedTypes().stream()
                      .map(t -> t.asString()).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    public String recordSignature(RecordDeclaration n) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, n.getAnnotations());
        appendModifiers(sb, n.getModifiers());
        sb.append("record ").append(n.getNameAsString());
        if (!n.getTypeParameters().isEmpty()) {
            sb.append('<').append(joinTypeParams(n.getTypeParameters())).append('>');
        }
        sb.append('(');
        sb.append(n.getParameters().stream()
                .map(p -> {
                    StringBuilder ps = new StringBuilder();
                    p.getAnnotations().forEach(a -> ps.append('@').append(a.getNameAsString()).append(' '));
                    ps.append(p.getType().asString()).append(' ').append(p.getNameAsString());
                    return ps.toString();
                }).collect(Collectors.joining(", ")));
        sb.append(')');
        if (!n.getImplementedTypes().isEmpty()) {
            sb.append(" implements ")
              .append(n.getImplementedTypes().stream()
                      .map(t -> t.asString()).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    public String enumSignature(EnumDeclaration n) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, n.getAnnotations());
        appendModifiers(sb, n.getModifiers());
        sb.append("enum ").append(n.getNameAsString());
        if (!n.getImplementedTypes().isEmpty()) {
            sb.append(" implements ")
              .append(n.getImplementedTypes().stream()
                      .map(t -> t.asString()).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    public String annotationTypeSignature(AnnotationDeclaration n) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, n.getAnnotations());
        appendModifiers(sb, n.getModifiers());
        sb.append("@interface ").append(n.getNameAsString());
        return sb.toString();
    }

    // ── Поля ───────────────────────────────────────────────────────────────

    public String fieldSignature(FieldDeclaration fd, VariableDeclarator v) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, fd.getAnnotations());
        appendModifiers(sb, fd.getModifiers());
        sb.append(fd.getElementType().asString()).append(' ').append(v.getNameAsString());
        v.getInitializer().ifPresent(init -> sb.append(" = ").append(init));
        return sb.toString();
    }

    // ── Методы и конструкторы ─────────────────────────────────────────────

    public String methodSignature(MethodDeclaration md) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, md.getAnnotations());
        appendModifiers(sb, md.getModifiers());
        if (!md.getTypeParameters().isEmpty()) {
            sb.append('<').append(joinTypeParams(md.getTypeParameters())).append("> ");
        }
        sb.append(md.getType().asString()).append(' ').append(md.getNameAsString());
        appendParams(sb, md.getParameters());
        appendThrows(sb, md.getThrownExceptions());
        return sb.toString();
    }

    public String constructorSignature(ConstructorDeclaration cd) {
        StringBuilder sb = new StringBuilder();
        appendAnnotations(sb, cd.getAnnotations());
        appendModifiers(sb, cd.getModifiers());
        if (!cd.getTypeParameters().isEmpty()) {
            sb.append('<').append(joinTypeParams(cd.getTypeParameters())).append("> ");
        }
        sb.append(cd.getNameAsString());
        appendParams(sb, cd.getParameters());
        appendThrows(sb, cd.getThrownExceptions());
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void appendAnnotations(StringBuilder sb, NodeList<AnnotationExpr> annotations) {
        annotations.forEach(a -> sb.append(a.toString()).append('\n'));
    }

    private void appendModifiers(StringBuilder sb,
            NodeList<com.github.javaparser.ast.Modifier> modifiers) {
        modifiers.forEach(m -> sb.append(m.getKeyword().asString()).append(' '));
    }

    private void appendParams(StringBuilder sb,
            NodeList<com.github.javaparser.ast.body.Parameter> params) {
        sb.append('(');
        sb.append(params.stream().map(p -> {
            String annots = p.getAnnotations().stream()
                    .map(a -> '@' + a.getNameAsString() + ' ')
                    .collect(Collectors.joining());
            String vararg = p.isVarArgs() ? "..." : "";
            return annots + p.getType().asString() + vararg + ' ' + p.getNameAsString();
        }).collect(Collectors.joining(", ")));
        sb.append(')');
    }

    private void appendThrows(StringBuilder sb, NodeList<ReferenceType> exceptions) {
        if (!exceptions.isEmpty()) {
            sb.append(" throws ")
              .append(exceptions.stream()
                      .map(e -> e.asString()).collect(Collectors.joining(", ")));
        }
    }

    private String joinTypeParams(NodeList<?> typeParams) {
        return typeParams.stream().map(Object::toString).collect(Collectors.joining(", "));
    }

    /** Формирует строку диапазона строк: "17" или "19-22". */
    public String rows(com.github.javaparser.ast.Node node) {
        return node.getRange().map(r ->
                r.begin.line == r.end.line
                        ? String.valueOf(r.begin.line)
                        : r.begin.line + "-" + r.end.line
        ).orElse(null);
    }
}
