package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiErrorHandlerTests.Probe.class)
class ApiErrorHandlerTests {

    private static final String RUN_ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11";
    private static final String INVALID = "{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}}";

    // Stands in for the real endpoints, which do not exist yet.
    @RestController
    @RequestMapping("/probe")
    static class Probe {

        @PostMapping("/run")
        Map<String, Integer> run(@RequestBody RunRequest request) {
            return Map.of("codePoints", request.question().codePointCount(0, request.question().length()));
        }

        @GetMapping("/runs/{runId}")
        String run(@PathVariable String runId) {
            return RunIds.require(runId);
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("password=hunter2 at http://internal-host:9999/secret-path");
        }
    }

    @Value("${local.server.port}")
    private int port;

    private ResponseEntity<String> call(HttpMethod method, String path, String contentType, String body, Object... vars) {
        RestClient.RequestBodySpec spec = RestClient.create("http://127.0.0.1:" + port).method(method).uri(path, vars);
        if (body != null) {
            spec.contentType(MediaType.parseMediaType(contentType)).body(body);
        }
        return spec.retrieve().onStatus(HttpStatusCode::isError, (request, response) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> postJson(String body) {
        return call(HttpMethod.POST, "/probe/run", "application/json", body);
    }

    @Test
    void aValidRequestPassesThrough() {
        ResponseEntity<String> response = postJson("{\"question\":\"😀😀\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"codePoints\":2}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"question\":\"\"}",
        "{}",
        "{\"question\":\"hi\",\"extra\":true}",
        "{\"question\":123}",
        "{\"question\":true}",
        "{\"question\":",
        "not json",
        ""
    })
    void invalidBodiesGetTheInvalidRequestEnvelope(String body) {
        ResponseEntity<String> response = postJson(body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(INVALID);
    }

    @Test
    void anOversizedQuestionAndAMissingBodyAreInvalid() {
        assertThat(postJson("{\"question\":\"" + "x".repeat(2001) + "\"}").getBody()).isEqualTo(INVALID);
        assertThat(call(HttpMethod.POST, "/probe/run", null, null).getBody()).isEqualTo(INVALID);
    }

    @Test
    void aWrongContentTypeIsInvalidRequestToo() {
        ResponseEntity<String> response = call(HttpMethod.POST, "/probe/run", "text/plain", "{\"question\":\"hi\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(INVALID);
    }

    @Test
    void aCanonicalRunIdPathIsAccepted() {
        assertThat(call(HttpMethod.GET, "/probe/runs/{id}", null, null, RUN_ID).getBody()).isEqualTo(RUN_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "3F2B8C1E-5D4A-4C7E-9B1A-2E6F0A8D7C11",
        "{3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11}",
        "3f2b8c1e5d4a4c7e9b1a2e6f0a8d7c11",
        "urn:uuid:3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11 ",
        "not-a-uuid"
    })
    void aMalformedRunIdPathGetsTheInvalidRequestEnvelope(String runId) {
        ResponseEntity<String> response = call(HttpMethod.GET, "/probe/runs/{id}", null, null, runId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(INVALID);
    }

    @Test
    void anUnexpectedExceptionIsAGenericInternalErrorWithoutDetails() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/probe/boom", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody())
                .isEqualTo("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"An internal error occurred.\"}}")
                .doesNotContain("hunter2", "internal-host", "IllegalState", "at com.nexora");
    }

    @Test
    void frameworkRoutingErrorsKeepTheirOwnStatus() {
        assertThat(call(HttpMethod.GET, "/probe/nowhere", null, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/probe/boom", "application/json", "{}").getStatusCode().value())
                .isEqualTo(405);
    }
}
