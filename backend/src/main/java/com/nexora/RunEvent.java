package com.nexora;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public record RunEvent(
        String version,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("run_id") String runId,
        Type type,
        Instant timestamp,
        JsonNode data) {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public RunEvent {
        if (!"1".equals(version)) {
            throw new IllegalArgumentException("unsupported event version");
        }
        if (eventId == null || !UUID_FORMAT.matcher(eventId).matches()) {
            throw new IllegalArgumentException("event_id must be a UUID");
        }
        RunIds.require(runId);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(data, "data");
        switch (type) {
            case STEP -> requireTextFields(data, "message");
            case TOKEN -> requireTextFields(data, "text");
            case ERROR -> {
                requireTextFields(data, "code", "message");
                ErrorCode.valueOf(data.get("code").stringValue());
            }
            case DONE, HEARTBEAT -> requireTextFields(data);
        }
    }

    // Exactly these non-empty string fields and nothing else, as every event schema demands.
    private static void requireTextFields(JsonNode data, String... names) {
        if (!data.isObject() || data.size() != names.length) {
            throw new IllegalArgumentException("event data does not match its type");
        }
        for (String name : names) {
            JsonNode field = data.get(name);
            if (field == null || !field.isString() || field.stringValue().isEmpty()) {
                throw new IllegalArgumentException("event data does not match its type");
            }
        }
    }

    public enum Type {
        STEP,
        TOKEN,
        DONE,
        HEARTBEAT,
        ERROR;

        @JsonValue
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
