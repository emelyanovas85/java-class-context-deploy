package service.mcp.client;

/**
 * Ошибка обращения к основному сервису Java Class Context API.
 * Несёт HTTP-статус и тело ответа, чтобы MCP-инструмент мог вернуть осмысленное сообщение LLM.
 */
public class UpstreamException extends RuntimeException {

    private final int status;
    private final String responseBody;

    public UpstreamException(int status, String responseBody, String message) {
        super(message);
        this.status = status;
        this.responseBody = responseBody;
    }

    public int status() {
        return status;
    }

    public String responseBody() {
        return responseBody;
    }
}
