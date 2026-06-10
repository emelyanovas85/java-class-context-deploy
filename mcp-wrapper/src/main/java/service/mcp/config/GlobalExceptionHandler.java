package service.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import service.mcp.client.UpstreamException;

import java.util.Map;

/**
 * Транслирует ошибки обращения к основному сервису в HTTP-ответы REST-слоя обёртки.
 * (MCP-инструменты получают сообщение исключения непосредственно — Spring AI оборачивает
 * его в tool error для клиента.)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleUpstream(UpstreamException ex) {
        HttpStatus status = HttpStatus.resolve(ex.status());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).body(Map.of(
                "error", "upstream_error",
                "upstreamStatus", ex.status(),
                "message", ex.getMessage()));
    }
}
