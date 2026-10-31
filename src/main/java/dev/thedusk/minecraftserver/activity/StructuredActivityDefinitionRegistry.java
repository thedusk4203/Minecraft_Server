package dev.thedusk.minecraftserver.activity;

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

public final class StructuredActivityDefinitionRegistry {
    public static final String DEFAULT_INDEX_RESOURCE = "activities/index.txt";

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id",
            "version",
            "encounter-id",
            "encounter-version",
            "min-players",
            "max-players",
            "outer-seal-count",
            "inner-guard-melee-count",
            "inner-guard-ranged-count",
            "arena-radius"
    );

    private final Map<String, StructuredActivityDefinition> definitions;

    private StructuredActivityDefinitionRegistry(
            Map<String, StructuredActivityDefinition> definitions
    ) {
        this.definitions = Map.copyOf(definitions);
    }

    public static StructuredActivityDefinitionRegistry load(ClassLoader classLoader) {
        return load(classLoader, DEFAULT_INDEX_RESOURCE);
    }

    static StructuredActivityDefinitionRegistry load(
            ClassLoader classLoader,
            String indexResource
    ) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(indexResource, "indexResource");

        List<String> resources = readIndex(classLoader, indexResource);
        if (resources.isEmpty()) {
            throw new IllegalStateException(
                    "Structured activity definition index is empty: " + indexResource
            );
        }

        Map<String, StructuredActivityDefinition> definitions = new LinkedHashMap<>();
        for (String resource : resources) {
            StructuredActivityDefinition definition = loadDefinition(classLoader, resource);
            StructuredActivityDefinition previous = definitions.putIfAbsent(
                    definition.id(),
                    definition
            );
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate structured activity definition id '" + definition.id() + "'"
                );
            }
        }
        return new StructuredActivityDefinitionRegistry(definitions);
    }

    public int size() {
        return definitions.size();
    }

    public List<StructuredActivityDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public StructuredActivityDefinition require(String activityId) {
        Objects.requireNonNull(activityId, "activityId");
        StructuredActivityDefinition definition = definitions.get(
                activityId.trim().toLowerCase(java.util.Locale.ROOT)
        );
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Unknown structured activity definition: " + activityId
            );
        }
        return definition;
    }

    private static List<String> readIndex(ClassLoader classLoader, String indexResource) {
        try (InputStream stream = classLoader.getResourceAsStream(indexResource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing structured activity definition index: " + indexResource
                );
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
                                "Invalid structured activity resource name in index: " + normalized
                        );
                    }
                    resources.add("activities/" + normalized);
                }
            }
            return List.copyOf(resources);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read structured activity definition index",
                    exception
            );
        }
    }

    private static StructuredActivityDefinition loadDefinition(
            ClassLoader classLoader,
            String resource
    ) {
        Properties properties = new Properties();
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing structured activity definition resource: " + resource
                );
            }
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read structured activity definition: " + resource,
                    exception
            );
        }

        for (String key : properties.stringPropertyNames()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalStateException(
                        "Unknown structured activity definition key '" + key + "' in " + resource
                );
            }
        }
        for (String key : ALLOWED_KEYS) {
            if (properties.getProperty(key) == null || properties.getProperty(key).isBlank()) {
                throw new IllegalStateException(
                        "Missing structured activity definition key '" + key + "' in " + resource
                );
            }
        }

        try {
            return new StructuredActivityDefinition(
                    properties.getProperty("id"),
                    Integer.parseInt(properties.getProperty("version")),
                    properties.getProperty("encounter-id"),
                    Integer.parseInt(properties.getProperty("encounter-version")),
                    Integer.parseInt(properties.getProperty("min-players")),
                    Integer.parseInt(properties.getProperty("max-players")),
                    Integer.parseInt(properties.getProperty("outer-seal-count")),
                    Integer.parseInt(properties.getProperty("inner-guard-melee-count")),
                    Integer.parseInt(properties.getProperty("inner-guard-ranged-count")),
                    Integer.parseInt(properties.getProperty("arena-radius"))
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Structured activity definition contains a non-integer numeric field: "
                            + resource,
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid structured activity definition in " + resource + ": "
                            + exception.getMessage(),
                    exception
            );
        }
    }
}
