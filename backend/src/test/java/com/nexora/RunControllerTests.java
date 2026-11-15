package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The real application over a real port, talking to the fake Python service over real HTTP.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunControllerTests {

    private static final FakeAiService FAKE = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("nexora.ai-service.url", () -> FAKE.uri().toString());
    }

    @AfterAll
    static void stopFake() {
        FAKE.close();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RunRegistry registry;

    @Autowired
    private JsonMapper mapper;

    @BeforeEach
    void resetFake() {
        FAKE.script(FakeAiService::completeRun);
        FAKE.sent.clear();
        FAKE.requests.set(0);
        FAKE.cancels.clear();
    }

    private HttpRequest post(String body, String accept) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static List<SseParser.Frame> parse(String text) {
        List<SseParser.Frame> frames = new ArrayList<>();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        new SseParser(frame -> frames.add(frame)).feed(bytes, 0, bytes.length);
        return frames;
    }

    private UUID runIdOf(SseParser.Frame frame) {
        return UUID.fromString(mapper.readTree(frame.data()).get("run_id").stringValue());
    }

    @Test
    void aRunStreamsEveryEventToTheBrowserWithPythonsEnvelopesUnchanged() throws Exception {
        HttpResponse<InputStream> response =
                HTTP.send(post("{\"question\":\"What changed?\"}", "text/event-stream"), HttpResponse.BodyHandlers.ofInputStream());
        List<SseParser.Frame> frames = parse(new String(response.body().readAllBytes(), StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(frames).extracting(SseParser.Frame::event)
                .containsExactly("step", "step", "token", "token", "token", "token", "token", "done");
        assertThat(frames).extracting(SseParser.Frame::data).containsExactlyElementsOf(FAKE.sent);
        UUID runId = runIdOf(frames.get(0));
        assertThat(RunIds.isValid(runId.toString())).isTrue();
        assertThat(FAKE.lastBody).contains(runId.toString()).contains("What changed?");
        assertThat(FAKE.lastSecret).isEqualTo(TestSupport.SECRET);

        Run run = registry.find(runId).orElseThrow();
        RunHarness.awaitTerminal(run);
        assertThat(run.snapshot().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.snapshot().output()).isEqualTo("This is a simulated answer.");
        assertThat(run.snapshot().steps()).hasSize(2);
    }

    @Autowired
    private NexoraProperties properties;

    @Test
    void theBrowserStreamTimeoutIsFourMinutesAndOutlastsTheRunDeadline() {
        assertThat(RunController.EMITTER_TIMEOUT).isEqualTo(Duration.ofMinutes(4));
        assertThat(RunController.EMITTER_TIMEOUT).isGreaterThan(properties.run().maxDuration());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        request = method.equals("GET") ? request.GET() : request.POST(HttpRequest.BodyPublishers.noBody());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aFinishedRunIsReadableAsASnapshotAndCancellingItChangesNothing() throws Exception {
        HttpResponse<String> stream =
                HTTP.send(post("{\"question\":\"q\"}", "text/event-stream"), HttpResponse.BodyHandlers.ofString());
        UUID runId = runIdOf(parse(stream.body()).get(0));
        RunHarness.awaitTerminal(registry.find(runId).orElseThrow());

        HttpResponse<String> get = send("GET", "/api/v1/runs/" + runId);
        HttpResponse<String> cancel = send("POST", "/api/v1/runs/" + runId + "/cancel");

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        JsonNode body = mapper.readTree(get.body());
        assertThat(body.get("run_id").stringValue()).isEqualTo(runId.toString());
        assertThat(body.get("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(body.get("output").stringValue()).isEqualTo("This is a simulated answer.");
        assertThat(body.get("steps")).hasSize(2);
        assertThat(body.has("error")).isFalse();
        assertThat(cancel.statusCode()).isEqualTo(200);
        assertThat(cancel.body()).isEqualTo(get.body());
        assertThat(FAKE.cancels).isEmpty();
    }

    @Test
    void cancellingARunningRunOverHttpReturnsTheCancelledSnapshotAndEndsTheStream() throws Exception {
        FAKE.script(call -> {
            call.startStream();
            call.step("first");
            call.token("partial");
            call.waitForClientToClose(Duration.ofSeconds(15));
        });
        HttpResponse<InputStream> stream =
                HTTP.send(post("{\"question\":\"q\"}", "text/event-stream"), HttpResponse.BodyHandlers.ofInputStream());
        UUID runId = runIdOf(readFirstFrame(stream.body()));
        await().atMost(Duration.ofSeconds(10)).until(
                () -> registry.find(runId).orElseThrow().snapshot().output().equals("partial"));

        HttpResponse<String> cancel = send("POST", "/api/v1/runs/" + runId + "/cancel");
        HttpResponse<String> again = send("POST", "/api/v1/runs/" + runId + "/cancel");

        JsonNode body = mapper.readTree(cancel.body());
        assertThat(cancel.statusCode()).isEqualTo(200);
        assertThat(body.get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(body.get("output").stringValue()).isEqualTo("partial");
        assertThat(again.body()).isEqualTo(cancel.body());
        // The first frame was consumed above; what remains is the token, and no terminal event follows.
        assertThat(parse(new String(stream.body().readAllBytes(), StandardCharsets.UTF_8)))
                .extracting(SseParser.Frame::event).containsExactly("token");
        await().atMost(Duration.ofSeconds(10)).until(() -> FAKE.cancels.size() == 1);
        assertThat(FAKE.cancels).containsExactly(runId.toString());
    }

    @Test
    void anUnknownRunIs404AndAMalformedIdIs400WithTheErrorEnvelope() throws Exception {
        String unknown = "/api/v1/runs/3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11";
        String notFound = "{\"error\":{\"code\":\"RUN_NOT_FOUND\",\"message\":\""
                + ErrorCode.RUN_NOT_FOUND.message() + "\"}}";
        String invalid = "{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}}";

        for (HttpResponse<String> response : List.of(send("GET", unknown), send("POST", unknown + "/cancel"))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo(notFound);
        }
        for (HttpResponse<String> response : List.of(
                send("GET", "/api/v1/runs/not-a-uuid"), send("POST", "/api/v1/runs/NOT-A-UUID/cancel"))) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo(invalid);
        }
        assertThat(FAKE.cancels).isEmpty();
    }

    @Test
    void anInvalidRequestIsAJsonErrorEvenWhenTheClientAsksForAnEventStream() throws Exception {
        HttpResponse<String> response =
                HTTP.send(post("{\"question\":\"\"}", "text/event-stream"), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body())
                .isEqualTo("{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}}");
        assertThat(FAKE.requests.get()).isZero();
    }

    @Test
    void aBrowserThatDisconnectsMidStreamDoesNotStopTheRun() throws Exception {
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean upstreamWritesAllSucceeded = new AtomicBoolean();
        FAKE.script(call -> {
            call.startStream();
            call.step("first");
            proceed.await();
            call.step("second");
            call.token("after the browser left");
            call.event("done", "{}");
            upstreamWritesAllSucceeded.set(true);
        });

        HttpResponse<InputStream> response =
                HTTP.send(post("{\"question\":\"q\"}", "text/event-stream"), HttpResponse.BodyHandlers.ofInputStream());
        UUID runId = runIdOf(readFirstFrame(response.body()));
        response.body().close();
        proceed.countDown();

        Run run = registry.find(runId).orElseThrow();
        RunHarness.awaitTerminal(run);
        RunSnapshot snapshot = run.snapshot();
        assertThat(snapshot.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(snapshot.output()).isEqualTo("after the browser left");
        assertThat(snapshot.steps()).extracting(RunSnapshot.Step::message).containsExactly("first", "second");
        await().atMost(Duration.ofSeconds(10)).untilTrue(upstreamWritesAllSucceeded);
        assertThat(registry.find(runId)).isPresent();
    }

    @Test
    void anUnavailableAiServiceEndsTheStreamWithAGenericErrorEvent() throws Exception {
        FAKE.script(call -> call.respond(500, "application/json", "{\"detail\":\"python traceback\"}"));

        HttpResponse<String> response =
                HTTP.send(post("{\"question\":\"q\"}", "text/event-stream"), HttpResponse.BodyHandlers.ofString());
        List<SseParser.Frame> frames = parse(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(frames).extracting(SseParser.Frame::event).containsExactly("error");
        JsonNode envelope = mapper.readTree(frames.get(0).data());
        assertThat(envelope.get("data").get("code").stringValue()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("python traceback").doesNotContain("127.0.0.1");
        RunHarness.awaitTerminal(registry.find(runIdOf(frames.get(0))).orElseThrow());
        assertThat(registry.find(runIdOf(frames.get(0))).orElseThrow().snapshot().status()).isEqualTo(RunStatus.FAILED);
    }

    // Reads only until the first complete frame, leaving the rest of the stream unread.
    private static SseParser.Frame readFirstFrame(InputStream body) throws IOException {
        StringBuilder text = new StringBuilder();
        List<SseParser.Frame> frames = new ArrayList<>();
        SseParser parser = new SseParser(frame -> {
            frames.add(frame);
            return false;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (frames.isEmpty() && System.nanoTime() < deadline) {
            int b = body.read();
            if (b < 0) {
                break;
            }
            parser.feed(new byte[] {(byte) b}, 0, 1);
            text.append((char) b);
        }
        assertThat(frames).as("first frame, read so far: " + text).isNotEmpty();
        return frames.get(0);
    }
}
