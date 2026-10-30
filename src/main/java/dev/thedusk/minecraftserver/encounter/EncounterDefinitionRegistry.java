package dev.thedusk.minecraftserver.encounter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public final class EncounterDefinitionRegistry {
    public static final String DEFAULT_INDEX_RESOURCE = "encounters/index.txt";

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id",
            "version",
            "min-players",
            "max-players",
            "timeout-seconds"
    );

    private final Map<String, EncounterDefinition> definitions;

    private EncounterDefinitionRegistry(Map<String, EncounterDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public static EncounterDefinitionRegistry load(ClassLoader classLoader) {
        return load(classLoader, DEFAULT_INDEX_RESOURCE);
    }

    static EncounterDefinitionRegistry load(ClassLoader classLoader, String indexResource) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(indexResource, "indexResource");

        List<String> resources = readIndex(classLoader, indexResource);
        if (resources.isEmpty()) {
            throw new IllegalStateException("Encounter definition index is empty: " + indexResource);
        }

        Map<String, EncounterDefinition> definitions = new LinkedHashMap<>();
        for (String resource : resources) {
            EncounterDefinition definition = loadDefinition(classLoader, resource);
            EncounterDefinition previous = definitions.putIfAbsent(definition.id(), definition);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate encounter definition id '" + definition.id() + "'"
                );
            }
        }
        return new EncounterDefinitionRegistry(definitions);
    }

    public int size() {
        return definitions.size();
    }

    public List<EncounterDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public EncounterDefinition require(String encounterId) {
        Objects.requireNonNull(encounterId, "encounterId");
        EncounterDefinition definition = definitions.get(encounterId.trim().toLowerCase(java.util.Locale.ROOT));
        if (definition == null) {
            throw new IllegalArgumentException("Unknown encounter definition: " + encounterId);
        }
        return definition;
    }

    private static List<String> readIndex(ClassLoader classLoader, String indexResource) {
        try (InputStream stream = classLoader.getResourceAsStream(indexResource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing encounter definition index: " + indexResource);
            }
            List<String> resources = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String normalized = line.trim();
                    if (normalized.isEmpty() || normalized.startsWith("#")) {
                        continue;
                    }
                    if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,95}\\.properties")) {
                        throw new IllegalStateException(
                                "Invalid encounter definition resource name in index: " + normalized
                        );
                    }
                    resources.add("encounters/" + normalized);
                }
            }
            return List.copyOf(resources);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read encounter definition index", exception);
        }
    }

    private static EncounterDefinition loadDefinition(ClassLoader classLoader, String resource) {
        Properties properties = new Properties();
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing encounter definition resource: " + resource);
            }
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read encounter definition: " + resource, exception);
        }

        for (String key : properties.stringPropertyNames()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalStateException(
                        "Unknown encounter definition key '" + key + "' in " + resource
                );
            }
        }
        for (String key : ALLOWED_KEYS) {
            if (properties.getProperty(key) == null || properties.getProperty(key).isBlank()) {
                throw new IllegalStateException(
                        "Missing encounter definition key '" + key + "' in " + resource
                );
            }
        }

        try {
            return new EncounterDefinition(
                    properties.getProperty("id"),
                    Integer.parseInt(properties.getProperty("version")),
                    Integer.parseInt(properties.getProperty("min-players")),
                    Integer.parseInt(properties.getProperty("max-players")),
                    Integer.parseInt(properties.getProperty("timeout-seconds"))
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Encounter definition contains a non-integer numeric field: " + resource,
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid encounter definition in " + resource + ": " + exception.getMessage(),
                    exception
            );
        }
    }
}
