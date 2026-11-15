package com.nexora;

import static com.nexora.TestSupport.race;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexora.RunRegistry.Registration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class RunRegistryTests {

    private static final Duration RETENTION = Duration.ofHours(1);

    private final TestSupport.MutableClock clock = new TestSupport.MutableClock();

    private Run run() {
        return new Run(UUID.randomUUID(), clock);
    }

    private Run runningRun() {
        Run run = run();
        run.start();
        return run;
    }

    private RunRegistry registry(int maxActive) {
        return new RunRegistry(maxActive, RETENTION, clock);
    }

    @Test
    void aRegisteredRunCanBeFoundByItsId() {
        RunRegistry registry = registry(5);
        Run run = run();

        assertThat(registry.register(run)).isEqualTo(Registration.REGISTERED);

        assertThat(registry.find(run.id())).containsSame(run);
        assertThat(registry.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aDuplicateIdIsRejectedAndTheOriginalKept() {
        RunRegistry registry = registry(5);
        Run original = run();
        registry.register(original);

        assertThat(registry.register(new Run(original.id(), clock))).isEqualTo(Registration.DUPLICATE);

        assertThat(registry.find(original.id())).containsSame(original);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void capacityCountsActiveRunsOnly() {
        RunRegistry registry = registry(2);
        Run first = run();
        Run second = runningRun();
        registry.register(first);
        registry.register(second);

        assertThat(registry.register(run())).isEqualTo(Registration.AT_CAPACITY);
        assertThat(registry.activeCount()).isEqualTo(2);

        first.cancel();
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(registry.register(run())).isEqualTo(Registration.REGISTERED);
    }

    @Test
    void aRunThatAlreadyEndedIsAcceptedAtCapacityWithoutUsingASlotAndExpiresLater() {
        RunRegistry registry = registry(1);
        registry.register(runningRun());
        Run failed = run();
        failed.fail(ErrorCode.RUN_CAPACITY_EXCEEDED);

        assertThat(registry.register(failed)).isEqualTo(Registration.REGISTERED);

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.find(failed.id())).containsSame(failed);
        clock.advance(RETENTION);
        assertThat(registry.find(failed.id())).isEmpty();
    }

    @Test
    void aRejectedRunIsNotStored() {
        RunRegistry registry = registry(1);
        registry.register(run());
        Run rejected = run();

        registry.register(rejected);

        assertThat(registry.find(rejected.id())).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void aTerminalRunIsKeptUntilTheRetentionPasses() {
        RunRegistry registry = registry(5);
        Run run = runningRun();
        registry.register(run);
        run.complete();

        clock.advance(RETENTION.minusNanos(1));
        assertThat(registry.find(run.id())).isPresent();

        clock.advance(Duration.ofNanos(1));
        assertThat(registry.find(run.id())).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void retentionIsMeasuredFromTheMomentTheRunEnded() {
        RunRegistry registry = registry(5);
        Run run = runningRun();
        registry.register(run);

        clock.advance(Duration.ofMinutes(50));
        run.fail(ErrorCode.INTERNAL_ERROR);
        clock.advance(Duration.ofMinutes(50));

        assertThat(registry.find(run.id())).isPresent();
        clock.advance(Duration.ofMinutes(10));
        assertThat(registry.find(run.id())).isEmpty();
    }

    @Test
    void anActiveRunIsNeverEvictedHoweverOldItIs() {
        RunRegistry registry = registry(5);
        Run queued = run();
        Run running = runningRun();
        registry.register(queued);
        registry.register(running);

        clock.advance(Duration.ofDays(30));
        registry.register(run());

        assertThat(registry.find(queued.id())).isPresent();
        assertThat(registry.find(running.id())).isPresent();
    }

    @Test
    void expiredRunsAreSweptOnRegistrationSoTheRegistryDoesNotGrowWithoutBound() {
        RunRegistry registry = registry(1000);
        for (int i = 0; i < 200; i++) {
            Run run = runningRun();
            registry.register(run);
            run.complete();
        }
        assertThat(registry.size()).isEqualTo(200);

        clock.advance(RETENTION);
        registry.register(run());

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationNeverExceedsTheLimit() throws Exception {
        for (int round = 0; round < 50; round++) {
            RunRegistry registry = registry(10);
            List<Callable<Registration>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                tasks.add(() -> registry.register(runningRun()));
            }

            List<Registration> results = race(tasks);

            assertThat(results.stream().filter(r -> r == Registration.REGISTERED)).hasSize(10);
            assertThat(results.stream().filter(r -> r == Registration.AT_CAPACITY)).hasSize(54);
            assertThat(registry.size()).isEqualTo(10);
        }
    }

    @Test
    void concurrentRegistrationOfOneIdAcceptsExactlyOne() throws Exception {
        for (int round = 0; round < 50; round++) {
            RunRegistry registry = registry(100);
            UUID id = UUID.randomUUID();
            List<Callable<Registration>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> registry.register(new Run(id, clock)));
            }

            List<Registration> results = race(tasks);

            assertThat(results.stream().filter(r -> r == Registration.REGISTERED)).hasSize(1);
            assertThat(results.stream().filter(r -> r == Registration.DUPLICATE)).hasSize(15);
        }
    }

    @Test
    void lookupsDuringRegistrationNeverFail() throws Exception {
        RunRegistry registry = registry(10_000);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            tasks.add(() -> {
                for (int i = 0; i < 500; i++) {
                    Run run = run();
                    registry.register(run);
                    if (registry.find(run.id()).isEmpty()) {
                        return false;
                    }
                }
                return true;
            });
        }

        assertThat(race(tasks)).containsOnly(true);
        assertThat(registry.size()).isEqualTo(2000);
    }
}
