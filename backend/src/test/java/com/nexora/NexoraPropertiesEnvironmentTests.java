package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// The names are the environment variables from application.yml; the Environment resolves them the same way
// whether they come from the operating system or from here.
@SpringBootTest(properties = {
    "NEXORA_AI_SERVICE_URL=http://ai.internal:9000",
    "NEXORA_AI_SERVICE_CONNECT_TIMEOUT=PT2S",
    "NEXORA_RUN_MAX_DURATION=PT5M",
    "NEXORA_HEARTBEAT_INTERVAL=PT10S",
    "NEXORA_UPSTREAM_HEARTBEAT_TIMEOUT=PT90S",
    "NEXORA_TERMINAL_RETENTION=PT2H",
    "NEXORA_MAX_ACTIVE_RUNS=3"
})
class NexoraPropertiesEnvironmentTests {

    @Autowired
    private NexoraProperties properties;

    @Test
    void everyRunSettingCanBeOverriddenThroughItsEnvironmentVariable() {
        assertThat(properties.aiService().url()).isEqualTo(URI.create("http://ai.internal:9000"));
        assertThat(properties.aiService().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.run().maxDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.run().heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.run().upstreamHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(properties.run().terminalRetention()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.run().maxActiveRuns()).isEqualTo(3);
    }
}
