package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Uses the application's own JsonMapper so the tests cover its real configuration.
@SpringBootTest
class ContractDtoTests {

    private static final String RUN_ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11";
    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    @Autowired
    private JsonMapper mapper;

    private JsonNode tree(String json) {
        return mapper.readTree(json);
    }

    private String request(String question) {
        return mapper.createObjectNode().put("question", question).toString();
    }

    private RunSnapshot snapshot(RunStatus status, ErrorResponse.ErrorBody error) {
        return new RunSnapshot(RUN_ID, status, "out", List.of(new RunSnapshot.Step("s")), T0, T0, error);
    }

    @Test
    void validRequestFixtureIsAccepted() {
        RunRequest parsed = mapper.readValue(OpenApiFiles.fixture("public-run-request.json"), RunRequest.class);

        assertThat(parsed.question()).isEqualTo("What changed in the latest Spring Boot release?");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n\t "})
    void whitespaceOnlyQuestionIsValidBecauseTheContractOnlyRequiresOneCharacter(String question) {
        assertThat(mapper.readValue(request(question), RunRequest.class).question()).isEqualTo(question);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequestsAreRejected(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, RunRequest.class)).isInstanceOf(JacksonException.class);
    }

    static Stream<String> invalidRequests() {
        return Stream.of(
                "{\"question\":\"\"}",
                "{\"question\":null}",
                "{}",
                "{\"question\":\"hi\",\"extra\":1}",
                "{\"question\":123}",
                "{\"question\":1.5}",
                "{\"question\":true}",
                "{\"question\":\"hi\",\"run_id\":\"3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11\"}",
                "{\"question\":\"" + "x".repeat(2001) + "\"}",
                "{\"question\":");
    }

