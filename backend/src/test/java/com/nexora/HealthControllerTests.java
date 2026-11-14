package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthReturnsOkWithoutCredentials() {
        ResponseEntity<String> response = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/health")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}");
    }
}
