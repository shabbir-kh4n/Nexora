package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RunTests {

    static final UUID ID = UUID.fromString("3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11");
    static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    static final Set<RunStatus> TERMINAL = EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED);

    // Each reading is one second later, so every accepted change gets a distinct, predictable timestamp.
    static final class TickingClock extends Clock {
        private final AtomicLong ticks = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return T0.plusSeconds(ticks.getAndIncrement());
        }
    }

    enum Op {
        START(RunStatus.RUNNING, Run::start),
        COMPLETE(RunStatus.COMPLETED, Run::complete),
        FAIL(RunStatus.FAILED, run -> run.fail(ErrorCode.INTERNAL_ERROR)),
        CANCEL(RunStatus.CANCELLED, Run::cancel);

        final RunStatus target;
        final Predicate<Run> apply;

        Op(RunStatus target, Predicate<Run> apply) {
            this.target = target;
            this.apply = apply;
        }
    }

    // Written out from the approved lifecycle, not derived from the implementation.
    private static final Map<RunStatus, Set<Op>> ALLOWED = Map.of(
            RunStatus.QUEUED, EnumSet.of(Op.START, Op.FAIL, Op.CANCEL),
            RunStatus.RUNNING, EnumSet.of(Op.COMPLETE, Op.FAIL, Op.CANCEL),
            RunStatus.COMPLETED, EnumSet.noneOf(Op.class),
            RunStatus.FAILED, EnumSet.noneOf(Op.class),
            RunStatus.CANCELLED, EnumSet.noneOf(Op.class));

    static Run newRun() {
        return new Run(ID, new TickingClock());
    }

    static Run runIn(RunStatus status) {
        Run run = newRun();
        switch (status) {
            case QUEUED -> { }
            case RUNNING -> run.start();
            case COMPLETED -> {
                run.start();
                run.complete();
            }
            case FAILED -> {
                run.start();
                run.fail(ErrorCode.UPSTREAM_UNAVAILABLE);
            }
            case CANCELLED -> {
                run.start();
                run.cancel();
            }
        }
        return run;
    }

    static Stream<Arguments> transitionMatrix() {
        return Stream.of(RunStatus.values())
                .flatMap(from -> Stream.of(Op.values()).map(op -> Arguments.of(from, op, ALLOWED.get(from).contains(op))));
    }

    @Test
    void initialStateIsQueuedAndEmpty() {
        RunSnapshot snapshot = newRun().snapshot();

        assertThat(snapshot.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(snapshot.runId()).isEqualTo(ID.toString());
        assertThat(snapshot.output()).isEmpty();
        assertThat(snapshot.steps()).isEmpty();
        assertThat(snapshot.error()).isNull();
        assertThat(snapshot.createdAt()).isEqualTo(T0).isEqualTo(snapshot.updatedAt());
    }

    @ParameterizedTest
    @MethodSource("transitionMatrix")
    void everyTransitionIsAcceptedOnlyWhenTheLifecycleAllowsIt(RunStatus from, Op op, boolean allowed) {
        Run run = runIn(from);
        RunSnapshot before = run.snapshot();

        boolean applied = op.apply.test(run);

        assertThat(applied).isEqualTo(allowed);
        if (allowed) {
            assertThat(run.snapshot().status()).isEqualTo(op.target);
        } else {
            assertThat(run.snapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalRunsRejectEveryUpdateAndStayIdentical(RunStatus terminal) {
        Run run = runIn(terminal);
        run.appendOutput("kept");
        RunSnapshot before = run.snapshot();

        assertThat(run.appendOutput("late")).isFalse();
        assertThat(run.addStep("late")).isFalse();
        for (Op op : Op.values()) {
            assertThat(op.apply.test(run)).isFalse();
        }

        assertThat(run.snapshot()).isEqualTo(before);
    }

    @Test
    void outputAccumulatesInOrder() {
        Run run = runIn(RunStatus.RUNNING);

        run.appendOutput("This ");
        run.appendOutput("is ");
        run.appendOutput("ordered.");

        assertThat(run.snapshot().output()).isEqualTo("This is ordered.");
    }

    @Test
    void stepsKeepInsertionOrder() {
        Run run = runIn(RunStatus.RUNNING);

        run.addStep("first");
        run.addStep("second");
        run.addStep("third");

        assertThat(run.snapshot().steps()).extracting(RunSnapshot.Step::message).containsExactly("first", "second", "third");
    }

    @Test
    void aSnapshotCannotBeUsedToChangeTheRun() {
        Run run = runIn(RunStatus.RUNNING);
        run.addStep("one");
        RunSnapshot snapshot = run.snapshot();

        assertThatThrownBy(() -> snapshot.steps().add(new RunSnapshot.Step("x"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(run.snapshot().steps()).hasSize(1);
    }

    @Test
    void anEarlierSnapshotDoesNotChangeWhenTheRunLaterDoes() {
        Run run = runIn(RunStatus.RUNNING);
        run.appendOutput("a");
        run.addStep("s1");
        RunSnapshot earlier = run.snapshot();

        run.appendOutput("b");
        run.addStep("s2");
        run.complete();

        assertThat(earlier.output()).isEqualTo("a");
        assertThat(earlier.steps()).extracting(RunSnapshot.Step::message).containsExactly("s1");
        assertThat(earlier.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.snapshot().output()).isEqualTo("ab");
    }

    @Test
    void timestampsMoveOnlyWhenAChangeIsAccepted() {
        Run run = newRun();
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0);

        assertThat(run.appendOutput("early")).isFalse();
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0);

        run.start();
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0.plusSeconds(1));
        run.addStep("s");
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0.plusSeconds(2));
        run.appendOutput("t");
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0.plusSeconds(3));

        assertThat(run.start()).isFalse();
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0.plusSeconds(3));

        run.complete();
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0.plusSeconds(4));
        run.appendOutput("late");
        assertThat(run.snapshot().updatedAt()).isEqualTo(T0.plusSeconds(4));
        assertThat(run.snapshot().createdAt()).isEqualTo(T0);
    }

    @Test
    void aFailedRunCarriesTheFixedErrorForItsCode() {
        Run run = runIn(RunStatus.RUNNING);

        assertThat(run.fail(ErrorCode.RUN_TIMEOUT)).isTrue();

        RunSnapshot snapshot = run.snapshot();
        assertThat(snapshot.status()).isEqualTo(RunStatus.FAILED);
        assertThat(snapshot.error()).isEqualTo(ErrorResponse.ErrorBody.of(ErrorCode.RUN_TIMEOUT));
        assertThat(snapshot.error().message()).isEqualTo("The run exceeded the maximum allowed time.");
    }

    @Test
    void aQueuedRunCanFailBeforeItStarts() {
        Run run = newRun();

        assertThat(run.fail(ErrorCode.UPSTREAM_UNAVAILABLE)).isTrue();

        assertThat(run.snapshot().status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.snapshot().error().code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStatusHasNoError(RunStatus status) {
        assertThat(runIn(status).snapshot().error()).isNull();
    }

    @Test
    void failingWithoutACodeIsRejectedAndChangesNothing() {
        Run run = runIn(RunStatus.RUNNING);
        RunSnapshot before = run.snapshot();

        assertThatThrownBy(() -> run.fail(null)).isInstanceOf(NullPointerException.class);

        assertThat(run.snapshot()).isEqualTo(before);
    }

    @Test
    void emptyOrMissingTextIsRejectedAndChangesNothing() {
        Run run = runIn(RunStatus.RUNNING);
        RunSnapshot before = run.snapshot();

        for (String bad : new String[] {null, ""}) {
            assertThatThrownBy(() -> run.appendOutput(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> run.addStep(bad)).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(run.snapshot()).isEqualTo(before);
    }

    @Test
    void outputAndStepsAreNotAcceptedBeforeTheRunStarts() {
        Run run = newRun();

        assertThat(run.appendOutput("x")).isFalse();
        assertThat(run.addStep("x")).isFalse();
        assertThat(run.snapshot().output()).isEmpty();
        assertThat(run.snapshot().steps()).isEmpty();
    }

    @Test
    void constructorRejectsMissingArguments() {
        assertThatThrownBy(() -> new Run(null, Clock.systemUTC())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Run(ID, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancelWhileQueuedEndsTheRunWithoutOutput() {
        Run run = newRun();

        assertThat(run.cancel()).isTrue();

        assertThat(run.snapshot().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.snapshot().output()).isEmpty();
        assertThat(run.start()).isFalse();
    }

    @Test
    void cancelWhileRunningKeepsThePartialOutputAndSteps() {
        Run run = runIn(RunStatus.RUNNING);
        run.appendOutput("This is a ");
        run.addStep("Planning");

        assertThat(run.cancel()).isTrue();

        RunSnapshot snapshot = run.snapshot();
        assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(snapshot.output()).isEqualTo("This is a ");
        assertThat(snapshot.steps()).extracting(RunSnapshot.Step::message).containsExactly("Planning");
        assertThat(snapshot.error()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"COMPLETED", "FAILED"})
    void cancelAfterCompletionOrFailureChangesNothing(RunStatus terminal) {
        Run run = runIn(terminal);
        RunSnapshot before = run.snapshot();

        assertThat(run.cancel()).isFalse();

        assertThat(run.snapshot()).isEqualTo(before);
    }

    @Test
    void repeatedCancellationIsAcceptedOnceAndLeavesTheFirstOutcome() {
        Run run = runIn(RunStatus.RUNNING);

        assertThat(run.cancel()).isTrue();
        RunSnapshot afterFirst = run.snapshot();

        assertThat(List.of(run.cancel(), run.cancel(), run.cancel())).containsOnly(false);
        assertThat(run.snapshot()).isEqualTo(afterFirst);
    }

    @Test
    void updatesAfterCancellationAreIgnored() {
        Run run = runIn(RunStatus.RUNNING);
        run.appendOutput("kept");
        run.cancel();
        RunSnapshot cancelled = run.snapshot();

        assertThat(run.appendOutput("late")).isFalse();
        assertThat(run.addStep("late")).isFalse();
        assertThat(run.complete()).isFalse();
        assertThat(run.fail(ErrorCode.INTERNAL_ERROR)).isFalse();

        assertThat(run.snapshot()).isEqualTo(cancelled);
    }

    @Test
    void aStepAfterCompletionAndADuplicateCompletionAreIgnored() {
        Run run = runIn(RunStatus.RUNNING);
        run.complete();
        RunSnapshot completed = run.snapshot();

        assertThat(run.addStep("late")).isFalse();
        assertThat(run.complete()).isFalse();
        assertThat(run.fail(ErrorCode.INTERNAL_ERROR)).isFalse();

        assertThat(run.snapshot()).isEqualTo(completed);
    }

    @Test
    void aDuplicateFailureKeepsTheFirstError() {
        Run run = runIn(RunStatus.RUNNING);
        run.fail(ErrorCode.UPSTREAM_UNAVAILABLE);

        assertThat(run.fail(ErrorCode.RUN_TIMEOUT)).isFalse();

        assertThat(run.snapshot().error().code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void terminalStatusesAreExactlyTheThreeEndStates() {
        assertThat(Stream.of(RunStatus.values()).filter(s -> !ALLOWED.get(s).isEmpty()).toList())
                .containsExactlyInAnyOrder(RunStatus.QUEUED, RunStatus.RUNNING);
        assertThat(TERMINAL).containsExactlyInAnyOrder(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED);
    }
}
