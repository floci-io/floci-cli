package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * BL-069: {@code docs/help/} holds the {@code --help} text of every command in every product
 * tree, and this test fails when the committed files and the code drift apart. A change to a
 * command, a flag or a description therefore shows up in review as a change to these files.
 *
 * <p>To regenerate them after an intended change:
 * {@code mvn test -Dtest=HelpSnapshotTest -Dfloci.help.update=true}
 */
class HelpSnapshotTest {

    private static final Path DIR = Path.of("docs", "help");
    private static final String UPDATE = "floci.help.update";
    private static final String HOW_TO_UPDATE =
            "Run 'mvn test -Dtest=HelpSnapshotTest -D" + UPDATE + "=true' and commit docs/help.";

    @TempDir
    Path tempDir;

    /** File name to help text, for the root command and everything under it. */
    private Map<String, String> generate() {
        CommandLine root = FlociCli.buildCommandLine(new ProfileStore(tempDir));
        // Fixed, so the files do not depend on the terminal the test happens to run in.
        root.setUsageHelpWidth(100);
        Map<String, String> pages = new TreeMap<>();
        collect(root, pages);
        return pages;
    }

    private static void collect(CommandLine command, Map<String, String> pages) {
        String name = command.getCommandSpec().qualifiedName("-") + ".txt";
        String text = command.getUsageMessage(CommandLine.Help.Ansi.OFF).replace("\r\n", "\n");
        pages.put(name, text);
        // 'help' is picocli's own subcommand: the same text in every group, and not ours to pin.
        command.getSubcommands().forEach((subName, sub) -> {
            if (!"help".equals(subName)) collect(sub, pages);
        });
    }

    @Test
    void theCommittedHelpTextMatchesTheCommands() throws IOException {
        // The defaults shown in help come from FLOCI_* variables when they are set.
        assumeTrue(System.getenv().keySet().stream().noneMatch(key -> key.startsWith("FLOCI_")),
                "FLOCI_* variables change the defaults that help prints");
        Map<String, String> pages = generate();

        if (Boolean.getBoolean(UPDATE)) {
            write(pages);
            return;
        }

        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            Path file = DIR.resolve(page.getKey());
            if (!Files.isRegularFile(file)) {
                drift.add("missing: " + file);
            } else if (!Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(page.getValue())) {
                drift.add("out of date: " + file);
            }
        }
        for (String name : committed()) {
            if (!pages.containsKey(name)) drift.add("no such command any more: " + DIR.resolve(name));
        }
        assertEquals(List.of(), drift, "docs/help does not match the commands. " + HOW_TO_UPDATE);
    }

    /** Every tree is there: a product group that lost a command would otherwise go unnoticed. */
    @Test
    void everyProductTreeHasItsPages() {
        Map<String, String> pages = generate();

        for (String page : List.of("floci.txt", "floci-start.txt", "floci-aws-start.txt", "floci-gcp-start.txt",
                "floci-az-start.txt", "floci-oci-start.txt", "floci-config-profile.txt", "floci-update.txt")) {
            assertTrue(pages.containsKey(page), page + " in " + pages.keySet());
        }
        assertTrue(pages.get("floci-gcp-start.txt").startsWith("Usage: floci gcp start"), pages.get("floci-gcp-start.txt"));
        assertTrue(pages.keySet().stream().noneMatch(name -> name.endsWith("-help.txt")));
    }

    private static void write(Map<String, String> pages) throws IOException {
        Files.createDirectories(DIR);
        for (String stale : committed()) {
            if (!pages.containsKey(stale)) Files.delete(DIR.resolve(stale));
        }
        for (Map.Entry<String, String> page : pages.entrySet()) {
            Files.writeString(DIR.resolve(page.getKey()), page.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> committed() throws IOException {
        if (!Files.isDirectory(DIR)) return List.of();
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).sorted().toList();
        }
    }
}
