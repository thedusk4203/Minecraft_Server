package dev.thedusk.minecraftserver.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EncounterDefinitionRegistryTest {
    @Test
    void productionRegistryLoadsSyntheticProbe() {
        EncounterDefinitionRegistry registry = EncounterDefinitionRegistry.load(
                EncounterDefinitionRegistryTest.class.getClassLoader()
        );

        assertEquals(1, registry.size());
        EncounterDefinition definition = registry.require("FRAMEWORK_PROBE");
        assertEquals("framework_probe", definition.id());
        assertEquals(1, definition.version());
        assertEquals(1, definition.minPlayers());
        assertEquals(4, definition.maxPlayers());
        assertEquals(300, definition.timeoutSeconds());
        assertTrue(definition.definitionSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void canonicalDefinitionHashIsStableAfterIdNormalization() {
        EncounterDefinition first = new EncounterDefinition(
                "Framework_Probe",
                1,
                1,
                4,
                300
        );
        EncounterDefinition second = new EncounterDefinition(
                "framework_probe",
                1,
                1,
                4,
                300
        );

        assertEquals(first, second);
        assertEquals(first.definitionSha256(), second.definitionSha256());
    }

    @Test
    void invalidDefinitionFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncounterDefinition("bad id", 1, 1, 4, 300)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncounterDefinition("probe", 0, 1, 4, 300)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncounterDefinition("probe", 1, 4, 2, 300)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncounterDefinition("probe", 1, 1, 4, 5)
        );
    }

    @Test
    void unknownDefinitionKeyFailsRegistryLoad() {
        ClassLoader loader = new ResourceClassLoader(Map.of(
                "encounters/index.txt",
                "bad.properties\n",
                "encounters/bad.properties",
                "id=bad\nversion=1\nmin-players=1\nmax-players=1\ntimeout-seconds=60\nreward=diamond\n"
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EncounterDefinitionRegistry.load(
                        loader,
                        EncounterDefinitionRegistry.DEFAULT_INDEX_RESOURCE
                )
        );
        assertTrue(exception.getMessage().contains("Unknown encounter definition key"));
    }

    @Test
    void duplicateDefinitionIdFailsRegistryLoad() {
        ClassLoader loader = new ResourceClassLoader(Map.of(
                "encounters/index.txt",
                "one.properties\ntwo.properties\n",
                "encounters/one.properties",
                "id=same\nversion=1\nmin-players=1\nmax-players=1\ntimeout-seconds=60\n",
                "encounters/two.properties",
                "id=same\nversion=2\nmin-players=1\nmax-players=2\ntimeout-seconds=90\n"
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EncounterDefinitionRegistry.load(
                        loader,
                        EncounterDefinitionRegistry.DEFAULT_INDEX_RESOURCE
                )
        );
        assertTrue(exception.getMessage().contains("Duplicate encounter definition id"));
    }

    private static final class ResourceClassLoader extends ClassLoader {
        private final Map<String, byte[]> resources;

        private ResourceClassLoader(Map<String, String> resources) {
            super(null);
            this.resources = resources.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().getBytes(StandardCharsets.UTF_8)
                    )
            );
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] content = resources.get(name);
            return content == null ? null : new ByteArrayInputStream(content);
        }
    }
}
