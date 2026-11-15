package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiServiceClientTests {

    private static final UUID RUN_ID = UUID.fromString("3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11");

    private static AiServiceClient client(URI url, String secret) {
        return new AiServiceClient(TestSupport.properties(
                url, secret, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofHours(1), 10));
    }

    private static ErrorCode failureOf(AiServiceClient client) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> client.stream(RUN_ID, "What changed?", () -> { }, frame -> true));
        assertThat(thrown).isInstanceOf(UpstreamException.class);
        return ((UpstreamException) thrown).code();
    }

    @Test
    void sendsTheSecretTheRunIdAndTheQuestionAndDeliversEveryFrame() {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun)) {
            List<SseParser.Frame> frames = new ArrayList<>();
            AtomicInteger activity = new AtomicInteger();

            client(fake.uri(), TestSupport.SECRET).stream(RUN_ID, "What changed?", activity::incrementAndGet, frame -> {
                frames.add(frame);
                return true;
            });

            assertThat(frames).extracting(SseParser.Frame::event)
                    .containsExactly("step", "step", "token", "token", "token", "token", "token", "done");
            assertThat(frames).extracting(SseParser.Frame::data).containsExactlyElementsOf(fake.sent);
            assertThat(activity.get()).isPositive();
            assertThat(fake.lastSecret).isEqualTo(TestSupport.SECRET);
            assertThat(fake.lastContentType).startsWith("application/json");
            assertThat(fake.lastAccept).contains("text/event-stream");
            assertThat(fake.lastBody).contains(RUN_ID.toString()).contains("What changed?");
            assertThat(fake.requests).hasValue(1);
        }
    }

    @Test
    void cancelPostsToTheRunsCancelPathWithTheSecret() {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun)) {
            client(fake.uri(), TestSupport.SECRET).cancel(RUN_ID);

            assertThat(fake.cancels).containsExactly(RUN_ID.toString());
            assertThat(fake.requests).hasValue(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"401,INTERNAL_AUTH_FAILED", "400,INTERNAL_ERROR", "404,UPSTREAM_UNAVAILABLE", "500,UPSTREAM_UNAVAILABLE"})
    void aRejectedCancelIsMappedToAnUpstreamError(int status, ErrorCode expected) {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun)) {
            fake.cancelStatus = status;

            assertThatThrownBy(() -> client(fake.uri(), TestSupport.SECRET).cancel(RUN_ID))
                    .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.code()).isEqualTo(expected));
        }
    }

    @Test
    void cancelAgainstAnUnreachableServiceIsUnavailable() {
        assertThatThrownBy(() -> client(TestSupport.closedPortUri(), TestSupport.SECRET).cancel(RUN_ID))
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void theConfiguredConnectionTimeoutIsTheOneTheHttpClientUses() {
        NexoraProperties properties = new NexoraProperties(
                new NexoraProperties.AiService(URI.create("http://127.0.0.1:8000"), Duration.ofMillis(1234)),
                new NexoraProperties.Internal(TestSupport.SECRET),
                new NexoraProperties.Run(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(10),
                        Duration.ofHours(1), 10));

        assertThat(new AiServiceClient(properties).connectTimeout()).isEqualTo(Duration.ofMillis(1234));
    }

    @Test
    void stopsReadingWhenTheListenerAsksTo() {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun)) {
            List<SseParser.Frame> frames = new ArrayList<>();

            client(fake.uri(), TestSupport.SECRET).stream(RUN_ID, "q", () -> { }, frame -> {
                frames.add(frame);
                return false;
            });

            assertThat(frames).hasSize(1);
        }
    }

    @Test
    void aWrongSecretIsReportedAsAnAuthenticationFailureWithoutLeakingIt() {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun)) {
            AiServiceClient client = client(fake.uri(), "not-the-secret");

            assertThat(failureOf(client)).isEqualTo(ErrorCode.INTERNAL_AUTH_FAILED);
            assertThatThrownBy(() -> client.stream(RUN_ID, "q", () -> { }, frame -> true))
                    .hasMessageNotContaining("not-the-secret").hasMessageNotContaining(TestSupport.SECRET);
        }
    }

    @ParameterizedTest
    @CsvSource({"400,INTERNAL_ERROR", "404,UPSTREAM_UNAVAILABLE", "500,UPSTREAM_UNAVAILABLE", "503,UPSTREAM_UNAVAILABLE"})
    void otherErrorStatusesAreMappedToStableCodes(int status, ErrorCode expected) {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET,
                call -> call.respond(status, "application/json", "{\"detail\":\"secret python text\"}"))) {
            AiServiceClient client = client(fake.uri(), TestSupport.SECRET);

            assertThat(failureOf(client)).isEqualTo(expected);
            assertThatThrownBy(() -> client.stream(RUN_ID, "q", () -> { }, frame -> true))
                    .hasMessageNotContaining("secret python text");
        }
    }

    @Test
    void aRefusedConnectionIsUnavailable() {
        assertThat(failureOf(client(TestSupport.closedPortUri(), TestSupport.SECRET)))
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void aSuccessResponseThatIsNotAnEventStreamIsInvalid() {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET,
                call -> call.respond(200, "application/json", "{}"))) {
            assertThat(failureOf(client(fake.uri(), TestSupport.SECRET))).isEqualTo(ErrorCode.UPSTREAM_STREAM_INVALID);
        }
    }

    @Test
    void aMalformedFrameSurfacesAsInvalidWhileTheStreamIsOpen() {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.raw(new byte[] {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'});
        })) {
            assertThat(failureOf(client(fake.uri(), TestSupport.SECRET))).isEqualTo(ErrorCode.UPSTREAM_STREAM_INVALID);
        }
    }
}
