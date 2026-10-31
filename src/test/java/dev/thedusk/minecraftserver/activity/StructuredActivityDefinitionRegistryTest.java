package dev.thedusk.minecraftserver.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredActivityDefinitionRegistryTest {
    @Test
    void productionRegistryLoadsStoneVault() {
        StructuredActivityDefinitionRegistry registry =
                StructuredActivityDefinitionRegistry.load(
                        StructuredActivityDefinitionRegistryTest.class.getClassLoader()
                );

        assertEquals(1, registry.size());

        StructuredActivityDefinition definition = registry.require("STONE_VAULT");
        assertEquals("stone_vault", definition.id());
        assertEquals(1, definition.version());
        assertEquals("stone_warden", definition.encounterId());
        assertEquals(1, definition.encounterVersion());
        assertEquals(1, definition.minPlayers());
        assertEquals(4, definition.maxPlayers());
        assertEquals(4, definition.outerSealCount());
        assertEquals(2, definition.innerGuardMeleeCount());
        assertEquals(2, definition.innerGuardRangedCount());
        assertEquals(4, definition.innerGuardCount());
        assertEquals(18, definition.arenaRadius());
        assertTrue(definition.definitionSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void canonicalDefinitionHashIsStableAfterIdNormalization() {
        StructuredActivityDefinition first = definition("Stone_Vault", 4);
        StructuredActivityDefinition second = definition("stone_vault", 4);

        assertEquals(first, second);
        assertEquals(first.definitionSha256(), second.definitionSha256());
    }

    @Test
    void semanticChangeChangesFingerprint() {
        StructuredActivityDefinition first = definition("stone_vault", 4);
        StructuredActivityDefinition second = definition("stone_vault", 5);

        assertTrue(!first.definitionSha256().equals(second.definitionSha256()));
    }

    @Test
    void invalidDefinitionFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredActivityDefinition(
                        "bad id", 1, "stone_warden", 1, 1, 4, 4, 2, 2, 18
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredActivityDefinition(
                        "stone_vault", 0, "stone_warden", 1, 1, 4, 4, 2, 2, 18
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredActivityDefinition(
                        "stone_vault", 1, "stone_warden", 1, 4, 2, 4, 2, 2, 18
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredActivityDefinition(
                        "stone_vault", 1, "stone_warden", 1, 1, 4, 0, 2, 2, 18
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredActivityDefinition(
                        "stone_vault", 1, "stone_warden", 1, 1, 4, 4, 2, 2, 4
                )
        );
    }

    @Test
    void unknownDefinitionKeyFailsRegistryLoad() {
        ClassLoader loader = new ResourceClassLoader(Map.of(
                "activities/index.txt",
                "bad.properties\n",
                "activities/bad.properties",
                validProperties("bad") + "loot=diamond\n"
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> StructuredActivityDefinitionRegistry.load(
                        loader,
                        StructuredActivityDefinitionRegistry.DEFAULT_INDEX_RESOURCE
                )
        );
        assertTrue(exception.getMessage().contains(
                "Unknown structured activity definition key"
        ));
    }

    @Test
    void duplicateDefinitionIdFailsRegistryLoad() {
        ClassLoader loader = new ResourceClassLoader(Map.of(
                "activities/index.txt",
                "one.properties\ntwo.properties\n",
                "activities/one.properties",
                validProperties("same"),
                "activities/two.properties",
                validProperties("same").replace("version=1", "version=2")
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> StructuredActivityDefinitionRegistry.load(
                        loader,
                        StructuredActivityDefinitionRegistry.DEFAULT_INDEX_RESOURCE
                )
        );
        assertTrue(exception.getMessage().contains(
                "Duplicate structured activity definition id"
        ));
    }

    private static StructuredActivityDefinition definition(String id, int outerSealCount) {
        return new StructuredActivityDefinition(
                id,
                1,
                "Stone_Warden",
                1,
                1,
                4,
                outerSealCount,
                2,
                2,
                18
        );
    }

    private static String validProperties(String id) {
        return "id=" + id + "\n"
                + "version=1\n"
                + "encounter-id=stone_warden\n"
                + "encounter-version=1\n"
                + "min-players=1\n"
                + "max-players=4\n"
                + "outer-seal-count=4\n"
                + "inner-guard-melee-count=2\n"
                + "inner-guard-ranged-count=2\n"
                + "arena-radius=18\n";
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
