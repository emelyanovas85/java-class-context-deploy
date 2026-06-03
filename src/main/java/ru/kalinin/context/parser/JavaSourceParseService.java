package ru.kalinin.context.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Один проход {@link JavaParser} на файл: {@link ClassStructure} и {@link StructureNode}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavaSourceParseService {

    private final JavaStructureParser structureParser;
    private final StructureNodeMapper nodeMapper;

    private final ParserConfiguration parserConfig = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    public ParsedJavaFile parse(
            String sourceCode,
            String sourceFile,
            int contextLevel,
            BiFunction<String, List<String>, Optional<String>> wildcardResolver) {
        ParseResult<CompilationUnit> result = new JavaParser(parserConfig).parse(sourceCode);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.warn("Failed to parse {}: {}", sourceFile, result.getProblems());
            return ParsedJavaFile.empty();
        }
        CompilationUnit cu = result.getResult().get();
        return new ParsedJavaFile(
                structureParser.parseCompilationUnit(cu, sourceFile, contextLevel, wildcardResolver),
                nodeMapper.mapCompilationUnit(cu));
    }
}
