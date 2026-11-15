package com.nexora;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiServiceClient {

    private static final String SECRET_HEADER = "X-Nexora-Internal-Secret";

    private record InternalRunRequest(@JsonProperty("run_id") String runId, String question) {
    }

    private final HttpClient httpClient;
    private final RestClient restClient;
    private final String secret;

    public AiServiceClient(NexoraProperties properties) {
        // HTTP/1.1 only: the Python server speaks nothing else, and an upgrade attempt only adds a failure mode.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.aiService().connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(properties.aiService().url().toString())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.secret = properties.internal().sharedSecret();
    }

    Duration connectTimeout() {
        return httpClient.connectTimeout().orElseThrow();
    }

    // Blocks while the upstream stream is open. There is deliberately no read timeout here: the caller
    // enforces idle and total deadlines by interrupting this thread.
    void stream(UUID runId, String question, Runnable onActivity, Predicate<SseParser.Frame> onFrame) {
        AtomicBoolean connected = new AtomicBoolean();
        try {
            restClient.post()
                    .uri("/internal/v1/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(SECRET_HEADER, secret)
                    .body(new InternalRunRequest(runId.toString(), question))
                    .exchange((request, response) -> {
                        readStream(response, connected, onActivity, onFrame);
                        return null;
                    });
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException | IllegalStateException e) {
            // A failure before the 200 response is "unavailable"; one after it is a broken stream.
            throw new UpstreamException(
                    connected.get() ? ErrorCode.UPSTREAM_STREAM_TRUNCATED : ErrorCode.UPSTREAM_UNAVAILABLE, e);
        }
    }

    private static void readStream(
            ClientHttpResponse response,
            AtomicBoolean connected,
            Runnable onActivity,
            Predicate<SseParser.Frame> onFrame)
            throws IOException {
        int status = response.getStatusCode().value();
        if (status == 401) {
            throw new UpstreamException(ErrorCode.INTERNAL_AUTH_FAILED);
        }
        if (status == 400) {
            // Java built the request, so a rejection means a defect on this side.
            throw new UpstreamException(ErrorCode.INTERNAL_ERROR);
        }
        if (status != 200) {
            throw new UpstreamException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            throw new UpstreamException(ErrorCode.UPSTREAM_STREAM_INVALID);
        }
        connected.set(true);
        SseParser parser = new SseParser(onFrame);
        byte[] buffer = new byte[8192];
        InputStream body = response.getBody();
        int read;
        while ((read = body.read(buffer)) != -1) {
            onActivity.run();
            if (!parser.feed(buffer, 0, read)) {
                return;
            }
        }
    }
}
