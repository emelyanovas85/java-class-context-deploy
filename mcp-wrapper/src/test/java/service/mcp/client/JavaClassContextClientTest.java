package service.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import service.mcp.model.SessionDtos.CreateSessionRequest;
import service.mcp.model.SessionDtos.CreateSessionResponse;
import service.mcp.model.SessionDtos.SessionRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class JavaClassContextClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private JavaClassContextClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://upstream:8084");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new JavaClassContextClient(builder.build(), mapper);
    }

    @Test
    void createSession_parsesTypedResponse() {
        server.expect(requestTo("http://upstream:8084/api/review-sessions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"sessionId\":\"abc123\",\"sourceSha\":\"s1\",\"targetSha\":\"t1\",\"baseSha\":\"b1\",\"expiresAt\":\"2026-06-10T14:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        CreateSessionResponse resp = client.createSession(
                new CreateSessionRequest("https://gitlab.com", "g/p", "glpat-x", 42L));

        assertThat(resp.sessionId()).isEqualTo("abc123");
        assertThat(resp.sourceSha()).isEqualTo("s1");
        server.verify();
    }

    @Test
    void structureJson_omitsNullNamesFromBody() {
        // names = null → поле names НЕ должно попадать в JSON (JsonInclude.NON_NULL)
        server.expect(requestTo("http://upstream:8084/api/structure/json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("names"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        String body = client.structureJson(new SessionRequest("abc123", 0, null));
        assertThat(body).contains("ok");
        server.verify();
    }

    @Test
    void upstream404_translatedToUpstreamException() {
        server.expect(requestTo("http://upstream:8084/api/structure/json"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("session not found"));

        assertThatThrownBy(() -> client.structureJson(new SessionRequest("missing", 0, null)))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("HTTP 404");
    }
}
