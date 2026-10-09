package io.floci.cli.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.cli.config.GlobalConfigStore;
import io.floci.cli.config.Profile;
import io.floci.cli.update.UpdateCache;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JVM has full reflection, so a type Jackson binds by field name works in every unit test and
 * fails only in the native binary when it is missing here (BL-025: the update cache was written
 * empty). Every such type must stay registered.
 */
class ReflectConfigTest {

    private static final List<Class<?>> JACKSON_BOUND = List.of(
            Profile.class, GlobalConfigStore.GlobalConfig.class, UpdateCache.class);

    @Test
    void everyJacksonBoundTypeIsRegisteredForNativeReflection() throws Exception {
        Set<String> registered = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/native-image/io.floci/floci-cli/reflect-config.json")) {
            assertNotNull(in, "reflect-config.json is on the classpath");
            for (JsonNode entry : new ObjectMapper().readTree(in)) {
                registered.add(entry.path("name").asText());
            }
        }
        for (Class<?> type : JACKSON_BOUND) {
            assertTrue(registered.contains(type.getName()), type.getName() + " is missing from reflect-config.json");
        }
    }
}
