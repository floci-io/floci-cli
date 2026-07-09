# Contributing to floci-cli

Thank you for your interest in contributing! `floci-cli` is a community-driven project and all contributions are welcome.

## Ways to Contribute

- **Bug reports** — open an issue with the CLI version (`floci version`), OS/arch, and the exact command + output
- **Feature requests** — open an issue describing the workflow you need (keep the [scope rules](#scope) in mind)
- **Pull requests** — bug fixes, new commands, diagnostics checks, or improvements

## Getting Started

### Prerequisites

- Java 25+
- Maven 3.9+
- Docker (the CLI drives `docker` subprocesses; you'll want a running Floci container to test against)
- GraalVM 25+ (only for building the native binary)

If you need to install a JDK, [SDKMAN](https://sdkman.io/) is a convenient option:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-graal    # GraalVM — also covers the plain JDK requirement
```

### Build & Test

```bash
git clone https://github.com/floci-io/floci-cli.git
cd floci-cli

mvn test                             # run all unit tests
mvn test -Dtest=WaitCommandParseTest # single test class
mvn test -Dtest=DockerVersionCheckTest#testVersionParsing  # single method
mvn package                          # fat JAR → target/floci.jar
mvn package -Pnative -DskipTests     # native binary → target/floci (requires GraalVM)
```

### Try it against a real emulator

```bash
java -jar target/floci.jar start     # or ./target/floci start
java -jar target/floci.jar doctor
java -jar target/floci.jar stop
```

**If your change touches Jackson serialization or adds a dependency, build and run the native binary at least once** — reflection that works on the JVM can fail under `native-image`. See the Native Image Constraints section of [AGENTS.md](AGENTS.md).

## Architecture

See [AGENTS.md](AGENTS.md) for the architecture: command wiring, the three product trees (AWS/GCP/Azure), the Docker and HTTP I/O boundaries, the output pipeline, native-image constraints, and scope rules.

`AGENTS.md` is the canonical agent instructions file for this repository, following the [AGENTS.md standard](https://agents.md/). If your coding agent expects a different filename, create a local symlink instead of copying the file:

```bash
ln -s AGENTS.md CLAUDE.md
ln -s AGENTS.md GEMINI.md
```

### Rules that trip up most PRs

- **Three product trees.** The `gcp` and `az` command trees mirror the root/AWS tree. A change to a shared command (start, stop, status, logs, wait, doctor, …) must be applied to **all three trees** — `StartCommand`, `GcpStartCommand`, and `AzStartCommand` — until they are unified.
- **Native image.** New Jackson DTOs must be registered in `reflect-config.json`; prefer passing `Map`/`JsonNode` to `printer.structured(...)`, which needs no registration. No reflection-heavy dependencies.
- **Error messages.** Every `printer.error(...)` must end with a suggested next step:

  ```java
  printer.error("Container 'floci' not found.\nRun 'floci start' to launch one.");
  ```

- **Structured output.** Every command must support `-o json|yaml` via `printer.structured(...)` alongside its text output.

## Scope

`floci-cli` manages Floci itself — lifecycle, config, diagnostics, state. Out of scope (PRs in these areas will be declined):

- Cloud resource commands (creating buckets, tables, topics, …) — that's the job of `aws`/`gcloud`/`az` pointed at the emulator
- Telemetry, TUIs, or Floci Cloud commands
- Client-side snapshot behavior beyond the server's `/_floci/snapshots/*` API

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/). PR titles should follow the same format, since they become the squash-merge commit message.

```
<type>[optional scope]: <description>
```

- **type** — lowercase, from the table below
- **scope** — optional, identifies the command or area (e.g. `start`, `doctor`, `update`, `gcp`)
- **description** — imperative mood, no trailing period
- Append `!` before the colon for a breaking change: `feat(cli)!:`

| Type | When to use |
|------|-------------|
| `feat` | New command, flag, or check |
| `fix` | Bug fix |
| `perf` | Performance improvement |
| `docs` | Documentation only |
| `refactor` | Code restructure without behavior change |
| `test` | Adding or updating tests |
| `build` / `ci` | Build system or CI workflow changes |
| `chore` | Dependencies, housekeeping, releases |

Examples from this repo's history:

```
feat(update): add self-update command with checksum verification
fix(start): mount host persistent path to /app/data in container
chore: release 0.1.8
```

Do not include `Co-Authored-By` trailers for AI tools in commit messages. Attribution should be limited to human contributors.

## Changelog

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and is maintained **by hand** (no semantic-release). Every PR with a user-visible change must add an entry under `[Unreleased]` in the same PR, written from the user's point of view (what was broken / what's new, not how it was implemented).

## Pull Request Guidelines

1. Branch off `main`: `git checkout -b feature/my-feature`
2. Open a PR targeting `main`
3. CI runs the JVM test suite and a linux/arm64 native smoke test — all checks must pass before merge
4. Keep PRs focused — one feature or fix per PR
5. Update `README.md` in the same PR if you add or change a command or flag
6. Reference related issues in the PR description

### Testing policy

- PRs that introduce new behavior must include tests validating that behavior
- Bug-fix PRs should include a regression test whenever the bug can be covered realistically
- Docs, formatting, and low-risk internal refactors may not need new tests — but the existing suite must pass
- If a PR includes no new tests, explain why in the description

## Release Process (maintainers)

Releases are **tag-driven**. Binaries are never published on PR merge — only when a SemVer tag is pushed.

```bash
# 1. On main: bump <version> in pom.xml, move [Unreleased] entries in
#    CHANGELOG.md under the new version heading, and commit
git commit -am "chore: release 0.2.0"
git push

# 2. Tag and push — this triggers the release pipeline
git tag 0.2.0
git push origin 0.2.0
```

The tag push triggers `release.yml`, which builds native binaries for linux/amd64, linux/arm64, darwin/amd64, darwin/arm64, and windows/amd64, plus the fat JAR, and publishes a GitHub Release with `sha256sums.txt`. A successful release then triggers `homebrew-bump.yml`, which opens a version-bump PR against the Homebrew tap automatically.

## Reporting Security Issues

Please do **not** open public issues for security vulnerabilities. Report them privately using [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
