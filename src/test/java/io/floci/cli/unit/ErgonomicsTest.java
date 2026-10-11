package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.FlociCli;
import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.ServerCompatibility;
import io.floci.cli.commands.config.ConfigProfileCommand;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Glyphs;
import io.floci.cli.output.OutputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** BL-059 to BL-062: --json, plain marks, config profile edit and the old-server warning. */
class ErgonomicsTest {

    @TempDir
    Path tempDir;

    private record Run(int exit, String out, String err) {}

    private static Run capture(IntSupplier body) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = body.getAsInt();
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private Run run(String... args) {
        return capture(() -> FlociCli.buildCommandLine(new ProfileStore(tempDir.resolve("profiles"))).execute(args));
    }

    // ---- --json ------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"'status --json', json", "'status -o yaml', yaml", "'status', text", "'status --json -o yaml', json",
            "'gcp doctor --json', json", "'oci services --json', json"})
    void jsonIsShorthandForOutputJson(String commandLine, OutputFormat expected) {
        ParseResult r = FlociCli.buildCommandLine(new ProfileStore(tempDir)).parseArgs(commandLine.split(" "));
        while (r.hasSubcommand()) r = r.subcommand();
        GlobalOptions global = (GlobalOptions) r.commandSpec().mixins().values().stream()
                .map(m -> m.userObject()).filter(GlobalOptions.class::isInstance).findFirst().orElseThrow();

        assertEquals(expected, global.format());
    }

    @Test
    void jsonReachesTheCommandsOutput() throws Exception {
        Files.createDirectories(tempDir.resolve("profiles"));
        Files.writeString(tempDir.resolve("profiles").resolve("one.yaml"), "port: 4599\n");

        Run r = run("config", "profile", "list", "--json");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().strip().startsWith("[") || r.out().strip().startsWith("{"), r.out());
    }

    // ---- marks -------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"false, true, false, false", "true, true, false, true", "false, false, false, true", "false, true, true, true"})
    void marksArePlainOnRequestInAPipeAndUnderNoColor(boolean noEmoji, boolean terminal, boolean noColorEnv, boolean plain) {
        assertEquals(plain, GlobalOptions.plainGlyphs(noEmoji, terminal, noColorEnv));
    }

    /** The update notice is printed before any command runs, so the choice is made from the raw arguments too. */
    @ParameterizedTest
    @CsvSource({"'status --no-emoji', false, true, true", "'status', false, true, false", "'status', true, true, true",
            "'status', false, false, true", "'--no-emoji=true status', false, true, true"})
    void theMarksAreChosenBeforeAnyCommandRuns(String commandLine, boolean noColorEnv, boolean interactive, boolean plain) {
        assertEquals(plain, FlociCli.plainGlyphs(commandLine.split(" "), noColorEnv, interactive));
    }

    @Test
    void thePlainSetIsAsciiOnly() {
        for (String mark : List.of(Glyphs.ASCII.ok(), Glyphs.ASCII.warn(), Glyphs.ASCII.fail(), Glyphs.ASCII.arrow())) {
            assertTrue(mark.chars().allMatch(c -> c < 128), mark);
        }
        assertEquals("✓", Glyphs.UNICODE.ok());
    }

    /** A test run is not a terminal, so a command's output here carries the plain marks. */
    @Test
    void pipedOutputCarriesNoUnicodeMarks() throws Exception {
        Path compose = Files.writeString(tempDir.resolve("compose.yml"), """
                services:
                  floci:
                    image: floci/floci:latest
                    ports: ["4566:4566"]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                """);

        Run r = run("config", "validate", "-f", compose.toString(), "--no-emoji");

        assertEquals(0, r.exit(), r.out() + r.err());
        assertTrue(Glyphs.isPlain());
        assertTrue(r.out().contains(Glyphs.ASCII.ok() + "  Compose file looks good"), r.out());
        assertFalse(r.out().contains("✓"), r.out());
    }

    // ---- config profile edit -----------------------------------------------------------------------

    private ProfileStore store() {
        return new ProfileStore(tempDir.resolve("profiles"));
    }

    private Path editorThat(String body) throws Exception {
        Path editor = Files.createTempFile(tempDir, "editor", ".sh");
        Files.writeString(editor, "#!/bin/sh\n" + body + "\n");
        assertTrue(editor.toFile().setExecutable(true));
        return editor;
    }

    private Run edit(Map<String, String> env, String name) {
        ConfigProfileCommand command = new ConfigProfileCommand(ProductProfile.AWS, store(), env::get);
        return capture(() -> new CommandLine(command).execute("edit", name));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void editOpensTheProfilesFileAndKeepsTheChange() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "dev"));
        Path editor = editorThat("echo 'port: 14566' >> \"$1\"");

        Run r = edit(Map.of("EDITOR", editor.toString()), "dev");

        assertEquals(0, r.exit(), r.err());
        assertEquals(14566, store().get("dev").orElseThrow().port);
        assertTrue(r.out().contains("Profile 'dev' saved"), r.out());
    }

    /** VISUAL wins over EDITOR, and a value with arguments is a command line. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void visualWinsAndArgumentsArePassedOn() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "dev"));
        Path args = tempDir.resolve("args");
        Path visual = editorThat("echo \"$1 $2\" > '" + args + "'");
        Path editor = editorThat("exit 9");

        Run r = edit(Map.of("VISUAL", visual + " --wait", "EDITOR", editor.toString()), "dev");

        assertEquals(0, r.exit(), r.err());
        assertEquals("--wait " + store().existingFile("dev"), Files.readString(args).strip());
    }

    /** A quoted path with spaces is one word, as a shell would read it; nothing else is interpreted. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "\"/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code\" --wait | /Applications/Visual Studio Code.app/Contents/Resources/app/bin/code;--wait",
            "'/opt/my editor/bin/edit' -n | /opt/my editor/bin/edit;-n",
            "/opt/my\\ editor/bin/edit | /opt/my editor/bin/edit",
            "emacsclient -c -a \"emacs --no-splash\" | emacsclient;-c;-a;emacs --no-splash",
            "vim -c 'set ft=yaml' | vim;-c;set ft=yaml",
            "code   --wait | code;--wait",
            "nano --rcfile=\"my rc\" | nano;--rcfile=my rc",
            "echo \"a \\\"quoted\\\" word\" | echo;a \"quoted\" word",
            "C:\\Windows\\System32\\notepad.exe | C:\\Windows\\System32\\notepad.exe",
            "\"C:\\Program Files\\Microsoft VS Code\\bin\\code.cmd\" --wait | C:\\Program Files\\Microsoft VS Code\\bin\\code.cmd;--wait",
            "\\\\tools\\share\\edit.exe -n | \\\\tools\\share\\edit.exe;-n",
            "'C:\\my tools\\edit.exe' | C:\\my tools\\edit.exe",
            "ed '' | ed;"})
    void anEditorValueIsSplitLikeAShellWould(String value, String expected) {
        assertEquals(List.of(expected.split(";", -1)), ConfigProfileCommand.editorCommand(value, null));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anEditorInADirectoryWithSpacesIsRun() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "dev"));
        Path dir = Files.createDirectories(tempDir.resolve("my editors"));
        Path editor = Files.writeString(dir.resolve("edit it"), "#!/bin/sh\necho 'port: 14566' >> \"$2\"\n");
        assertTrue(editor.toFile().setExecutable(true));

        Run r = edit(Map.of("EDITOR", "\"" + editor + "\" --wait"), "dev");

        assertEquals(0, r.exit(), r.err());
        assertEquals(14566, store().get("dev").orElseThrow().port);
    }

    @Test
    void theEditorIsVisualThenEditorSplitOnSpaces() {
        assertEquals(List.of("code", "--wait"), ConfigProfileCommand.editorCommand("code --wait", "vi"));
        assertEquals(List.of("vi"), ConfigProfileCommand.editorCommand(null, "vi"));
        assertEquals(List.of("vi"), ConfigProfileCommand.editorCommand("  ", " vi "));
        assertEquals(List.of(), ConfigProfileCommand.editorCommand(null, null));
        assertEquals(List.of(), ConfigProfileCommand.editorCommand("", ""));
    }

    @Test
    void editWithoutAnEditorSaysHowToSetOne() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "dev"));

        Run r = edit(Map.of(), "dev");

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("No editor is set"), r.err());
        assertTrue(r.err().contains("export EDITOR="), r.err());
    }

    @Test
    void editingAProfileThatDoesNotExistPointsAtCreate() {
        Run r = edit(Map.of("EDITOR", "true"), "nope");

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("Profile 'nope' not found."), r.err());
        assertTrue(r.err().contains("floci config profile create nope"), r.err());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anEditThatBreaksTheFileIsReportedAtOnce() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "dev"));
        Path editor = editorThat("echo 'port: [unterminated' > \"$1\"");

        Run r = edit(Map.of("EDITOR", editor.toString()), "dev");

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("Profile 'dev' is no longer valid"), r.err());
        assertTrue(r.err().contains("floci config profile edit dev"), r.err());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anEditorThatFailsIsReported() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "dev"));

        Run failed = edit(Map.of("EDITOR", editorThat("exit 3").toString()), "dev");
        Run missing = edit(Map.of("EDITOR", tempDir.resolve("no-such-editor").toString()), "dev");

        assertEquals(1, failed.exit());
        assertTrue(failed.err().contains("exited with code 3"), failed.err());
        assertEquals(1, missing.exit());
        assertTrue(missing.err().contains("Could not start the editor"), missing.err());
        assertTrue(missing.err().contains("Set EDITOR to a command on your PATH"), missing.err());
    }

    // ---- the old-server warning --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1.4.9", "1.0.0", "0.9.0"})
    void anAwsServerBelowTheMinimumIsWarnedAbout(String version) {
        String warning = ServerCompatibility.warning(ProductProfile.AWS, version, "").orElseThrow();

        assertTrue(warning.contains("server " + version + " is older than 1.5.0"), warning);
        assertTrue(warning.contains("docker pull floci/floci:latest"), warning);
        assertTrue(warning.contains("then run 'floci restart'."), warning);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5.0", "2.2.0", "unknown", "unavailable", "", "dev-SNAPSHOT"})
    void aSupportedOrUnreadableVersionIsNotWarnedAbout(String version) {
        assertTrue(ServerCompatibility.warning(ProductProfile.AWS, version, "").isEmpty(), version);
    }

    /** The other emulators are on 0.x version lines of their own: the AWS minimum says nothing about them. */
    @Test
    void theOtherProductsHaveNoMinimum() {
        for (ProductProfile product : List.of(ProductProfile.GCP, ProductProfile.AZ, ProductProfile.OCI)) {
            assertTrue(ServerCompatibility.warning(product, "0.4.2", "").isEmpty(), product.name());
        }
        assertTrue(ServerCompatibility.warning(ProductProfile.AWS, null, "").isEmpty());
    }

    /** The hint acts on the instance the version came from, and keeps that instance's settings. */
    @Test
    void theHintSelectsTheSameInstance() {
        String warning = ServerCompatibility.warning(ProductProfile.AWS, "1.4.0", " --profile dev").orElseThrow();

        assertTrue(warning.contains("then run 'floci restart --profile dev'."), warning);
        // The way out for a pinned tag acts on the same instance too.
        assertTrue(warning.contains("floci stop --remove --profile dev"), warning);
    }

    /** restart keeps the image reference, so the hint says what to do when that reference is the old tag. */
    @Test
    void theHintCoversAnInstancePinnedToAnOldTag() {
        String warning = ServerCompatibility.warning(ProductProfile.AWS, "1.4.0", " --container dev").orElseThrow();

        assertTrue(warning.contains("If the instance pins an older tag, restart keeps it"), warning);
        assertTrue(warning.contains("change 'image' in its profile"), warning);
        assertTrue(warning.contains("'--image floci/floci:latest'"), warning);
    }

    @Test
    void statusWarnsOnStderrAndKeepsStdoutOneDocument() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"version\":\"1.4.0\",\"edition\":\"community\",\"services\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            Run r = run("status", "--json", "--container", "floci-ergonomics-none",
                    "--endpoint", "http://127.0.0.1:" + server.getAddress().getPort());

            assertTrue(r.err().contains("server 1.4.0 is older than 1.5.0"), r.err());
            assertTrue(r.err().contains("floci restart --container floci-ergonomics-none"), r.err());
            assertTrue(r.out().strip().startsWith("{") && r.out().strip().endsWith("}"), r.out());
            assertFalse(r.out().contains("older than"), r.out());
        } finally {
            server.stop(0);
        }
    }
}
