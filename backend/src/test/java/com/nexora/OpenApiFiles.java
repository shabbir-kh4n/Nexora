package com.nexora;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

// The pom copies docs/openapi onto the test classpath under /openapi.
final class OpenApiFiles {

    private OpenApiFiles() {
    }

    private static InputStream open(String path) {
        InputStream stream = OpenApiFiles.class.getResourceAsStream("/openapi/" + path);
        if (stream == null) {
            throw new IllegalStateException("Missing contract file on the test classpath: " + path);
        }
        return stream;
    }

    static String fixture(String name) {
        try (InputStream stream = open("fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixture " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(String document, String schemaName) {
        try (InputStream stream = open(document + ".yaml")) {
            Map<String, Object> spec = new Yaml().load(stream);
            Map<String, Object> components = (Map<String, Object>) spec.get("components");
            return (Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get(schemaName);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + document, e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> errorCodes(String document) {
        return (List<String>) schema(document, "ErrorCode").get("enum");
    }
}
