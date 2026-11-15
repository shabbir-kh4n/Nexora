package com.nexora;

import static com.nexora.TestSupport.race;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

// No sleeps: threads are released together by a barrier, and each assertion is an invariant that
// holds for every interleaving.
class RunConcurrencyTests {

    private static final int ROUNDS = 300;

    private static Run runningRun() {
        Run run = new Run(UUID.randomUUID(), Clock.systemUTC());
        run.start();
        return run;
    }

    private static long accepted(List<Boolean> results) {
        return results.stream().filter(Boolean::booleanValue).count();
    }

    @Test
    void cancelRacingCompleteHasExactlyOneWinner() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Run run = runningRun();
            run.appendOutput("partial");

            List<Boolean> results = race(List.of(run::cancel, run::complete));

            assertThat(accepted(results)).isEqualTo(1);
            RunSnapshot snapshot = run.snapshot();
            assertThat(snapshot.status()).isEqualTo(results.get(0) ? RunStatus.CANCELLED : RunStatus.COMPLETED);
            assertThat(snapshot.output()).isEqualTo("partial");
            assertThat(run.snapshot()).isEqualTo(snapshot);
        }
    }

    @Test
    void cancelRacingFailureHasExactlyOneWinnerAndTheErrorMatches() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Run run = runningRun();

            List<Boolean> results = race(List.of(run::cancel, () -> run.fail(ErrorCode.UPSTREAM_UNAVAILABLE)));

            assertThat(accepted(results)).isEqualTo(1);
            RunSnapshot snapshot = run.snapshot();
            if (results.get(0)) {
                assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLED);
                assertThat(snapshot.error()).isNull();
            } else {
                assertThat(snapshot.status()).isEqualTo(RunStatus.FAILED);
                assertThat(snapshot.error().code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
            }
        }
    }

    @Test
    void simultaneousCancellationsAcceptExactlyOne() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Run run = runningRun();
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                tasks.add(run::cancel);
            }

            assertThat(accepted(race(tasks))).isEqualTo(1);
            assertThat(run.snapshot().status()).isEqualTo(RunStatus.CANCELLED);
        }
    }

    @Test
    void manyDifferentTerminalTransitionsAcceptExactlyOne() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Run run = runningRun();
            List<Callable<RunStatus>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                tasks.add(() -> run.complete() ? RunStatus.COMPLETED : null);
                tasks.add(() -> run.fail(ErrorCode.INTERNAL_ERROR) ? RunStatus.FAILED : null);
                tasks.add(() -> run.cancel() ? RunStatus.CANCELLED : null);
            }

            List<RunStatus> winners = race(tasks).stream().filter(s -> s != null).toList();

            assertThat(winners).hasSize(1);
            RunSnapshot snapshot = run.snapshot();
            assertThat(snapshot.status()).isEqualTo(winners.get(0));
            assertThat(snapshot.error() != null).isEqualTo(snapshot.status() == RunStatus.FAILED);
        }
    }

    @Test
    void cancelRacingStartWhileQueuedAlwaysEndsCancelled() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Run run = new Run(UUID.randomUUID(), Clock.systemUTC());

            List<Boolean> results = race(List.of(run::cancel, run::start));

            assertThat(results.get(0)).isTrue();
            assertThat(run.snapshot().status()).isEqualTo(RunStatus.CANCELLED);
        }
    }

    @Test
    void concurrentOutputKeepsEachWritersOrderAndLosesNothing() throws Exception {
        int writers = 8;
        int perWriter = 250;
        Run run = runningRun();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            tasks.add(() -> {
                boolean all = true;
                for (int n = 0; n < perWriter; n++) {
                    all &= run.appendOutput(writer + ":" + n + ";");
                }
                return all;
            });
        }

        assertThat(race(tasks)).containsOnly(true);

        List<List<Integer>> seen = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            seen.add(new ArrayList<>());
        }
        for (String token : run.snapshot().output().split(";")) {
            String[] parts = token.split(":");
            seen.get(Integer.parseInt(parts[0])).add(Integer.parseInt(parts[1]));
        }
        for (List<Integer> sequence : seen) {
            assertThat(sequence).hasSize(perWriter).isSorted();
            assertThat(sequence.get(perWriter - 1)).isEqualTo(perWriter - 1);
        }
    }

    @Test
    void concurrentStepsKeepEachWritersOrderAndLoseNothing() throws Exception {
        int writers = 8;
        int perWriter = 250;
        Run run = runningRun();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            tasks.add(() -> {
                boolean all = true;
                for (int n = 0; n < perWriter; n++) {
                    all &= run.addStep(writer + ":" + n);
                }
                return all;
            });
        }

        assertThat(race(tasks)).containsOnly(true);

        List<RunSnapshot.Step> steps = run.snapshot().steps();
        assertThat(steps).hasSize(writers * perWriter);
        int[] next = new int[writers];
        for (RunSnapshot.Step step : steps) {
            String[] parts = step.message().split(":");
            int writer = Integer.parseInt(parts[0]);
            assertThat(Integer.parseInt(parts[1])).isEqualTo(next[writer]++);
        }
    }

    @Test
    void nothingIsAcceptedOnceCancelHasReturned() throws Exception {
        for (int i = 0; i < 100; i++) {
            Run run = runningRun();
            AtomicBoolean cancelReturned = new AtomicBoolean();
            AtomicInteger acceptedAfterCancel = new AtomicInteger();
            AtomicInteger acceptedTotal = new AtomicInteger();
            List<Callable<Boolean>> tasks = new ArrayList<>();
            tasks.add(() -> {
                boolean cancelled = run.cancel();
                cancelReturned.set(true);
                return cancelled;
            });
            for (int w = 0; w < 6; w++) {
                tasks.add(() -> {
                    for (int n = 0; n < 200; n++) {
                        boolean afterCancel = cancelReturned.get();
                        if (run.appendOutput("x")) {
                            acceptedTotal.incrementAndGet();
                            if (afterCancel) {
                                acceptedAfterCancel.incrementAndGet();
                            }
                        }
                    }
                    return true;
                });
            }

            race(tasks);

            RunSnapshot snapshot = run.snapshot();
            assertThat(acceptedAfterCancel).hasValue(0);
            assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(snapshot.output()).hasSize(acceptedTotal.get());
            assertThat(run.snapshot()).isEqualTo(snapshot);
        }
    }

    @Test
    void snapshotsAreNeverTornWhileTheRunChanges() throws Exception {
        Run run = runningRun();
        AtomicBoolean finished = new AtomicBoolean();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Boolean>> tasks = new ArrayList<>();
        tasks.add(() -> {
            for (int n = 0; n < 2000; n++) {
                run.appendOutput("x");
                run.addStep("s");
            }
            run.fail(ErrorCode.INTERNAL_ERROR);
            finished.set(true);
            return true;
        });
        for (int r = 0; r < 3; r++) {
            tasks.add(() -> {
                do {
                    RunSnapshot snapshot = run.snapshot();
                    int gap = snapshot.output().length() - snapshot.steps().size();
                    if (gap != 0 && gap != 1) {
                        violations.add("output/steps gap " + gap);
                    }
                    if ((snapshot.status() == RunStatus.FAILED) != (snapshot.error() != null)) {
                        violations.add("status and error disagree");
                    }
                } while (!finished.get());
                return true;
            });
        }

        race(tasks);

        assertThat(violations).isEmpty();
    }
}
