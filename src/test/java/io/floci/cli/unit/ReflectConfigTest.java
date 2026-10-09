package io.floci.cli.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.cli.config.GlobalConfigStore;
import io.floci.cli.config.Profile;
import io.floci.cli.update.UpdateCache;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JVM has full reflection, so a type Jackson binds by field name works in every unit test and
 * fails only in the native binary when it is missing here (BL-025: the update cache was written
 * empty). Every such type must stay registered.
 */
class ReflectConfigTest {

    private static final List<Class<?>> JACKSON_BOUND = List.of(
            Profile.class, GlobalConfigStore.GlobalConfig.class, UpdateCache.class);

    // Jackson reads the fields, calls the accessors and the constructor of each bound type.
    private static final List<String> REQUIRED_FLAGS =
            List.of("allDeclaredFields", "allDeclaredMethods", "allDeclaredConstructors");

    @Test
    void everyJacksonBoundTypeIsRegisteredForNativeReflection() throws Exception {
        Map<String, JsonNode> registered = new HashMap<>();
        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/native-image/io.floci/floci-cli/reflect-config.json")) {
            assertNotNull(in, "reflect-config.json is on the classpath");
            for (JsonNode entry : new ObjectMapper().readTree(in)) {
                registered.put(entry.path("name").asText(), entry);
            }
        }
        for (Class<?> type : JACKSON_BOUND) {
            JsonNode entry = registered.get(type.getName());
            assertNotNull(entry, type.getName() + " is missing from reflect-config.json");
            for (String flag : REQUIRED_FLAGS) {
                assertTrue(entry.path(flag).asBoolean(false), type.getName() + " needs \"" + flag + "\": true");
            }
        }
    }
}
