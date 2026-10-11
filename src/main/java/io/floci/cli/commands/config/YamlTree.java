package io.floci.cli.commands.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a YAML document into a tree with its anchors, aliases and merge keys resolved, the way a
 * YAML consumer such as Compose sees it. Jackson's own {@code readTree} leaves them unresolved:
 * {@code volumes: *common} arrives as the string "common" and {@code <<: *base} as a field named
 * {@code <<}, so a service built from an anchor looks empty. Its parser also drops anchors on
 * scalars ({@code image: &img ...}), so they cannot be resolved on top of it either; SnakeYAML,
 * which Jackson's YAML module is built on, does all three.
 */
public final class YamlTree {

    /**
     * The most nodes a document may have once its aliases are expanded. An alias costs nothing in
     * the file and a whole copy in the tree, so a few nested aliases describe millions of nodes;
     * no Compose file comes near this, and stopping here keeps such a file from using up memory.
     */
    static final int MAX_NODES = 100_000;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private YamlTree() {}

    /**
     * Scalar typing as Compose reads it (YAML 1.2): only {@code true} and {@code false} are
     * booleans. SnakeYAML's default follows YAML 1.1, where {@code yes}, {@code no}, {@code on}
     * and {@code off} are booleans too, which would rename a service called {@code on} to
     * {@code true}. Timestamps are left as text for the same reason: nothing here wants a date.
     */
    private static final class ComposeResolver extends Resolver {

        private static final Pattern TRUE_OR_FALSE = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, TRUE_OR_FALSE, "tTfF");
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }

    /**
     * The first document of {@code yaml}; a missing node for an empty one.
     *
     * @throws IOException when the text is not YAML, or expands to more than {@link #MAX_NODES}
     *                     nodes, with a one-line reason
     */
    public static JsonNode read(String yaml) throws IOException {
        Object first;
        try {
            // SafeConstructor builds only maps, lists and scalars, never arbitrary classes.
            LoaderOptions options = new LoaderOptions();
            DumperOptions unused = new DumperOptions();
            Yaml reader = new Yaml(new SafeConstructor(options), new Representer(unused), unused, options, new ComposeResolver());
            Iterator<Object> documents = reader.loadAll(yaml).iterator();
            first = documents.hasNext() ? documents.next() : null;
        } catch (YAMLException | IllegalArgumentException e) {
            String reason = e.getMessage() == null ? "not valid YAML" : e.getMessage().lines().findFirst().orElse("not valid YAML");
            throw new IOException(reason);
        }
        return first == null ? NODES.missingNode() : tree(first, new int[] {MAX_NODES});
    }

    // SnakeYAML hands back shared objects for aliases; the tree gets a copy at each use, counted
    // against the budget so the copying cannot run away.
    private static JsonNode tree(Object value, int[] budget) throws IOException {
        if (--budget[0] < 0) {
            throw new IOException("the document has more than " + MAX_NODES + " values once its aliases are expanded");
        }
        if (value == null) return NODES.nullNode();
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.set(String.valueOf(entry.getKey()), tree(entry.getValue(), budget));
            }
            return object;
        }
        if (value instanceof Collection<?> items) {
            ArrayNode array = NODES.arrayNode();
            for (Object item : items) {
                array.add(tree(item, budget));
            }
            return array;
        }
        if (value instanceof Boolean flag) return NODES.booleanNode(flag);
        if (value instanceof Integer number) return NODES.numberNode(number);
        if (value instanceof Long number) return NODES.numberNode(number);
        if (value instanceof BigInteger number) return NODES.numberNode(number);
        if (value instanceof Double number) return NODES.numberNode(number);
        return NODES.textNode(String.valueOf(value));
    }
}
