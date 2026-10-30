package dev.thedusk.minecraftserver.boss;

import dev.thedusk.minecraftserver.encounter.EncounterDefinitionRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public final class BossDefinitionRegistry {
    public static final String DEFAULT_INDEX_RESOURCE = "bosses/index.txt";

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id",
            "encounter-id",
            "entity-type",
            "display-name",
            "join-radius",
            "arena-radius",
            "slam-interval-ticks",
            "slam-windup-ticks",
            "slam-radius",
            "slam-damage",
            "ring-interval-ticks",
            "ring-windup-ticks",
            "ring-safe-radius",
            "ring-outer-radius",
            "ring-damage"
    );

    private final Map<String, BossDefinition> definitions;

    private BossDefinitionRegistry(Map<String, BossDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public static BossDefinitionRegistry load(
            ClassLoader classLoader,
            EncounterDefinitionRegistry encounterDefinitions
    ) {
        Objects.requireNonNull(encounterDefinitions, "encounterDefinitions");
        BossDefinitionRegistry registry = load(classLoader, DEFAULT_INDEX_RESOURCE);
        for (BossDefinition definition : registry.definitions()) {
            encounterDefinitions.require(definition.encounterId());
        }
        return registry;
    }

    static BossDefinitionRegistry load(ClassLoader classLoader, String indexResource) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(indexResource, "indexResource");

        List<String> resources = readIndex(classLoader, indexResource);
        if (resources.isEmpty()) {
            throw new IllegalStateException("Boss definition index is empty: " + indexResource);
        }

        Map<String, BossDefinition> definitions = new LinkedHashMap<>();
        for (String resource : resources) {
            BossDefinition definition = loadDefinition(classLoader, resource);
            BossDefinition previous = definitions.putIfAbsent(definition.id(), definition);
            if (previous != null) {
                throw new IllegalStateException("Duplicate boss definition id '" + definition.id() + "'");
            }
        }
        return new BossDefinitionRegistry(definitions);
    }

    public int size() {
        return definitions.size();
    }

    public List<BossDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public BossDefinition require(String bossId) {
        Objects.requireNonNull(bossId, "bossId");
        BossDefinition definition = definitions.get(bossId.trim().toLowerCase(Locale.ROOT));
        if (definition == null) {
            throw new IllegalArgumentException("Unknown boss definition: " + bossId);
        }
        return definition;
    }

    private static List<String> readIndex(ClassLoader classLoader, String indexResource) {
        try (InputStream stream = classLoader.getResourceAsStream(indexResource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing boss definition index: " + indexResource);
            }
            List<String> resources = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String normalized = line.trim();
                    if (normalized.isEmpty() || normalized.startsWith("#")) {
                        continue;
                    }
                    if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,95}\\.properties")) {
                        throw new IllegalStateException("Invalid boss definition resource name in index: " + normalized);
                    }
                    resources.add("bosses/" + normalized);
                }
            }
            return List.copyOf(resources);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read boss definition index", exception);
        }
    }

    private static BossDefinition loadDefinition(ClassLoader classLoader, String resource) {
        Properties properties = new Properties();
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing boss definition resource: " + resource);
            }
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read boss definition: " + resource, exception);
        }

        for (String key : properties.stringPropertyNames()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalStateException("Unknown boss definition key '" + key + "' in " + resource);
            }
        }
        for (String key : ALLOWED_KEYS) {
            if (properties.getProperty(key) == null || properties.getProperty(key).isBlank()) {
                throw new IllegalStateException("Missing boss definition key '" + key + "' in " + resource);
            }
        }

        try {
            return new BossDefinition(
                    properties.getProperty("id"),
                    properties.getProperty("encounter-id"),
                    properties.getProperty("entity-type"),
                    properties.getProperty("display-name"),
                    Double.parseDouble(properties.getProperty("join-radius")),
                    Double.parseDouble(properties.getProperty("arena-radius")),
                    Integer.parseInt(properties.getProperty("slam-interval-ticks")),
                    Integer.parseInt(properties.getProperty("slam-windup-ticks")),
                    Double.parseDouble(properties.getProperty("slam-radius")),
                    Double.parseDouble(properties.getProperty("slam-damage")),
                    Integer.parseInt(properties.getProperty("ring-interval-ticks")),
                    Integer.parseInt(properties.getProperty("ring-windup-ticks")),
                    Double.parseDouble(properties.getProperty("ring-safe-radius")),
                    Double.parseDouble(properties.getProperty("ring-outer-radius")),
                    Double.parseDouble(properties.getProperty("ring-damage"))
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Boss definition contains a non-numeric field: " + resource, exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid boss definition in " + resource + ": " + exception.getMessage(),
                    exception
            );
        }
    }
}
