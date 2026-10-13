package io.floci.cli.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code skills/floci/} is the agent skill published from this repo. It quotes floci commands,
 * and an agent runs what it reads, so a flag that was renamed or never existed is a bug there.
 * This test parses every {@code floci ...} command line in the skill and checks its subcommands
 * and options against the real command tree.
 *
 * <p>Only code is read: fenced blocks and inline code spans in Markdown, backtick spans in the
 * Compose templates, and the command lines of the helper scripts.
 */
class SkillDocsTest {

    private static final Path SKILL = Path.of("skills", "floci");
    private static final List<String> TREES = List.of("aws", "gcp", "az", "oci");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#\\s]+)(?:#[^)]*)?\\)");
    private static final Pattern BUNDLED_PATH = Pattern.compile("`((?:references|scripts|assets)/[\\w./-]+)`");

    @TempDir
    Path tempDir;

    @Test
    void everyFlociCommandInTheSkillExists() throws IOException {
        CommandLine root = FlociCli.buildCommandLine(new ProfileStore(tempDir));
        List<String> problems = new ArrayList<>();
        for (Path file : skillFiles()) {
            for (String code : codeIn(file)) {
                String[] tokens = code.trim().split("\\s+");
                for (int i = 0; i < tokens.length; i++) {
                    // 'docker run --name floci' names a container; it does not run the CLI.
                    boolean optionValue = i > 0 && tokens[i - 1].startsWith("-");
                    if (!optionValue && "floci".equals(tokens[i].replaceFirst("^[\"'(`$]+", ""))) {
                        check(root, tokens, i + 1, SKILL.relativize(file) + ": " + code.trim(), problems);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "The skill names commands or flags the CLI does not have:\n  " + String.join("\n  ", problems));
    }

    /** Walks one command line from {@code from}, branching where the text offers alternatives. */
    private static void check(CommandLine command, String[] tokens, int from, String where, List<String> problems) {
        boolean commandPhase = true;
        for (int i = from; i < tokens.length; i++) {
            String raw = tokens[i];
            // The rest of the shell line. '<tree>' is a placeholder, '<<EOF' and '< file' are not.
            if (raw.matches("^[|&;>#)].*") || raw.matches("^<+([^a-z].*)?")) return;
            String token = raw.replaceFirst("^[\"']+", "");
            String stripped = token.replaceFirst("[\"'),;.:]+$", "");
            boolean last = token.substring(stripped.length()).matches(".*[),;.:].*");
            token = stripped;
            if (token.isEmpty()) return;

            if (commandPhase && command.getParent() == null && (token.equals("<tree>") || token.equals("$tree"))) {
                for (String tree : TREES) {
                    check(command.getSubcommands().get(tree), tokens, i + 1, where, problems);
                }
                return;
            }
            List<String> alternatives = List.of(token.replaceAll("^<|>$", "").split("\\|"));
            if (commandPhase && alternatives.size() > 1
                    && command.getSubcommands().keySet().containsAll(alternatives)) {
                if (last) return;
                for (String alternative : alternatives) {
                    check(command.getSubcommands().get(alternative), tokens, i + 1, where, problems);
                }
                return;
            }

            if (token.startsWith("-") && token.length() > 1) {
                String name = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
                if (!name.matches("--?[A-Za-z][A-Za-z-]*")) return; // a value or a shell construct, not a flag
                OptionSpec option = findOption(command.getCommandSpec(), name);
                if (option == null) {
                    problems.add(where + "  ->  '" + command.getCommandSpec().qualifiedName() + "' has no " + name);
                    return;
                }
                if (option.arity().max() > 0 && !token.contains("=")) i++;
            } else if (commandPhase && command.getSubcommands().containsKey(token)) {
                command = command.getSubcommands().get(token);
            } else if (commandPhase && token.matches("[a-z][a-z-]*") && !command.getSubcommands().isEmpty()
                    && command.getCommandSpec().positionalParameters().isEmpty()) {
                problems.add(where + "  ->  '" + command.getCommandSpec().qualifiedName()
                        + "' has no subcommand " + token);
                return;
            } else {
                commandPhase = false;
            }
            if (last) return;
        }
    }

    private static OptionSpec findOption(CommandSpec spec, String name) {
        OptionSpec option = spec.findOption(name);
        if (option != null || !name.matches("-[A-Za-z]{2,}")) return option;
        // A cluster of short flags, as in -hq: every letter has to be one.
        for (char flag : name.substring(1).toCharArray()) {
            option = spec.findOption("-" + flag);
            if (option == null) return null;
        }
        return option;
    }

    @Test
    void everyLinkAndBundledPathInTheSkillResolves() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path file : skillFiles()) {
            if (!file.toString().endsWith(".md")) continue;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher link = LINK.matcher(text);
            while (link.find()) {
                String target = link.group(1);
                if (target.contains("://")) continue;
                if (!Files.exists(file.resolveSibling(target))) {
                    broken.add(SKILL.relativize(file) + " links to " + target);
                }
            }
            // Paths named in prose are relative to the skill directory itself.
            Matcher bundled = BUNDLED_PATH.matcher(text);
            while (bundled.find()) {
                if (!Files.exists(SKILL.resolve(bundled.group(1)))) {
                    broken.add(SKILL.relativize(file) + " names " + bundled.group(1));
                }
            }
        }
        assertTrue(broken.isEmpty(), "Broken references in the skill:\n  " + String.join("\n  ", broken));
    }

    @Test
    void theFrontmatterFollowsTheAgentSkillsSpec() throws IOException {
        List<String> lines = Files.readAllLines(SKILL.resolve("SKILL.md"), StandardCharsets.UTF_8);
        assertEquals("---", lines.get(0), "SKILL.md starts with YAML frontmatter");
        List<String> frontmatter = lines.subList(1, lines.subList(1, lines.size()).indexOf("---") + 1);

        assertEquals(List.of("name", "description"),
                frontmatter.stream().map(line -> line.substring(0, line.indexOf(':'))).toList(),
                "only the two fields every agent understands");
        assertEquals("name: " + SKILL.getFileName(), frontmatter.get(0), "the name is the directory name");
        int length = frontmatter.get(1).length() - "description: ".length();
        assertTrue(length <= 1024, "description is " + length + " characters; the spec allows 1024");
    }

    /** {@code plugin.json} (Kiro powers) and the Claude Code marketplace both publish this directory. */
    @Test
    void thePluginManifestsPointAtTheSkill() throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        // Agent Plugins: the manifest sits next to skills/, and each skills/<name>/SKILL.md is a skill.
        JsonNode plugin = mapper.readTree(Path.of("plugin.json").toFile());
        assertEquals("https://agent-plugins.org/schemas/1.0.0/plugin.schema.json", plugin.path("$schema").asText());
        assertTrue(plugin.path("name").asText().matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"), "kebab-case name");
        assertTrue(plugin.path("version").asText().matches("\\d+\\.\\d+\\.\\d+"), "semantic version");
        assertFalse(plugin.path("author").path("name").asText().isBlank(), "author.name");
        assertTrue(plugin.path("keywords").size() > 0, "keywords are what activate the power");
        assertEquals(Path.of("skills"), SKILL.getParent(), "skills/ is a sibling of plugin.json");

        JsonNode marketplace = mapper.readTree(Path.of(".claude-plugin", "marketplace.json").toFile());
        for (JsonNode entry : marketplace.path("plugins")) {
            for (JsonNode skill : entry.path("skills")) {
                assertTrue(Files.isRegularFile(Path.of(skill.asText()).resolve("SKILL.md")),
                        "marketplace.json lists " + skill.asText());
            }
        }
    }

    @Test
    void theSkillHasNoEmOrEnDashes() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : skillFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).matches(".*[–—].*")) found.add(SKILL.relativize(file) + ":" + (i + 1));
            }
        }
        assertTrue(found.isEmpty(), "Use a colon, comma or parentheses instead of a dash at: " + found);
    }

    private static List<Path> skillFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SKILL)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** The pieces of {@code file} that are code, one string per line or span. */
    private static List<String> codeIn(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString();
        List<String> code = new ArrayList<>();
        if (name.endsWith(".sh")) {
            // Comments and messages are prose; the rest of a script is commands.
            lines.stream().map(String::trim)
                    .filter(line -> !line.startsWith("#") && !line.startsWith("echo "))
                    .forEach(code::add);
            return code;
        }
        boolean fenced = false;
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                fenced = !fenced;
            } else if (fenced && name.endsWith(".md")) {
                code.add(line);
            } else {
                Matcher span = CODE_SPAN.matcher(line);
                while (span.find()) code.add(span.group(1));
            }
        }
        return code;
    }
}
