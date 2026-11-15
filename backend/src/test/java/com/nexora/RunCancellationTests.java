package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.nexora.RunHarness.RecordingEmitter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class RunCancellationTests {

    private static final String UNKNOWN_ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11";

    private static final String ERROR_DATA = "{\"code\":\"INTERNAL_ERROR\",\"message\":\"An internal error occurred.\"}";

    private static void awaitFrames(RecordingEmitter emitter, int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> emitter.frames.size() >= count);
    }

    // Streams a step and a token, then holds the connection open until the client closes it.
    private static FakeAiService.Script partialThenWait(AtomicBoolean connectionClosed) {
        return call -> {
            call.startStream();
            call.step("first");
            call.token("partial");
            connectionClosed.set(call.waitForClientToClose(Duration.ofSeconds(15)));
        };
    }

    @Test
    void cancellingARunningRunKeepsItsPartialWorkEndsTheStreamAndStopsPython() throws Exception {
        AtomicBoolean connectionClosed = new AtomicBoolean();
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, partialThenWait(connectionClosed));
                RunHarness harness = RunHarness.defaults(fake.uri())) {
            RunService.StartedRun started = harness.service.start("q");
            RecordingEmitter emitter = new RecordingEmitter();
            started.stream().attach(emitter);
            awaitFrames(emitter, 2);
            String id = started.run().id().toString();

            RunSnapshot snapshot = harness.service.cancel(id);

            assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(snapshot.output()).isEqualTo("partial");
            assertThat(snapshot.steps()).extracting(RunSnapshot.Step::message).containsExactly("first");
            assertThat(snapshot.error()).isNull();
            assertThat(emitter.awaitCompleted()).isTrue();
            assertThat(emitter.frames).extracting(SseParser.Frame::event).containsExactly("step", "token");
            await().atMost(Duration.ofSeconds(10)).untilTrue(connectionClosed);
            await().atMost(Duration.ofSeconds(10)).until(() -> fake.cancels.size() == 1);
            assertThat(fake.cancels).containsExactly(id);
            assertThat(harness.service.snapshot(id)).isEqualTo(snapshot);
        }
    }

    @Test
    void cancellingAgainReturnsTheSameSnapshotAndDoesNotTouchPythonAgain() throws Exception {
        AtomicBoolean connectionClosed = new AtomicBoolean();
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, partialThenWait(connectionClosed));
                RunHarness harness = RunHarness.defaults(fake.uri())) {
            RunService.StartedRun started = harness.service.start("q");
            RecordingEmitter emitter = new RecordingEmitter();
            started.stream().attach(emitter);
            awaitFrames(emitter, 2);
            String id = started.run().id().toString();

            RunSnapshot first = harness.service.cancel(id);
            await().atMost(Duration.ofSeconds(10)).until(() -> fake.cancels.size() == 1);
            RunSnapshot second = harness.service.cancel(id);
            RunSnapshot third = harness.service.cancel(id);

            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
            await().atMost(Duration.ofSeconds(10)).untilTrue(connectionClosed);
            assertThat(fake.cancels).hasSize(1);
        }
    }

    @Test
    void aFinishedRunIsNeverCancelled() throws Exception {
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                FakeAiService failing = new FakeAiService(TestSupport.SECRET, call -> {
                    call.startStream();
                    call.event("error", ERROR_DATA);
                });
                RunHarness harness = RunHarness.defaults(fake.uri());
                RunHarness failingHarness = RunHarness.defaults(failing.uri())) {
            Run completed = harness.service.start("q").run();
            Run failed = failingHarness.service.start("q").run();
            RunHarness.awaitTerminal(completed);
            RunHarness.awaitTerminal(failed);
            RunSnapshot completedBefore = completed.snapshot();
            RunSnapshot failedBefore = failed.snapshot();

            assertThat(harness.service.cancel(completed.id().toString())).isEqualTo(completedBefore);
            assertThat(failingHarness.service.cancel(failed.id().toString())).isEqualTo(failedBefore);

            assertThat(completedBefore.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(failedBefore.status()).isEqualTo(RunStatus.FAILED);
            assertThat(fake.cancels).isEmpty();
            assertThat(failing.cancels).isEmpty();
        }
    }

    @Test
    void aRunCancelledBeforeItStartsNeverReachesPythonAndItsStreamEnds() throws Exception {
        List<Runnable> deferred = new ArrayList<>();
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, FakeAiService::completeRun);
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofSeconds(10), 10, deferred::add)) {
            RunService.StartedRun started = harness.service.start("q");
            RecordingEmitter emitter = new RecordingEmitter();
            started.stream().attach(emitter);
            assertThat(started.run().status()).isEqualTo(RunStatus.QUEUED);

            RunSnapshot snapshot = harness.service.cancel(started.run().id().toString());
            deferred.get(0).run();

            assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(emitter.awaitCompleted()).isTrue();
            assertThat(emitter.frames).isEmpty();
            assertThat(started.run().status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(fake.requests).hasValue(0);
            assertThat(fake.cancels).isEmpty();
        }
    }

    @Test
    void aCancelledRunFreesItsCapacitySlot() throws Exception {
        AtomicBoolean connectionClosed = new AtomicBoolean();
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, partialThenWait(connectionClosed));
                RunHarness harness = new RunHarness(fake.uri(), TestSupport.SECRET, Duration.ofSeconds(30),
                        Duration.ofMillis(100), Duration.ofSeconds(10), 1)) {
            RunService.StartedRun first = harness.service.start("one");
            await().atMost(Duration.ofSeconds(10)).until(() -> fake.requests.get() == 1);
            assertThat(harness.service.start("two").run().status()).isEqualTo(RunStatus.FAILED);

            harness.service.cancel(first.run().id().toString());

            assertThat(harness.registry.activeCount()).isZero();
            assertThat(harness.service.start("three").run().status()).isNotEqualTo(RunStatus.FAILED);
        }
    }

    @Test
    void anUpstreamCancelFailureDoesNotUndoTheCancellation() throws Exception {
        AtomicBoolean connectionClosed = new AtomicBoolean();
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, partialThenWait(connectionClosed));
                RunHarness harness = RunHarness.defaults(fake.uri())) {
            fake.cancelStatus = 500;
            RunService.StartedRun started = harness.service.start("q");
            RecordingEmitter emitter = new RecordingEmitter();
            started.stream().attach(emitter);
            awaitFrames(emitter, 2);

            RunSnapshot snapshot = harness.service.cancel(started.run().id().toString());

            await().atMost(Duration.ofSeconds(10)).until(() -> fake.cancels.size() == 1);
            assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(started.run().snapshot().status()).isEqualTo(RunStatus.CANCELLED);
        }
    }

    @Test
    void anUnknownRunIsNotFoundForBothOperations() {
        try (RunHarness harness = RunHarness.defaults(TestSupport.closedPortUri())) {
            assertThatThrownBy(() -> harness.service.snapshot(UNKNOWN_ID))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.code()).isEqualTo(ErrorCode.RUN_NOT_FOUND);
                    });
            assertThatThrownBy(() -> harness.service.cancel(UNKNOWN_ID))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "3F2B8C1E-5D4A-4C7E-9B1A-2E6F0A8D7C11", "3f2b8c1e5d4a4c7e9b1a2e6f0a8d7c11"})
    void aMalformedRunIdIsAnInvalidRequestForBothOperations(String id) {
        try (RunHarness harness = RunHarness.defaults(TestSupport.closedPortUri())) {
            assertThatThrownBy(() -> harness.service.snapshot(id))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    });
            assertThatThrownBy(() -> harness.service.cancel(id))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    void aSnapshotShowsTheLiveStateOfARunningRun() throws Exception {
        AtomicBoolean connectionClosed = new AtomicBoolean();
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, partialThenWait(connectionClosed));
                RunHarness harness = RunHarness.defaults(fake.uri())) {
            String id = harness.service.start("q").run().id().toString();

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> harness.service.snapshot(id).output().equals("partial"));

            RunSnapshot snapshot = harness.service.snapshot(id);
            assertThat(snapshot.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(snapshot.steps()).extracting(RunSnapshot.Step::message).containsExactly("first");
            harness.service.cancel(id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"done", "error"})
    void cancelRacingTheTerminalEventLeavesExactlyOneConsistentOutcome(String terminal) throws Exception {
        FakeAiService.Script script = call -> {
            call.startStream();
            call.step("s");
            call.token("t");
            call.event(terminal, terminal.equals("done") ? "{}" : ERROR_DATA);
        };
        try (FakeAiService fake = new FakeAiService(TestSupport.SECRET, script);
                RunHarness harness = RunHarness.defaults(fake.uri())) {
            for (int i = 0; i < 30; i++) {
                RunService.StartedRun started = harness.service.start("q");
                RecordingEmitter emitter = new RecordingEmitter();
                started.stream().attach(emitter);
                String id = started.run().id().toString();
                List<Callable<RunSnapshot>> cancels = new ArrayList<>();
                for (int c = 0; c < 3; c++) {
                    cancels.add(() -> harness.service.cancel(id));
                }

                TestSupport.race(cancels);
                RunHarness.awaitTerminal(started.run());
                assertThat(emitter.awaitCompleted()).isTrue();

                RunSnapshot result = started.run().snapshot();
                List<String> events = emitter.frames.stream().map(SseParser.Frame::event).toList();
                long terminalEvents = events.stream().filter(e -> e.equals("done") || e.equals("error")).count();
                switch (result.status()) {
                    case CANCELLED -> assertThat(terminalEvents).isZero();
                    case COMPLETED -> assertThat(events).endsWith("done");
                    case FAILED -> assertThat(events).endsWith("error");
                    default -> throw new AssertionError("not terminal: " + result.status());
                }
                assertThat(terminalEvents).isLessThanOrEqualTo(1);
                assertThat(result.output()).isIn("", "t");
                assertThat(started.run().snapshot()).isEqualTo(result);
            }
        }
    }
}
