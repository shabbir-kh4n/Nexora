package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexora.RunHarness.RecordingEmitter;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class RunServiceTests {

    private static final String OTHER_RUN_ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11";

    private record Outcome(Run run, RecordingEmitter emitter) {
    }

    private static Outcome runToEnd(RunHarness harness) throws InterruptedException {
        RunService.StartedRun started = harness.service.start("What changed?");
        RecordingEmitter emitter = new RecordingEmitter();
        started.stream().attach(emitter);
        assertThat(emitter.awaitCompleted()).isTrue();
        RunHarness.awaitTerminal(started.run());
        return new Outcome(started.run(), emitter);
    }

    private static List<String> types(Outcome outcome) {
        return outcome.emitter().frames.stream().map(SseParser.Frame::event).toList();
    }

    private static SseParser.Frame lastFrame(Outcome outcome) {
        List<SseParser.Frame> frames = outcome.emitter().frames;
        return frames.get(frames.size() - 1);
    }

    private static void awaitRunThreadExit(RunHarness harness) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> harness.pendingTimers() == 0);
    }

    private static void assertFailedWith(RunHarness harness, Outcome outcome, ErrorCode code) {
        RunSnapshot snapshot = outcome.run().snapshot();
        assertThat(snapshot.status()).isEqualTo(RunStatus.FAILED);
        assertThat(snapshot.error()).isEqualTo(ErrorResponse.ErrorBody.of(code));
        SseParser.Frame last = lastFrame(outcome);
        assertThat(last.event()).isEqualTo("error");
        // A complete, valid envelope: version, event id, run id, type, timestamp and data all check out.
        RunEvent parsed = harness.mapper.readValue(last.data(), RunEvent.class);
        assertThat(parsed.type()).isEqualTo(RunEvent.Type.ERROR);
        var envelope = harness.mapper.readTree(last.data());
        assertThat(envelope.get("version").stringValue()).isEqualTo("1");
        assertThat(envelope.get("run_id").stringValue()).isEqualTo(outcome.run().id().toString());
        assertThat(envelope.get("type").stringValue()).isEqualTo("error");
        assertThat(envelope.get("data").get("code").stringValue()).isEqualTo(code.name());
        assertThat(envelope.get("data").get("message").stringValue()).isEqualTo(code.message());
        assertThat(types(outcome).stream().filter("error"::equals)).hasSize(1);
    }

    @Test
    void aCompleteRunUpdatesTheStateAndRelaysEveryFrameUnchanged() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("Planning");
            call.event("heartbeat", "{}");
            call.token("Hello ");
            call.token("world");
            call.event("done", "{}");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);

            RunSnapshot snapshot = outcome.run().snapshot();
            assertThat(snapshot.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(snapshot.output()).isEqualTo("Hello world");
            assertThat(snapshot.steps()).extracting(RunSnapshot.Step::message).containsExactly("Planning");
            assertThat(snapshot.error()).isNull();
            assertThat(types(outcome)).containsExactly("step", "heartbeat", "token", "token", "done");
            // Python's ids and timestamps pass through byte for byte, nothing is regenerated.
            assertThat(outcome.emitter().frames).extracting(SseParser.Frame::data).containsExactlyElementsOf(fake.sent);
            assertThat(fake.lastBody).contains(outcome.run().id().toString());
            assertThat(fake.lastSecret).isEqualTo(TestSupport.SECRET);
            assertThat(harness.registry.find(outcome.run().id())).containsSame(outcome.run());
        }
    }

    @Test
    void anUpstreamErrorEventFailsTheRunWithItsCodeAndIsRelayedVerbatim() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("Planning");
            call.event("error", "{\"code\":\"INTERNAL_ERROR\",\"message\":\"An internal error occurred.\"}");
            call.token("never applied");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);

            assertThat(outcome.run().snapshot().status()).isEqualTo(RunStatus.FAILED);
            assertThat(outcome.run().snapshot().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
            assertThat(outcome.run().snapshot().output()).isEmpty();
            assertThat(types(outcome)).containsExactly("step", "error");
            assertThat(lastFrame(outcome).data()).isEqualTo(fake.sent.get(1));
        }
    }

    static Stream<Arguments> malformedStreams() {
        return Stream.of(
                malformed("invalid json", c -> c.raw("event: step\ndata: {not json\n\n")),
                malformed("wrong run id", c -> c.raw("event: step\ndata: "
                        + c.envelope(OTHER_RUN_ID, "step", "{\"message\":\"x\"}") + "\n\n")),
                malformed("unknown event type", c -> c.raw("event: banana\ndata: "
                        + c.envelope(c.runId, "banana", "{}") + "\n\n")),
                malformed("missing envelope fields", c -> c.raw(
                        "event: step\ndata: {\"version\":\"1\",\"type\":\"step\",\"data\":{\"message\":\"x\"}}\n\n")),
                malformed("event name differs from the type", c -> c.raw("event: token\ndata: "
                        + c.envelope(c.runId, "step", "{\"message\":\"x\"}") + "\n\n")),
                malformed("no event name", c -> c.raw("data: " + c.envelope(c.runId, "step", "{\"message\":\"x\"}") + "\n\n")),
                malformed("payload does not match the type", c -> c.raw("event: token\ndata: "
                        + c.envelope(c.runId, "token", "{\"text\":\"\"}") + "\n\n")),
                malformed("unsupported version", c -> c.raw("event: step\ndata: "
                        + c.envelope(c.runId, "step", "{\"message\":\"x\"}").replace("\"version\":\"1\"", "\"version\":\"2\"")
                        + "\n\n")),
                malformed("invalid utf-8", c -> c.raw(new byte[] {'d', 'a', 't', 'a', ':', (byte) 0xC3, (byte) 0x28, '\n', '\n'})),
                malformed("not json at all", c -> c.raw("event: step\ndata: hello\n\n")));
    }

    private static Arguments malformed(String name, FakeAiService.Script bad) {
        FakeAiService.Script script = call -> {
            call.startStream();
            call.step("fine");
            bad.run(call);
            call.token("after the bad frame");
            call.event("done", "{}");
        };
        return Arguments.of(name, script);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedStreams")
    void aMalformedFrameFailsTheRunAsInvalidAndStopsTheStream(String name, FakeAiService.Script script) throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, script);
                RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.UPSTREAM_STREAM_INVALID);
            assertThat(types(outcome)).containsExactly("step", "error");
            assertThat(outcome.run().snapshot().output()).isEmpty();
            assertThat(outcome.run().snapshot().steps()).hasSize(1);
        }
    }

    @Test
    void aStreamThatEndsWithoutATerminalEventIsTruncated() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("Planning");
            call.token("partial");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.UPSTREAM_STREAM_TRUNCATED);
            assertThat(outcome.run().snapshot().output()).isEqualTo("partial");
        }
    }

    @Test
    void aStreamCutInTheMiddleOfAFrameIsTruncated() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("Planning");
            call.raw("event: token\ndata: {\"version\":\"1\",\"ev");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.UPSTREAM_STREAM_TRUNCATED);
            assertThat(types(outcome)).containsExactly("step", "error");
        }
    }

    @Test
    void aRefusedConnectionFailsTheQueuedRunAsUnavailableWithoutLeakingTheAddress() throws Exception {
        URI closed = TestSupport.closedPortUri();
        try (RunHarness harness = RunHarness.defaults(closed)) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.UPSTREAM_UNAVAILABLE);
            assertThat(lastFrame(outcome).data()).doesNotContain("127.0.0.1").doesNotContain(TestSupport.SECRET);
            assertThat(types(outcome)).containsExactly("error");
        }
    }

    @Test
    void aWrongSecretFailsTheRunAsAnAuthenticationFailureWithoutLeakingIt() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = new RunHarness(fake.uri(), "a-different-secret", Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofSeconds(10), 10)) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.INTERNAL_AUTH_FAILED);
            assertThat(lastFrame(outcome).data()).doesNotContain("a-different-secret").doesNotContain(TestSupport.SECRET);
        }
    }

    @ParameterizedTest
    @CsvSource({"400,INTERNAL_ERROR", "500,UPSTREAM_UNAVAILABLE"})
    void anUpstreamHttpErrorFailsTheRunWithAStableCode(int status, ErrorCode expected) throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET,
                call -> call.respond(status, "application/json", "{\"detail\":\"python traceback\"}"));
                RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, expected);
            assertThat(lastFrame(outcome).data()).doesNotContain("python traceback");
        }
    }

    @Test
    void anUpstreamThatGoesSilentTriggersTheHeartbeatTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("Planning");
            release.await();
        }); RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                Duration.ofMillis(100), Duration.ofMillis(300), 10)) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.UPSTREAM_STREAM_TRUNCATED);
            assertThat(types(outcome)).containsExactly("step", "error");
            // The run's thread must exit on its own (its timers are cleaned up last) while the server stays silent.
            awaitRunThreadExit(harness);
            release.countDown();
        }
    }

    @Test
    void anUpstreamThatNeverAnswersAlsoTriggersTheHeartbeatTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> release.await());
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofMillis(300), 10)) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.UPSTREAM_STREAM_TRUNCATED);
            awaitRunThreadExit(harness);
            release.countDown();
        }
    }

    @Test
    void theRunDeadlineFailsAChattyRunAndClosesTheUpstreamConnection() throws Exception {
        CountDownLatch upstreamClosed = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            if (call.waitForClientToClose(Duration.ofSeconds(20))) {
                upstreamClosed.countDown();
            }
        }); RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofMillis(400),
                Duration.ofMillis(100), Duration.ofSeconds(10), 10)) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.RUN_TIMEOUT);
            assertThat(upstreamClosed.await(10, TimeUnit.SECONDS)).isTrue();
            awaitRunThreadExit(harness);
        }
    }

    @Test
    void eventsThatArriveAfterTheTerminalEventNeverChangeTheRun() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.token("kept");
            call.event("done", "{}");
            call.token("late");
            call.step("late");
            call.event("error", "{\"code\":\"INTERNAL_ERROR\",\"message\":\"late\"}");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);
            RunSnapshot afterRun = outcome.run().snapshot();

            assertThat(afterRun.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(afterRun.output()).isEqualTo("kept");
            assertThat(afterRun.steps()).isEmpty();
            assertThat(types(outcome)).containsExactly("token", "done");
            assertThat(outcome.run().snapshot()).isEqualTo(afterRun);
        }
    }

    @Test
    void aFinishedRunLeavesNoTimersBehindSoNoLateTimeoutCanTouchIt() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = RunHarness.defaults(fake.uri())) {

            Outcome outcome = runToEnd(harness);
            RunSnapshot completed = outcome.run().snapshot();

            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> harness.pendingTimers() == 0);
            assertThat(outcome.run().snapshot()).isEqualTo(completed);
            assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void anExecutorThatRejectsTheTaskFailsTheQueuedRunAndSendsAnError() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofSeconds(10), 10, task -> {
                            throw new RejectedExecutionException("saturated");
                        })) {

            Outcome outcome = runToEnd(harness);

            assertFailedWith(harness, outcome, ErrorCode.INTERNAL_ERROR);
            assertThat(outcome.run().snapshot().steps()).isEmpty();
            assertThat(fake.requests).hasValue(0);
            assertThat(harness.registry.find(outcome.run().id())).isPresent();
            assertThat(harness.registry.activeCount()).isZero();
        }
    }

    private static Outcome finish(RunService.StartedRun started) throws InterruptedException {
        RecordingEmitter emitter = new RecordingEmitter();
        started.stream().attach(emitter);
        assertThat(emitter.awaitCompleted()).isTrue();
        RunHarness.awaitTerminal(started.run());
        return new Outcome(started.run(), emitter);
    }

    private static FakeAiService.Script blockedUntil(CountDownLatch release) {
        return call -> {
            call.startStream();
            release.await();
            call.token("x");
            call.event("done", "{}");
        };
    }

    @Test
    void atCapacityTheRunFailsWithAnErrorEventTakesNoSlotAndNeverReachesPython() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, blockedUntil(release));
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofSeconds(10), 1)) {
            RunService.StartedRun first = harness.service.start("one");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> fake.requests.get() == 1);

            Outcome rejected = finish(harness.service.start("two"));

            assertFailedWith(harness, rejected, ErrorCode.RUN_CAPACITY_EXCEEDED);
            assertThat(types(rejected)).containsExactly("error");
            assertThat(lastFrame(rejected).data())
                    .contains("Nexora is currently at capacity. Please try again shortly.")
                    .doesNotContain("limit");
            RunSnapshot snapshot = rejected.run().snapshot();
            assertThat(snapshot.output()).isEmpty();
            assertThat(snapshot.steps()).isEmpty();
            assertThat(RunIds.isValid(snapshot.runId())).isTrue();
            assertThat(harness.registry.find(rejected.run().id())).containsSame(rejected.run());
            assertThat(harness.registry.activeCount()).isEqualTo(1);
            assertThat(harness.registry.size()).isEqualTo(2);

            release.countDown();
            RunHarness.awaitTerminal(first.run());
            assertThat(fake.requests).hasValue(1);
            assertThat(first.run().snapshot().status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void capacityFailuresNeverUseUpSlotsAndCapacityComesBackWhenARunEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, blockedUntil(release));
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofSeconds(10), 1)) {
            RunService.StartedRun first = harness.service.start("one");
            for (int i = 0; i < 5; i++) {
                assertFailedWith(harness, finish(harness.service.start("more")), ErrorCode.RUN_CAPACITY_EXCEEDED);
            }
            assertThat(harness.registry.activeCount()).isEqualTo(1);
            assertThat(harness.registry.size()).isEqualTo(6);

            release.countDown();
            RunHarness.awaitTerminal(first.run());
            RunService.StartedRun next = harness.service.start("after");
            RunHarness.awaitTerminal(next.run());

            assertThat(next.run().snapshot().status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void concurrentAdmissionAtTheLimitAdmitsExactlyTheLimit() throws Exception {
        for (int round = 0; round < 10; round++) {
            CountDownLatch release = new CountDownLatch(1);
            try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, blockedUntil(release));
                    RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                            Duration.ofMillis(100), Duration.ofSeconds(10), 3)) {
                List<Callable<RunService.StartedRun>> tasks = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    tasks.add(() -> harness.service.start("q"));
                }

                List<RunService.StartedRun> started = TestSupport.race(tasks);

                List<RunService.StartedRun> admitted =
                        started.stream().filter(s -> !s.run().status().isTerminal()).toList();
                List<RunService.StartedRun> rejected =
                        started.stream().filter(s -> s.run().status() == RunStatus.FAILED).toList();
                assertThat(admitted).hasSize(3);
                assertThat(rejected).hasSize(17);
                assertThat(rejected).allSatisfy(s -> assertThat(s.run().snapshot().error().code())
                        .isEqualTo(ErrorCode.RUN_CAPACITY_EXCEEDED));
                assertThat(harness.registry.activeCount()).isEqualTo(3);
                assertThat(harness.registry.size()).isEqualTo(20);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> fake.requests.get() == 3);

                release.countDown();
                for (RunService.StartedRun run : admitted) {
                    RunHarness.awaitTerminal(run.run());
                }
                assertThat(fake.requests).hasValue(3);
            }
        }
    }

    @Test
    void aBrowserTimeoutDoesNotAffectTheRun() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("first");
            release.await();
            call.token("rest");
            call.event("done", "{}");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {
            RunService.StartedRun started = harness.service.start("q");
            RecordingEmitter emitter = new RecordingEmitter();
            started.stream().attach(emitter);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> emitter.frames.size() == 1);

            emitter.fireTimeout();
            release.countDown();
            RunHarness.awaitTerminal(started.run());

            assertThat(started.run().snapshot().status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(started.run().snapshot().output()).isEqualTo("rest");
            assertThat(emitter.frames).extracting(SseParser.Frame::event).containsExactly("step");
            assertThat(emitter.awaitCompleted()).isTrue();
        }
    }

    @Test
    void exactlyOneTerminalEventIsEmittedWhenTheDeadlineRacesTheDoneEvent() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofMillis(20),
                        Duration.ofMillis(10), Duration.ofSeconds(10), 100)) {
            for (int i = 0; i < 40; i++) {
                Outcome outcome = finish(harness.service.start("q"));

                List<String> terminal =
                        types(outcome).stream().filter(t -> t.equals("done") || t.equals("error")).toList();
                RunStatus status = outcome.run().snapshot().status();
                assertThat(terminal).hasSize(1);
                assertThat(lastFrame(outcome).event()).isEqualTo(terminal.get(0));
                assertThat(status).isEqualTo(terminal.get(0).equals("done") ? RunStatus.COMPLETED : RunStatus.FAILED);
                if (status == RunStatus.FAILED) {
                    assertThat(outcome.run().snapshot().error().code()).isEqualTo(ErrorCode.RUN_TIMEOUT);
                }
            }
        }
    }

    @Test
    void aRunCompletesEvenWhenNoBrowserEverConnects() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = RunHarness.defaults(fake.uri())) {

            RunService.StartedRun started = harness.service.start("nobody is listening");
            RunHarness.awaitTerminal(started.run());

            assertThat(started.run().snapshot().status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(started.run().snapshot().output()).isEqualTo("This is a simulated answer.");
        }
    }

    @Test
    void aBrowserConnectionThatFailsNeverAffectsTheRun() throws Exception {
        CountDownLatch firstEventSent = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, call -> {
            call.startStream();
            call.step("first");
            firstEventSent.countDown();
            proceed.await();
            call.token("rest");
            call.event("done", "{}");
        }); RunHarness harness = RunHarness.defaults(fake.uri())) {
            RunService.StartedRun started = harness.service.start("question");
            RecordingEmitter emitter = new RecordingEmitter();
            emitter.failSends = true;
            started.stream().attach(emitter);
            assertThat(firstEventSent.await(10, TimeUnit.SECONDS)).isTrue();

            proceed.countDown();
            RunHarness.awaitTerminal(started.run());

            assertThat(started.run().snapshot().status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(started.run().snapshot().output()).isEqualTo("rest");
            assertThat(emitter.frames).isEmpty();
        }
    }

    @Test
    void everyRunGetsItsOwnCanonicalId() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = RunHarness.defaults(fake.uri())) {
            RunService.StartedRun a = harness.service.start("a");
            RunService.StartedRun b = harness.service.start("b");

            assertThat(a.run().id()).isNotEqualTo(b.run().id());
            assertThat(RunIds.isValid(a.run().id().toString())).isTrue();
            assertThat(UUID.fromString(a.run().snapshot().runId())).isEqualTo(a.run().id());
            RunHarness.awaitTerminal(a.run());
            RunHarness.awaitTerminal(b.run());
        }
    }
}
