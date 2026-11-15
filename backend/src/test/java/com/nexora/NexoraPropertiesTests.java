package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

class NexoraPropertiesTests {

    private static final String SECRET = "s3cr3t-value-that-must-not-leak";

    private static final Map<String, String> VALID = Map.of(
            "nexora.ai-service.url", "http://127.0.0.1:8000",
            "nexora.internal.shared-secret", SECRET,
            "nexora.run.max-duration", "PT3M",
            "nexora.run.heartbeat-interval", "PT15S",
            "nexora.run.upstream-heartbeat-timeout", "PT40S",
            "nexora.run.terminal-retention", "PT1H",
            "nexora.run.max-active-runs", "5");

    @Configuration
    @EnableConfigurationProperties(NexoraProperties.class)
    static class Config {
    }

    // A null value removes the property, an empty one models an unset environment placeholder.
    private static ApplicationContextRunner runner(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>(VALID);
        overrides.forEach((key, value) -> {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        });
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    private static Map<String, String> override(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    void validConfigurationBinds() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            NexoraProperties properties = context.getBean(NexoraProperties.class);
            assertThat(properties.aiService().url()).isEqualTo(URI.create("http://127.0.0.1:8000"));
            assertThat(properties.run().maxDuration()).isEqualTo(Duration.ofMinutes(3));
            assertThat(properties.run().heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.run().upstreamHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(40));
            assertThat(properties.run().terminalRetention()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.run().maxActiveRuns()).isEqualTo(5);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void invalidOrMissingSettingsStopStartupAndNameTheProperty(String key, String value, String expected) {
        runner(override(key, value)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage())
                    .contains(expected);
        });
    }

    static Stream<Arguments> invalidSettings() {
        String secret = "nexora.internal.shared-secret";
        String slots = "nexora.run.max-active-runs";
        String timeout = "nexora.run.upstream-heartbeat-timeout";
        return Stream.of(
                Arguments.of(secret, null, secret),
                Arguments.of(secret, "", secret),
                Arguments.of(secret, "   ", secret),
                Arguments.of(slots, null, slots),
                Arguments.of(slots, "", slots),
                Arguments.of(slots, "0", slots),
                Arguments.of(slots, "-3", slots),
                Arguments.of(timeout, null, timeout),
                Arguments.of(timeout, "", timeout),
                Arguments.of(timeout, "PT15S", timeout),
                Arguments.of(timeout, "PT5S", timeout),
                Arguments.of(timeout, "PT0S", timeout),
                Arguments.of("nexora.run.max-duration", "PT0S", "nexora.run.max-duration"),
                Arguments.of("nexora.run.max-duration", "-PT1M", "nexora.run.max-duration"),
                Arguments.of("nexora.run.max-duration", null, "nexora.run.max-duration"),
                Arguments.of("nexora.run.heartbeat-interval", "PT0S", "nexora.run.heartbeat-interval"),
                Arguments.of("nexora.run.terminal-retention", "PT0S", "nexora.run.terminal-retention"),
                Arguments.of("nexora.run.terminal-retention", null, "nexora.run.terminal-retention"),
                Arguments.of("nexora.ai-service.url", null, "nexora.ai-service.url"),
                Arguments.of("nexora.ai-service.url", "", "nexora.ai-service.url"),
                Arguments.of("nexora.ai-service.url", "/relative/path", "nexora.ai-service.url"),
                Arguments.of("nexora.ai-service.url", "ftp://127.0.0.1", "nexora.ai-service.url"),
                Arguments.of("nexora.ai-service.url", "http://user:pa55word@127.0.0.1:8000", "nexora.ai-service.url"));
    }

    @Test
    void aMalformedNumberOrDurationAlsoStopsStartup() {
        runner(override("nexora.run.max-active-runs", "many")).run(context -> assertThat(context).hasFailed());
        runner(override("nexora.run.max-duration", "three minutes")).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anAbsentRunSectionStopsStartup() {
        Map<String, String> noRun = new LinkedHashMap<>();
        VALID.keySet().stream().filter(key -> key.startsWith("nexora.run.")).forEach(key -> noRun.put(key, null));

        runner(noRun).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void credentialsInTheServiceUrlAreNeverPrintedByAStartupFailure() {
        runner(override("nexora.ai-service.url", "http://user:pa55word@127.0.0.1:8000")).run(context -> {
            assertThat(context).hasFailed();
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain("pa55word");
            }
        });
    }

    @Test
    void theSecretIsNeverPrintedByToStringOrByAStartupFailure() {
        runner(Map.of()).run(context -> {
            NexoraProperties properties = context.getBean(NexoraProperties.class);
            assertThat(properties.toString()).doesNotContain(SECRET);
            assertThat(properties.internal().toString()).doesNotContain(SECRET);
        });

        runner(override("nexora.run.max-active-runs", "0")).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(SECRET);
            }
        });
    }
}
