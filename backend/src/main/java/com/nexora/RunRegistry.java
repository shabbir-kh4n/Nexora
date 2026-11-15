package com.nexora;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

// Holds runs in memory. Active runs are never evicted; terminal runs are dropped once the retention has passed.
public final class RunRegistry {

    public enum Registration {
        REGISTERED,
        DUPLICATE,
        AT_CAPACITY
    }

    private final ConcurrentHashMap<UUID, Run> runs = new ConcurrentHashMap<>();
    // Makes the capacity check and the insert one step, so concurrent registrations cannot exceed the limit.
    private final ReentrantLock admission = new ReentrantLock();
    private final int maxActive;
    private final Duration retention;
    private final Clock clock;

    public RunRegistry(int maxActive, Duration retention, Clock clock) {
        this.maxActive = maxActive;
        this.retention = retention;
        this.clock = clock;
    }

    public Registration register(Run run) {
        admission.lock();
        try {
            // Eviction happens only here and in find(), which are the only places the map can grow or be read.
            runs.values().removeIf(this::expired);
            if (runs.containsKey(run.id())) {
                return Registration.DUPLICATE;
            }
            // A run that already ended holds no slot, which lets a capacity failure be kept for retention.
            if (!run.status().isTerminal() && activeCount() >= maxActive) {
                return Registration.AT_CAPACITY;
            }
            runs.put(run.id(), run);
            return Registration.REGISTERED;
        } finally {
            admission.unlock();
        }
    }

    public Optional<Run> find(UUID id) {
        Run run = runs.get(id);
        if (run != null && expired(run)) {
            runs.remove(id, run);
            return Optional.empty();
        }
        return Optional.ofNullable(run);
    }

    public int activeCount() {
        return (int) runs.values().stream().filter(run -> !run.status().isTerminal()).count();
    }

    public int size() {
        return runs.size();
    }

    private boolean expired(Run run) {
        // updatedAt no longer moves once a run is terminal, so it is the time the run ended.
        return run.status().isTerminal() && !run.updatedAt().plus(retention).isAfter(clock.instant());
    }
}
