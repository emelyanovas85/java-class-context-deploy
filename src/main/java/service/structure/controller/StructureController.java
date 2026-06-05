package service.structure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.structure.model.*;
import service.structure.service.ContextBuilderService;
import service.structure.service.ContextMarkdownFormatter;
import service.structure.service.HtmlContextRenderer;
import service.structure.service.PlantUmlRenderer;
import service.structure.session.ReviewSession;
import service.structure.session.ReviewSessionResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
/**
 * Построение структурного контекста, HTML/markdown и PlantUML по {@code sessionId}.
 */
@Tag(name = "Structure", description = "Структурный контекст классов и PlantUML по сессии")
public class StructureController {

    private final ReviewSessionResolver sessionResolver;
    private final ContextBuilderService contextBuilderService;
    private final HtmlContextRenderer htmlContextRenderer;
    private final PlantUmlRenderer plantUmlRenderer;

    @Operation(summary = "Построить контекст классов (JSON)")
    @PostMapping("/context")
    public ResponseEntity<ContextResponse> getContext(@Valid @RequestBody SessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        log.info("Building context for session {}, depth={}", session.sessionId(), session.depth());
        Instant start = Instant.now();
        ContextResponse response = contextBuilderService.buildContext(session);
        log.info("Context built for session {}: {} classes   {} ms",
                session.sessionId(), response.totalClassesAnalyzed(),
                Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Построить контекст (HTML для отладки)")
    @PostMapping(value = "/context/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getContextHtml(@Valid @RequestBody SessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        Instant start = Instant.now();
        ContextResponse response = contextBuilderService.buildContext(session);
        String html = htmlContextRenderer.render(response);
        log.info("HTML context built for session {} ({} classes)   {} ms",
                session.sessionId(), response.totalClassesAnalyzed(),
                Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @Operation(summary = "Построить контекст (markdown)", description = """
            JSON-массив строк: по одному FileContext.toString() на каждый файл.
            """)
    @PostMapping("/context/markdown")
    public ResponseEntity<List<String>> getContextMarkdown(@Valid @RequestBody SessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        ContextResponse response = contextBuilderService.buildContext(session);
        return ResponseEntity.ok(ContextMarkdownFormatter.toMarkdownLines(response));
    }

    @Operation(summary = "Построить PlantUML class diagram")
    @PostMapping("/plantuml")
    public ResponseEntity<PlantUmlResponse> getPlantUml(@Valid @RequestBody PlantUmlSessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.session().sessionId());
        boolean pretty = request.prettyOrDefault();
        ContextResponse context = contextBuilderService.buildContext(session);
        String plantUml = plantUmlRenderer.render(context, pretty);
        PlantUmlResponse response = new PlantUmlResponse(
                context.mergeRequest(),
                plantUml,
                pretty,
                context.requestedDepth(),
                context.totalClassesAnalyzed());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Построить PlantUML (plain text)")
    @PostMapping(value = "/plantuml/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPlantUmlText(@Valid @RequestBody PlantUmlSessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.session().sessionId());
        boolean pretty = request.prettyOrDefault();
        ContextResponse context = contextBuilderService.buildContext(session);
        String plantUml = plantUmlRenderer.render(context, pretty);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(plantUml);
    }
}
