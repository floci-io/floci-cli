# Contributing to floci-cli

Thank you for your interest in contributing! `floci-cli` is a community-driven project and all contributions are welcome.

## Ways to Contribute

- **Bug reports**: open an issue with the CLI version (`floci version`), OS/arch, and the exact command + output
- **Feature requests**: open an issue describing the workflow you need (keep the [scope rules](#scope) in mind)
- **Pull requests**: bug fixes, new commands, diagnostics checks, or improvements

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
sdk install java 25-graal    # GraalVM : also covers the plain JDK requirement
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

The native CI and release jobs run `.github/scripts/smoke.sh ./target/floci`. It checks
completion, rejected input, JSON and YAML output, profile create/edit/read/delete, reset's
confirmation guard and Compose validation with YAML anchors and merge keys. It needs no
emulator or Docker daemon. It skips profile edit on Windows. Run it after building the native binary when changing these paths.

### Try it against a real emulator

```bash
java -jar target/floci.jar start     # or ./target/floci start
java -jar target/floci.jar doctor
java -jar target/floci.jar stop
```

**If your change touches Jackson serialization or adds a dependency, build and run the native binary at least once**: reflection that works on the JVM can fail under `native-image`. See the Native Image Constraints section of [AGENTS.md](AGENTS.md).

## Architecture

See [AGENTS.md](AGENTS.md) for the architecture: command wiring, the unified `ProductProfile`-parameterized command tree (AWS/GCP/Azure/OCI), the Docker and HTTP I/O boundaries, the output pipeline, native-image constraints, and scope rules.

`AGENTS.md` is the canonical agent instructions file for this repository, following the [AGENTS.md standard](https://agents.md/). If your coding agent expects a different filename, create a local symlink instead of copying the file:

```bash
ln -s AGENTS.md CLAUDE.md
ln -s AGENTS.md GEMINI.md
```

### Rules that trip up most PRs

- **One parameterized command tree.** Shared commands live once in `commands/`, parameterized by `ProductProfile`; the `gcp`/`az`/`oci` classes are thin shims (`GcpStartCommand extends StartCommand`). Change the base command and every tree gets it. New per-product values belong in `ProductProfile`, not in command code. Never subclass a group command (duplicate subcommand registration), and always pre-initialize the `GlobalOptions` mixin with the profile in command constructors.
- **Native image.** New Jackson DTOs must be registered in `reflect-config.json`; prefer passing `Map`/`JsonNode` to `printer.structured(...)`, which needs no registration. No reflection-heavy dependencies.
- **Error messages.** Every `printer.error(...)` must end with a suggested next step:

  ```java
  printer.error("Container 'floci' not found.\nRun 'floci start' to launch one.");
  ```

- **Structured output.** Every command must support `-o json|yaml` via `printer.structured(...)` alongside its text output.

## Scope

`floci-cli` manages Floci itself: lifecycle, config, diagnostics, state. Out of scope (PRs in these areas will be declined):

- Cloud resource commands (creating buckets, tables, topics, …): that's the job of `aws`/`gcloud`/`az` pointed at the emulator
- Telemetry, TUIs, or Floci Cloud commands
- Client-side snapshot behavior beyond the server's `/_floci/snapshots/*` API

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/). PR titles should follow the same format, since they become the squash-merge commit message.

```
<type>[optional scope]: <description>
```

- **type**: lowercase, from the table below
- **scope**: optional, identifies the command or area (e.g. `start`, `doctor`, `update`, `gcp`)
- **description**: imperative mood, no trailing period
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

PRs are squash-merged, so the **PR title** becomes the commit on `main` that release-please reads. The commit-lint check validates both your commits and the PR title.

Do not include `Co-Authored-By` trailers for AI tools in commit messages. Attribution should be limited to human contributors.

## Changelog

`CHANGELOG.md` is generated by [release-please](https://github.com/googleapis/release-please) from Conventional Commit messages. **Do not edit it by hand**. A `feat`, `fix` or `perf` subject (or a breaking change) becomes a release note verbatim, so write those from the user's point of view: what was broken or what's new, not how it was implemented. The other accepted types (`docs`, `chore`, `refactor`, `test`, `build`, `ci`) pass CI but produce no release and no entry, which is normal and needs no action. Genuine corrections to the file (typos in history, security notes) require the `changelog-edit` label on the PR.

## Pull Request Guidelines

1. Branch off `main`: `git checkout -b feature/my-feature`
2. Open a PR targeting `main`
3. CI runs the JVM test suite and a linux/arm64 native smoke test. All checks must pass before merge
4. Keep PRs focused: one feature or fix per PR
5. Update `README.md` in the same PR if you add or change a command or flag
6. Reference related issues in the PR description

### Testing policy

- PRs that introduce new behavior must include tests validating that behavior
- Bug-fix PRs should include a regression test whenever the bug can be covered realistically
- Docs, formatting, and low-risk internal refactors may not need new tests, but the existing suite must pass
- If a PR includes no new tests, explain why in the description

### Pull Request Limits and Review Bandwidth

To make sure every contribution gets a thorough, high-quality review in a reasonable time, we ask contributors to keep **no more than 4 open pull requests**, drafts included, at any time in this repository, and **no more than 2 of them ready for review**.

- **Why this policy exists:** maintainer review time is limited. Capping concurrent open PRs prevents review backlogs, reduces context switching, and keeps PR cycle times short for everyone.
- **Dependent work:** if your work depends on a PR that has not been merged yet, build on that branch or note the dependency in the discussion instead of opening separate, uncoordinated PRs.
- **Draft PRs:** drafts do not count against the limit of 2 ready pull requests, but they do count toward the total of 4. You can have, for example, 2 ready and 2 drafts, or 1 ready and 3 drafts. Use drafts for work in progress, not as a queue of finished changes waiting for a review slot, and mark a draft as ready for review only when you have review capacity available.
- **How it is applied:** a bot turns a pull request back into a draft if it would be your 3rd ready for review, and closes a pull request opened while you already have 4 open, drafts included. Your branch and commits are always kept: mark the draft ready again once one of your ready pull requests is merged, closed or turned into a draft, and reopen a closed pull request once you have fewer than 4 open. Maintainers and dependency bots are not counted.

Once your current pull requests are reviewed, merged, or closed, you are welcome to open new ones!

## Release Process (maintainers)

Releases are cut when there is something worth shipping. Unlike the emulators, floci-cli is
not on a fixed train.

Releases are made with [release-please](https://github.com/googleapis/release-please) (`.github/workflows/release-please.yml`). On every push to `main` it keeps one release PR open, titled `chore(main): release X.Y.Z`, which bumps `<version>` in `pom.xml` and adds the `CHANGELOG.md` entry for the Conventional Commits since the last release. To release, review that PR (the version and notes can be edited there) and merge it.

Merging the release PR tags the release (plain `X.Y.Z`, no `v`) and creates the GitHub Release. The same workflow then builds native binaries for linux/amd64, linux/arm64, darwin/amd64, darwin/arm64 and windows/amd64 plus the fat JAR (`release.yml`), smoke-tests each binary and attaches them with `sha256sums.txt` to the release, opens the formula bump PR against the Homebrew tap (`homebrew-bump.yml`), and runs CodeQL and Trivy against the tag.

If a publish step fails, re-run it without cutting a new version: Actions → Release Please → Run workflow, with `tag` set to the existing tag (e.g. `0.5.0`).

Nothing is published on any other PR merge: only merging the release PR produces artifacts.

## Reporting Security Issues

Please do **not** open public issues for security vulnerabilities. Report them privately using [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
