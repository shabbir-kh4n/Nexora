package com.nexora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class RunIdsTests {

    private static final String ID = "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11";

    @ParameterizedTest
    @ValueSource(strings = {ID, "00000000-0000-0000-0000-000000000000", "882ff11c-ca92-4159-9668-0c4097ffc3d6"})
    void canonicalLowerCaseUuidsAreAccepted(String value) {
        assertThat(RunIds.isValid(value)).isTrue();
        assertThat(RunIds.require(value)).isEqualTo(value);
    }

    @Test
    void everyUuidJavaGeneratesIsAccepted() {
        assertThat(IntStream.range(0, 2000).mapToObj(i -> UUID.randomUUID().toString()))
                .allMatch(RunIds::isValid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "3F2B8C1E-5D4A-4C7E-9B1A-2E6F0A8D7C11",
        "3F2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "{3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11}",
        "3f2b8c1e5d4a4c7e9b1a2e6f0a8d7c11",
        "urn:uuid:3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        " 3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11 ",
        "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11\n",
        "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11\r\n",
        "-3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c1",
        "3f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c110",
        "3f2b8c1g-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "3f2b8c1e--5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "3f2b8c1e5-d4a-4c7e-9b1a-2e6f0a8d7c11",
        "٣f2b8c1e-5d4a-4c7e-9b1a-2e6f0a8d7c11",
        "1-1-1-1-1",
        "",
        "not-a-uuid"
    })
    void everythingElseIsRejected(String value) {
        assertThat(RunIds.isValid(value)).isFalse();
        assertThatThrownBy(() -> RunIds.require(value))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                });
    }

    @Test
    void nullIsRejected() {
        assertThat(RunIds.isValid(null)).isFalse();
        assertThatThrownBy(() -> RunIds.require(null)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "internal"})
    void theExpressionEqualsTheOpenApiRunIdSchema(String document) {
        assertThat(OpenApiFiles.schema(document, "RunId").get("pattern")).isEqualTo(RunIds.PATTERN);
    }
}