    @Test
    void questionLengthBoundaryIsTwoThousand() {
        assertThat(mapper.readValue(request("x".repeat(2000)), RunRequest.class)).isNotNull();
        assertThatThrownBy(() -> mapper.readValue(request("x".repeat(2001)), RunRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void questionLengthCountsCodePointsNotUtf16Units() {
        String twoThousandEmoji = "😀".repeat(2000);
        assertThat(twoThousandEmoji.length()).isEqualTo(4000);

        assertThat(mapper.readValue(request(twoThousandEmoji), RunRequest.class)).isNotNull();
        assertThatThrownBy(() -> mapper.readValue(request(twoThousandEmoji + "😀"), RunRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-snapshot.json", "run-snapshot-cancelled.json", "run-snapshot-failed.json"})
    void snapshotFixturesRoundTripUnchanged(String name) {
        String fixture = OpenApiFiles.fixture(name);

        RunSnapshot parsed = mapper.readValue(fixture, RunSnapshot.class);

        assertThat(tree(mapper.writeValueAsString(parsed))).isEqualTo(tree(fixture));
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void nonFailedSnapshotsRejectAnErrorAndOmitTheField(RunStatus status) {
        assertThatThrownBy(() -> snapshot(status, ErrorResponse.ErrorBody.of(ErrorCode.INTERNAL_ERROR)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(tree(mapper.writeValueAsString(snapshot(status, null))).has("error")).isFalse();
    }

    @Test
    void failedSnapshotRequiresAnErrorAndSerializesIt() {
        assertThatThrownBy(() -> snapshot(RunStatus.FAILED, null)).isInstanceOf(IllegalArgumentException.class);

        JsonNode json = tree(mapper.writeValueAsString(
                snapshot(RunStatus.FAILED, ErrorResponse.ErrorBody.of(ErrorCode.UPSTREAM_UNAVAILABLE))));

        assertThat(json.get("error").get("code").stringValue()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void snapshotJsonViolatingTheErrorRuleIsRejected() {
        ObjectNode failedWithoutError = (ObjectNode) tree(OpenApiFiles.fixture("run-snapshot-failed.json"));
        failedWithoutError.remove("error");
        ObjectNode runningWithError = (ObjectNode) tree(OpenApiFiles.fixture("run-snapshot.json"));
        runningWithError.set("error", tree(OpenApiFiles.fixture("error-response.json")).get("error"));

        assertThatThrownBy(() -> mapper.readValue(failedWithoutError.toString(), RunSnapshot.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(runningWithError.toString(), RunSnapshot.class))
                .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run_id", "status", "output", "steps", "created_at", "updated_at"})
    void snapshotKeepsItsSixRequiredFields(String field) {
        ObjectNode json = (ObjectNode) tree(OpenApiFiles.fixture("run-snapshot.json"));
        json.remove(field);

        assertThatThrownBy(() -> mapper.readValue(json.toString(), RunSnapshot.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void snapshotRejectsAnEmptyStepMessageAndANonCanonicalRunId() {
        assertThatThrownBy(() -> new RunSnapshot.Step("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunSnapshot(RUN_ID.toUpperCase(), RunStatus.RUNNING, "", List.of(), T0, T0, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void timestampsSerializeAsUtcWithTrailingZRegardlessOfTheJvmTimeZone() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Kolkata");

        JsonNode json = tree(mapper.writeValueAsString(
                new RunSnapshot(RUN_ID, RunStatus.RUNNING, "", List.of(), Instant.parse("2026-11-15T10:00:00.123Z"),
                        Instant.now(), null)));

        assertThat(json.get("created_at").stringValue()).isEqualTo("2026-11-15T10:00:00.123Z");
        assertThat(json.get("updated_at").stringValue()).endsWith("Z").doesNotContain("+");
        assertThat(json.get("created_at").isString()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "step-event.json", "token-event.json", "done-event.json", "heartbeat-event.json", "error-event.json"})
    void eventFixturesRoundTripUnchanged(String name) {
        String fixture = OpenApiFiles.fixture(name);

        RunEvent parsed = mapper.readValue(fixture, RunEvent.class);

        assertThat(tree(mapper.writeValueAsString(parsed))).isEqualTo(tree(fixture));
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void invalidEventsAreRejected(String fixture, String field, String json) {
        ObjectNode node = (ObjectNode) tree(OpenApiFiles.fixture(fixture));
        if (json == null) {
            node.remove(field);
        } else {
            node.set(field, tree(json));
        }

        assertThatThrownBy(() -> mapper.readValue(node.toString(), RunEvent.class))
                .isInstanceOf(JacksonException.class);
    }

    static Stream<Arguments> invalidEvents() {
        return Stream.of(
                args("step-event.json", "version", "\"2\""),
                args("step-event.json", "type", "\"cancelled\""),
                args("step-event.json", "run_id", "\"3F2B8C1E-5D4A-4C7E-9B1A-2E6F0A8D7C11\""),
                args("step-event.json", "event_id", "\"nope\""),
                args("step-event.json", "timestamp", "\"yesterday\""),
                args("step-event.json", "data", "{\"message\":\"\"}"),
                args("step-event.json", "data", "{\"message\":\"x\",\"extra\":\"y\"}"),
                args("token-event.json", "data", "{\"text\":\"\"}"),
                args("token-event.json", "data", "{\"message\":\"x\"}"),
                args("done-event.json", "data", "{\"unexpected\":\"x\"}"),
                args("heartbeat-event.json", "data", "[]"),
                args("error-event.json", "data", "{\"code\":\"NOT_A_CODE\",\"message\":\"m\"}"),
                args("error-event.json", "data", "{\"code\":\"INTERNAL_ERROR\"}"),
                args("step-event.json", "data", null),
                args("step-event.json", "event_id", null));
    }

    private static Arguments args(String fixture, String field, String json) {
        return Arguments.of(fixture, field, json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"error-response.json", "run-not-found-response.json"})
    void errorResponseFixturesRoundTripUnchanged(String name) {
        String fixture = OpenApiFiles.fixture(name);

        ErrorResponse parsed = mapper.readValue(fixture, ErrorResponse.class);

        assertThat(tree(mapper.writeValueAsString(parsed))).isEqualTo(tree(fixture));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void errorEnvelopeHasExactlyCodeAndMessage(ErrorCode code) {
        JsonNode json = tree(mapper.writeValueAsString(ErrorResponse.of(code)));

        assertThat(json.propertyNames()).containsExactly("error");
        assertThat(json.get("error").propertyNames()).containsExactly("code", "message");
        assertThat(json.get("error").get("code").stringValue()).isEqualTo(code.name());
        assertThat(json.get("error").get("message").stringValue()).isEqualTo(code.message()).isNotBlank();
    }

    @Test
    void runTimeoutHasTheApprovedMessage() {
        assertThat(ErrorCode.RUN_TIMEOUT.message()).isEqualTo("The run exceeded the maximum allowed time.");
    }

    @Test
    void capacityExceededHasTheApprovedMessage() {
        assertThat(ErrorCode.RUN_CAPACITY_EXCEEDED.message())
                .isEqualTo("Nexora is currently at capacity. Please try again shortly.");
    }

    record Tolerant(String a) {
    }

    @Test
    void unknownFieldsAreRejectedOnlyForTheRequestNotForTheMapperAsAWhole() {
        assertThat(mapper.readValue("{\"a\":\"x\",\"extra\":1}", Tolerant.class).a()).isEqualTo("x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "internal"})
    void errorCodesEqualTheOpenApiEnumInOrder(String document) {
        assertThat(Stream.of(ErrorCode.values()).map(Enum::name).toList())
                .isEqualTo(OpenApiFiles.errorCodes(document));
    }
}
