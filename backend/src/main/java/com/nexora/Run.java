package com.nexora;

import static com.nexora.RunStatus.CANCELLED;
import static com.nexora.RunStatus.COMPLETED;
import static com.nexora.RunStatus.FAILED;
import static com.nexora.RunStatus.QUEUED;
import static com.nexora.RunStatus.RUNNING;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

// State of one run. Every state-changing method returns whether it was applied, so a caller learns it lost a race.
public final class Run {

    private final UUID id;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock: one lock for all fields, so a snapshot never mixes two states.
    private final StringBuilder output = new StringBuilder();
    private final List<RunSnapshot.Step> steps = new ArrayList<>();
    private final Instant createdAt;
    private RunStatus status = QUEUED;
    private ErrorResponse.ErrorBody error;
    private Instant updatedAt;

    public Run(UUID id, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.createdAt = clock.instant();
        this.updatedAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public RunStatus status() {
        lock.lock();
        try {
            return status;
        } finally {
            lock.unlock();
        }
    }

    // Stable once the run is terminal, which is how retention measures its age.
    public Instant updatedAt() {
        lock.lock();
        try {
            return updatedAt;
        } finally {
            lock.unlock();
        }
    }

    public boolean start() {
        return transition(RUNNING, null);
    }

    public boolean complete() {
        return transition(COMPLETED, null);
    }

    // Only a code is accepted so the stored message is always the fixed, user-safe one.
    public boolean fail(ErrorCode code) {
        return transition(FAILED, ErrorResponse.ErrorBody.of(Objects.requireNonNull(code, "code")));
    }

    public boolean cancel() {
        return transition(CANCELLED, null);
    }

    // Output and steps are accepted only while RUNNING; anything else is a late or premature event.
    public boolean appendOutput(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        lock.lock();
        try {
            if (status != RUNNING) {
                return false;
            }
            output.append(text);
            updatedAt = clock.instant();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean addStep(String message) {
        RunSnapshot.Step step = new RunSnapshot.Step(message);
        lock.lock();
        try {
            if (status != RUNNING) {
                return false;
            }
            steps.add(step);
            updatedAt = clock.instant();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public RunSnapshot snapshot() {
        RunStatus currentStatus;
        String currentOutput;
        List<RunSnapshot.Step> currentSteps;
        Instant currentUpdatedAt;
        ErrorResponse.ErrorBody currentError;
        lock.lock();
        try {
            currentStatus = status;
            currentOutput = output.toString();
            currentSteps = List.copyOf(steps);
            currentUpdatedAt = updatedAt;
            currentError = error;
        } finally {
            lock.unlock();
        }
        // Built outside the lock; the copies above cannot change.
        return new RunSnapshot(
                id.toString(), currentStatus, currentOutput, currentSteps, createdAt, currentUpdatedAt, currentError);
    }

    private boolean transition(RunStatus target, ErrorResponse.ErrorBody failure) {
        lock.lock();
        try {
            if (!allowed(status, target)) {
                return false;
            }
            status = target;
            error = failure;
            updatedAt = clock.instant();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static boolean allowed(RunStatus from, RunStatus to) {
        return switch (from) {
            case QUEUED -> to == RUNNING || to == FAILED || to == CANCELLED;
            case RUNNING -> to == COMPLETED || to == FAILED || to == CANCELLED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
