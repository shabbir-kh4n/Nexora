package com.nexora;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunSnapshot(
        @JsonProperty("run_id") String runId,
        RunStatus status,
        String output,
        List<Step> steps,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        ErrorResponse.ErrorBody error) {

    public RunSnapshot {
        RunIds.require(runId);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        steps = List.copyOf(steps);
        if ((status == RunStatus.FAILED) != (error != null)) {
            throw new IllegalArgumentException("error must be present exactly when the status is FAILED");
        }
    }

    public record Step(String message) {

        public Step {
            if (message == null || message.isEmpty()) {
                throw new IllegalArgumentException("message must not be empty");
            }
        }
    }
}
