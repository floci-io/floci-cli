package io.floci.cli.commands.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.util.Iterator;

/**
 * Reads a YAML document into a tree with its anchors, aliases and merge keys resolved, the way a
 * YAML consumer such as Compose sees it. Jackson's own {@code readTree} leaves them unresolved:
 * {@code volumes: *common} arrives as the string "common" and {@code <<: *base} as a field named
 * {@code <<}, so a service built from an anchor looks empty. Its parser also drops anchors on
 * scalars ({@code image: &img ...}), so they cannot be resolved on top of it either; SnakeYAML,
 * which Jackson's YAML module is built on, does all three.
 */
public final class YamlTree {

    private static final ObjectMapper TREES = new ObjectMapper();

    private YamlTree() {}

    /**
     * The first document of {@code yaml}; a missing node for an empty one.
     *
     * @throws IOException when the text is not YAML, with a one-line reason
     */
    public static JsonNode read(String yaml) throws IOException {
        try {
            // SafeConstructor builds only maps, lists and scalars, never arbitrary classes, and the
            // default LoaderOptions bound how far aliases may expand.
            Iterator<Object> documents = new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(yaml).iterator();
            Object first = documents.hasNext() ? documents.next() : null;
            return first == null ? MissingNode.getInstance() : TREES.valueToTree(first);
        } catch (YAMLException | IllegalArgumentException e) {
            String reason = e.getMessage() == null ? "not valid YAML" : e.getMessage().lines().findFirst().orElse("not valid YAML");
            throw new IOException(reason);
        }
    }
}
