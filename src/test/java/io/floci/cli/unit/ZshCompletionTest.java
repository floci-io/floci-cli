package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.commands.CompletionCommand;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.AutoComplete;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** #82: sourcing the zsh script sets up floci's completion and changes nothing else in the shell. */
class ZshCompletionTest {

    @TempDir
    Path tempDir;

    private String script(String shell) {
        PrintStream out = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            assertEquals(0, FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute("completion", shell));
        } finally {
            System.setOut(out);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private String picocliScript() {
        return AutoComplete.bash("floci", FlociCli.buildCommandLine(new ProfileStore(tempDir)));
    }

    /**
     * Guards the filter itself: if a picocli upgrade rewords these lines, the removal silently stops
     * matching. This fails first, so the change is looked at instead of shipped.
     */
    @Test
    void picocliStillEmitsTheLinesThatAreRemoved() {
        List<String> lines = picocliScript().lines().toList();

        for (String change : CompletionCommand.ZSH_SHELL_CHANGES) {
            assertTrue(lines.contains(change), "picocli no longer emits '" + change.strip() + "'");
        }
    }

    @Test
    void theZshScriptIsPicocliWithoutThoseLines() {
        String zsh = script("zsh");

        for (String change : CompletionCommand.ZSH_SHELL_CHANGES) {
            assertFalse(zsh.lines().anyMatch(change::equals), change);
        }
        List<String> expected = picocliScript().lines()
                .filter(line -> !CompletionCommand.ZSH_SHELL_CHANGES.contains(line)).toList();
        assertEquals(expected, zsh.lines().toList());
        assertTrue(zsh.contains("autoload -U +X bashcompinit && bashcompinit"), "zsh still loads bash completion");
        assertTrue(zsh.endsWith("\n") == picocliScript().endsWith("\n"), "the final newline is kept as picocli wrote it");
    }

    @Test
    void theBashScriptIsUnchanged() {
        assertEquals(picocliScript(), script("bash"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsetopt", "setopt"})
    @DisabledOnOs(OS.WINDOWS)
    void sourcingItLeavesTheShellAsItWas(String completeAliases) throws Exception {
        boolean zshInstalled = Files.isExecutable(Path.of("/bin/zsh")) || onPath("zsh");
        // CI installs zsh and sets this, so the test cannot skip there without anyone noticing.
        if (Boolean.parseBoolean(System.getenv("FLOCI_TEST_REQUIRE_ZSH"))) {
            assertTrue(zshInstalled, "FLOCI_TEST_REQUIRE_ZSH is set but zsh is not installed");
        }
        assumeTrue(zshInstalled, "zsh is not installed");
        Path completion = tempDir.resolve("floci.zsh");
        Files.writeString(completion, script("zsh"));
        String probe = String.join("\n",
                "autoload -U compinit && compinit -u -d " + tempDir.resolve("zcompdump"),
                completeAliases + " COMPLETE_ALIASES",
                "alias mr='mise run'",
                "before=$([[ -o completealiases ]] && echo on || echo off)",
                "source " + completion,
                "comps=${#_comps}",
                // A completion run under the old script registered '+o' and 'default' here.
                "COMP_WORDS=(floci start --pull ''); COMP_CWORD=3; _picocli_floci_start",
                "print -r -- \"before=$before\"",
                "print -r -- \"after=$([[ -o completealiases ]] && echo on || echo off)\"",
                "print -r -- \"compopt=$(alias compopt >/dev/null && echo alias || echo none)\"",
                "print -r -- \"mr=${aliases[mr]}\"",
                "print -r -- \"comps=$(( ${#_comps} - comps ))\"",
                "print -r -- \"floci=${_comps[floci]}\"",
                "print -r -- \"start=${#COMPREPLY}\"");

        Process zsh = new ProcessBuilder("zsh", "-f", "-c", probe).redirectErrorStream(true).start();
        assertTrue(zsh.waitFor(30, TimeUnit.SECONDS), "zsh did not finish");
        String out = new String(zsh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String> lines = out.lines().toList();

        String expected = completeAliases.equals("setopt") ? "on" : "off";
        assertTrue(lines.contains("before=" + expected), out);
        assertTrue(lines.contains("after=" + expected), out);
        assertTrue(lines.contains("compopt=none"), out);
        assertTrue(lines.contains("mr=mise run"), out);
        assertTrue(lines.contains("comps=0"), out);
        assertTrue(lines.contains("floci=_bash_complete -F _complete_floci -o default"), out);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("start=") && !l.equals("start=0")), out);
    }

    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, command))) return true;
        }
        return false;
    }
}
