# AGENTS.md

Guidance for AI coding agents working in the `floci-cli` repository.

This file defines repository-specific operating rules for autonomous or semi-autonomous coding agents. Follow these instructions unless a maintainer explicitly tells you otherwise.

---

## Project Overview

`floci-cli` is the official command-line interface for [Floci](https://floci.io) — the free, open-source local cloud emulator for AWS, GCP, and Azure. It manages emulator lifecycle (start/stop/status/logs), configuration profiles, diagnostics (`doctor`), and state snapshots.

- Stack: Java 25, Picocli 4.7.x, Jackson (JSON + YAML), JUnit 5
- Distribution: GraalVM native binaries (linux/amd64, linux/arm64, darwin/amd64, darwin/arm64, windows/amd64) + fat-JAR fallback
- No frameworks beyond Picocli — plain `java.net.http.HttpClient` and `docker` CLI subprocesses

---

## Build and Test Commands

```sh
mvn compile                          # compile only
mvn test                             # run all unit tests
mvn test -Dtest=WaitCommandParseTest # run a single test class
mvn test -Dtest=DockerVersionCheckTest#testVersionParsing  # run a single test method
mvn package                          # compile + test + produce target/floci.jar (fat JAR)
mvn package -DskipTests              # skip tests
mvn package -Pnative -DskipTests     # build native binary → target/floci  (requires GraalVM)
```

Running the fat JAR (requires Java 25+):

```sh
java -jar target/floci.jar <command>
```

---

## Architecture

### Entry point and command wiring

`FlociCli` is the Picocli root command. Every subcommand is registered in its `subcommands = {}` array. All commands implement `Callable<Integer>` and return an exit code.

Bare commands (`floci start`) route to the configured default product (`aws` unless changed via `floci config default-product aws|gcp|az|oci`). Explicit product groups are `floci aws` (same classes as the root tree), `floci gcp`, `floci az`, and `floci oci`.

### The unified product tree — CRITICAL

There is ONE implementation of every shared command, in `commands/`, parameterized by `ProductProfile` (`io.floci.cli.ProductProfile`) — the single source of per-product config:

| Profile | Default endpoint | Container | Env prefix | Control prefix |
|---------|------------------|-----------|------------|----------------|
| `AWS` (root tree) | `http://localhost:4566` | `floci` | `FLOCI_*` | `/_floci` |
| `GCP` | `http://localhost:4588` | `floci-gcp` | `FLOCI_GCP_*` | `/_floci-gcp` |
| `AZ` | `http://localhost:4577` | `floci-az` | `FLOCI_AZ_*` | `/_floci` (same as AWS — intentional) |
| `OCI` | `http://localhost:4599` | `floci-oci` | `FLOCI_OCI_*` | `/_floci-oci` |

The `commands/gcp/`, `commands/az/`, and `commands/oci/` leaf classes are **~8-line shims**: `GcpStartCommand extends StartCommand { super(ProductProfile.GCP); }` with their own `@Command` annotation carrying the per-product description. `ProductProfileTest` pins every profile field; `ProductTreesParsingTest` pins each tree's defaults.

Rules for the unified tree:

- **Every parameterized command pre-initializes its mixin in the constructor**: `this.global = new GlobalOptions(profile)`. Picocli uses a non-null `@Mixin` field instance as-is; a command that forgets gets AWS defaults in every tree (the per-tree parse tests catch this).
- **Per-product option defaults are constructor-set field values**, not annotation `defaultValue`s (annotation strings are compile-time constants). Use `(default: ${DEFAULT-VALUE})` in the option description to render them in help.
- **Never subclass a group command** (`GcpCommand`, `*ConfigCommand`, `*SnapshotCommand` groups): picocli registers the `subcommands` arrays of both super and subclass — instant duplicate-name crash. Groups stay standalone registration-only classes.
- Product-varying strings come from the profile: `displayName()` for banners, `commandPrefix()` for hint strings, `envVar("SERVICES")` for container env vars, `controlPrefix()` for `FlociHttpClient`, `serverRepo()` for issue-tracker links.
- Only `EnvCommand`/`GcpEnvCommand`/`AzEnvCommand`/`OciEnvCommand` are genuinely product-specific (not shims), plus the OCI-only `OciSetupCommand` (the OCI CLI/SDKs require a config file + signing key, so `floci oci setup` scaffolds them; no other tree needs an equivalent).

Commands call `global.printer()` at the start of `call()` — never store a `Printer` as a field.

### Two I/O boundaries

**Docker:** `DockerClient` wraps `docker` CLI subprocesses — no docker-java library. This is a deliberate native-image trade-off. `DockerClient` returns plain records (`ContainerInfo`, `ImageInfo`) and throws `DockerException` on non-zero exit codes.

**Floci server:** `FlociHttpClient` wraps `java.net.http.HttpClient` against the Floci REST API. All methods throw `FlociException`. The control-plane prefix is constructor-configurable: AWS and Azure use `/_floci`, GCP uses `/_floci-gcp`. Known live endpoints: `/_floci/health`, `/_floci/info`, `/_floci/init`. Snapshot endpoints (`/_floci/snapshots/*`) do not exist on the server yet.

### Output pipeline

Every command checks `printer.format()` to decide between text and structured output:

```java
if (printer.format() != OutputFormat.text) {
    printer.structured(map);   // renders as JSON or YAML via Jackson
    return 0;
}
// ... build human-readable text with Ansi helpers
```

`Ansi` is a static utility with a global `disable()` call (triggered by `--no-color` or non-TTY). Color is disabled once per JVM invocation; it cannot be re-enabled.

### Doctor checks

`Check` is a `@FunctionalInterface` taking `(endpoint, container)` and returning `CheckResult`. Order matters: Docker checks run before server checks because later checks depend on Docker being up.

One `DoctorCommand` serves every tree: `dockerChecks(profile)` builds the 9 Docker/server checks (image and control prefix from the profile), and the constructor appends the product's companion list — `AWS_COMPANION_CHECKS` (AWS CLI checks), `AZ_COMPANION_CHECKS` (az CLI checks), empty for GCP/OCI. `DoctorCheckListTest` pins each product's composition and order.

To add a check: create a class in `doctor/checks/`, then add it to `dockerChecks()` (all products) or the relevant companion list.

### Config profiles

`ProfileStore` reads/writes YAML files under `~/.floci/profiles/<name>.yaml` via Jackson. `Profile` is a plain bean — keep it `@JsonIgnoreProperties(ignoreUnknown = true)` to stay forward-compatible. The store is shared by all four product trees (no per-product namespacing). `GlobalConfigStore` persists `~/.floci/config.yaml` (currently just `default-product`).

### Self-update

`UpdateCommand` (`floci update`) downloads the release binary for the current platform, verifies its sha256 against the release's `sha256sums.txt`, and atomically replaces the running binary (staged in the same directory, `ATOMIC_MOVE`). It refuses to update Homebrew-managed installs. `--check` exits 0 when up to date, 1 when an update is available. `ReleaseChannel` builds the GitHub release asset URLs.

---

## Code Style and Good Practices

- **Prefer `record` for data carriers.** Any DTO, value object, or result type that just holds data should be a `record` (like `ContainerInfo`, `ImageInfo`, `CheckResult`, `HealthInfo`) — not a mutable bean with getters/setters. Exception: Jackson-mapped config beans that must stay forward-compatible and field-name-bound for the native image (`Profile`, `GlobalConfig`) may stay as plain beans; if you do make a Jackson-(de)serialized type a record, it still needs a `reflect-config.json` entry.
- **Prefer constructor injection over field mutation.** Collaborators (`DockerClient`, `FlociHttpClient`, `ProfileStore`, check lists) should be passed through the constructor and stored in `final` fields — see `DoctorCommand(List<Check> companionChecks)` for the pattern. Provide a no-arg constructor with defaults for Picocli, and a parameterized one as the test seam. Exception: Picocli requires non-final fields for `@Option`, `@Parameters`, and `@Mixin` — that is framework-mandated and fine.
- **Import classes; don't fully-qualify inline.** Add an `import` and use the simple name instead of writing `io.floci.cli.output.Printer` (or `java.util.List`, etc.) inline in signatures and bodies. Avoid wildcard imports except the existing `picocli.CommandLine.*` convention.
- Keep fields `final` wherever possible; prefer immutable collections (`List.of`, `List.copyOf`) over mutable ones exposed outside a method.

---

## Native Image Constraints

- **No new reflection-heavy dependencies.** If adding a library, verify it works under `--no-fallback`.
- **New Jackson DTOs** (anything `ObjectMapper` serializes/deserializes by field name) must be added to `src/main/resources/META-INF/native-image/io.floci/floci-cli/reflect-config.json`. Prefer passing `Map`/`JsonNode` to `printer.structured(...)` — those need no registration.
- **`@Command` classes** do not need manual reflect-config entries — `picocli-codegen` (the annotation processor in `pom.xml`) generates them automatically at compile time.
- **Do not remove** `--enable-url-protocols=http,https` from `native-image.properties`; `java.net.http.HttpClient` requires it.

---

## Scope Rules

- **No cloud resource commands.** This CLI manages Floci itself (lifecycle, config, diagnostics, state). Resource operations belong to the vendor CLIs pointed at the emulator: `aws` + `AWS_ENDPOINT_URL`, `gcloud`/SDK emulator-host vars, `az` + connection string.
- **No telemetry, no TUI, no Floci Cloud commands.**
- **Self-update is in scope** (`floci update`) — but it must never auto-run; updates happen only on explicit user invocation.
- **Snapshot commands:** the AWS `save/load/list/delete` call `/_floci/snapshots/*` and degrade gracefully (404/501 → "not available"); `export/import` and the entire GCP/Azure/OCI snapshot subtrees are stubs pending server-side endpoints in `floci-io/floci`, `floci-io/floci-gcp`, `floci-io/floci-az`, and `floci-io/floci-oci`. Do not invent client-side snapshot behavior — the server API contract comes first.

---

## Error Message Convention

Every `printer.error(...)` call must end with a suggested next step:

```java
printer.error("Container 'floci' not found.\nRun 'floci start' to launch one.");
```

---

## Documentation Rules

- `README.md` documents all four product trees (AWS, GCP, Azure, OCI). If you add/change a command or flag, update the README in the same change.
- `CHANGELOG.md` follows Keep a Changelog. Every user-visible change gets an entry under `[Unreleased]` in the same PR.
- `CLAUDE.md` is gitignored (maintainer-local); this file (`AGENTS.md`) is the tracked source of truth for agent guidance. Update it here.
