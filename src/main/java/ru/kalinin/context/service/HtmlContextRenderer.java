package ru.kalinin.context.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.kalinin.context.model.ContextRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Собирает самодостаточную HTML-страницу отладки: шаблон + встроенные CSS, JS и JSON данных.
 */
@Component
public class HtmlContextRenderer {

    private static final String REQUEST_PLACEHOLDER = "__CONTEXT_REQUEST__";
    private static final String CSS_PLACEHOLDER = "__CONTEXT_CSS__";
    private static final String JS_PLACEHOLDER = "__CONTEXT_JS__";

    private static final String TEMPLATE_PATH = "templates/context-debug.html";
    private static final String CSS_PATH = "static/context-debug.css";
    private static final String JS_PATH = "static/context-debug.js";

    private final ObjectMapper objectMapper;
    private final String pageShell;

    public HtmlContextRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.pageShell = loadResource(TEMPLATE_PATH)
                .replace(CSS_PLACEHOLDER, loadResource(CSS_PATH))
                .replace(JS_PLACEHOLDER, loadResource(JS_PATH));
    }

    /**
     * HTML-оболочка: данные контекста подгружаются на клиенте через POST /api/context.
     */
    public String renderShell(ContextRequest request) {
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize context request for debug page", e);
        }
        return pageShell.replace(REQUEST_PLACEHOLDER, json);
    }

    private static String loadResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath resource: " + path, e);
        }
    }
}
