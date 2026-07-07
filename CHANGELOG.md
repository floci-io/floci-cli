# Changelog

All notable changes to `floci-cli` will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `floci update` — self-update for native-binary installs. Downloads the platform binary from the GitHub release (same source as `install.sh`), verifies its sha256 against the release's `sha256sums.txt`, and atomically replaces the running binary (staged in the same directory so the `ATOMIC_MOVE` can never leave a half-written executable). `--check` reports staleness without installing (exit 0 = up to date, 1 = update available, script-friendly as `floci update --check || floci update`); `--version <v>` pins a target version. Homebrew-managed installs are detected and refused with a pointer to `brew upgrade floci` ([#12](https://github.com/floci-io/floci-cli/pull/12))

### Fixed

- `ContainerInfo` fields realigned with the `docker inspect` `--format` template order in `DockerClient` — field/value mismatches could surface wrong container metadata (e.g. status/ports swapped) in `status` and port auto-detection ([#11](https://github.com/floci-io/floci-cli/pull/11))

### Documentation

- README now documents the `floci gcp` command group (quick start, command reference, global flags, `floci gcp env` variables, CI usage) — it was fully shipped since 0.1.3 but absent from the README, which claimed the CLI covered "AWS and Azure" only
- Corrected the fat-JAR runtime requirement to Java 25+ in the README and release notes (the jar is built with `--release 25`; the notes previously claimed Java 21+)
- Added `AGENTS.md` — tracked, repository-specific guidance for AI coding agents (architecture, native-image constraints, scope rules, conventions)
- Added `CONTRIBUTING.md` — build/test setup, commit message and changelog conventions, PR guidelines, testing policy, and the tag-driven release process

## [0.1.8] — 2026-06-21

### Fixed

- `floci start --persist` bind-mounted the host directory to `/app/data` but the floci server still ran in its default in-memory storage mode (`FLOCI_STORAGE_MODE=memory`), so emulator state was never written to the mount and did not survive a container recreate. `--persist` now also sets `FLOCI_STORAGE_MODE=persistent` (the per-cloud-prefixed variant `FLOCI_GCP_STORAGE_MODE` / `FLOCI_AZ_STORAGE_MODE` for `floci gcp start` / `floci az start`), so state actually persists across restarts. Completes the persistence fix started in [#8](https://github.com/floci-io/floci-cli/issues/8) ([#9](https://github.com/floci-io/floci-cli/pull/9))

## [0.1.7] — 2026-06-20

### Fixed

- `floci start` mounted the host persistent path to `/var/lib/floci` inside the container, but the floci server reads and writes its persisted state under `/app/data`. State therefore never survived a container recreate despite `--persist` being set. The bind mount now targets `/app/data`, matching the server's data directory ([#8](https://github.com/floci-io/floci-cli/issues/8))

## [0.1.6] — 2026-06-11

### Fixed

- `floci doctor`'s `docker.daemon` check reported "Docker daemon not reachable" under Podman even when the daemon was clearly up (container running, endpoint reachable). The probe used `docker info --format '{{.ServerVersion}}'`, a Docker-only template field that errors under `podman info`. It now checks daemon reachability by exit code alone (`docker info` / `podman info` exit 0 when reachable), with output discarded to avoid a pipe-buffer deadlock

## [0.1.5] — 2026-06-10

### Added

- `floci start` (and `floci az`/`floci gcp start`) now honor the standard `DOCKER_HOST` environment variable, so Podman, rootless setups, and remote Docker contexts work without assuming `/var/run/docker.sock`. `DOCKER_HOST` is resolved into a unix socket, remote TCP daemon, or Windows named pipe — unix sockets are bind-mounted, remote TCP daemons are passed through to the container via `DOCKER_HOST`. Precedence is `DOCKER_HOST` → `DOCKER_SOCK` (legacy override) → OS default. `floci doctor`'s `docker.socket` check reports the resolved endpoint ([#3](https://github.com/floci-io/floci-cli/issues/3))
- Release ships a `darwin/amd64` (Intel macOS) native binary again — built with an x86_64 GraalVM under Rosetta 2 on the Apple Silicon runner, avoiding the unreliable/queue-bound `macos-13` Intel runner that previously caused the target to be dropped (a past release sat 9.5h on `macos-13` before being cancelled). The Homebrew formula bump again wires the `darwin/amd64` SHA, so `brew install` and the install script resolve a real Intel binary instead of 404ing ([#2](https://github.com/floci-io/floci-cli/issues/2))

### Fixed

- `install.sh` no longer reports a misleading "Checksum mismatch" when a download transiently fails. A failed `sha256sums.txt` fetch inside a command-substitution pipeline was masked by `set -e`, leaving the expected checksum empty and falsely flagging a mismatch against a correctly-downloaded binary. The installer now retries every download with backoff — an explicit loop (4 attempts by default, tunable via `FLOCI_DOWNLOAD_RETRIES`) that treats both transient HTTP errors and 0-byte responses as retryable, on top of `curl --retry` — fetches checksums to a file so download failures are caught, and emits distinct errors for "couldn't fetch checksum" vs. an actual mismatch. Adds `FLOCI_SKIP_CHECKSUM=1` to bypass verification (floci-io/floci#1236)

## [0.1.4] — 2026-06-02

### Fixed

- `floci gcp wait` / `status` / `version` / `services` / `doctor` timed out or reported the server unreachable even when the floci-gcp container was healthy — the CLI probed the AWS/Azure control path `/_floci/health`, which the GCP server routes into its GCS service (`object not found bucket=_floci name=health`). The GCP control plane lives under `/_floci-gcp`; `FlociHttpClient` now takes a configurable control-plane prefix and the `floci gcp` commands use `/_floci-gcp`

## [0.1.3] — 2026-06-02

### Added

- `floci gcp` subcommand group — full GCP emulator lifecycle via `floci gcp start|stop|restart|status|logs|wait|version|services|doctor|env|config|snapshot|completion`
- `floci gcp start` — launches `floci/floci-gcp:latest` on port 4588, mounts persist dir to `/app/data`
- `floci gcp env` — prints GCP SDK emulator host variables (`STORAGE_EMULATOR_HOST`, `PUBSUB_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST`, `DATASTORE_EMULATOR_HOST`, `SECRET_MANAGER_EMULATOR_HOST`); `--service` flag filters to a specific service (gcs, pubsub, firestore, datastore, secretmanager); supports bash, fish, and powershell shell formats
- `floci gcp doctor` — GCP-specific diagnostics: Docker checks against the `floci/floci-gcp` image plus GCP environment checks
- `floci gcp config show|profile|validate` — manage GCP profile configuration
- `floci config default-product gcp` — `gcp` is now a valid default product alongside `aws` and `az`; bare `floci start` routes to the configured default
- `floci gcp snapshot` stubs — commands registered but report "not yet available" pending server-side implementation in floci-gcp

## [0.1.2] — 2026-05-15

### Fixed

- `floci version` / `floci --version` now reads the version from a Maven-filtered `version.properties` resource instead of a hardcoded string — bumping `pom.xml` is now the single source of truth
- Homebrew bump workflow: secret renamed from `HOMEBREW_BUMP_TOKEN` to `GH_TOKEN`; removed stale `darwin/amd64` SHA extraction that referenced the dropped build target

## [0.1.1] — 2026-05-15

### Added

- `floci az` subcommand group — full Azure emulator lifecycle via `floci az start|stop|restart|status|logs|wait|version|services|doctor|env|snapshot|completion`
- `floci aws` subcommand group — explicit alias for all AWS commands (mirrors bare `floci *`)
- `floci az start` — launches `floci/floci-az:latest` on port 4577, mounts persist dir to `/app/data`
- `floci az env` — prints `AZURE_STORAGE_CONNECTION_STRING` (default) or individual SDK endpoint vars (`--format sdk-vars`); `--service` flag filters to specific services (blob, queue, table, functions, app-config, key-vault)
- `floci az doctor` — Azure-specific diagnostics: all Docker checks against `floci/floci-az` image, plus `az.cli.installed` and `az.cli.connection-string` checks
- `floci config default-product aws|az` — persists the default product to `~/.floci/config.yaml`; bare `floci start` routes to the configured default
- `floci az snapshot` stubs — commands registered but report "not yet available" pending server-side implementation in floci-az

### Changed

- `floci start` now routes to the configured default product (AWS unless overridden with `floci config default-product az`)
- `floci env` and `floci az env` now auto-detect the port from the running container's port mapping instead of using the option default
- CI native build reduced to linux/arm64 only — removed linux/amd64 native job from CI (covered by JVM test job); release still ships all platform binaries
- Release drops darwin/amd64 — `macos-13` Intel runners took 34+ minutes for native-image builds; darwin/arm64 (Apple Silicon) ships instead

### Fixed

- `floci env` reported wrong port when container ran on a non-default port — now calls `resolvedEndpoint()` before extracting port
- `floci az doctor` incorrectly checked for `floci/floci` image — now checks `floci/floci-az`

## [0.1.0] — 2026-05-14

### Added

- `floci start` — launches the Floci container with configurable port, persist directory, services, and image pull policy
- `floci stop` — stops and optionally removes the container
- `floci restart` — stop + start in sequence
- `floci status` — shows container state, endpoint reachability, and server version
- `floci logs` — streams container logs with `--follow`, `--tail`, `--since` options
- `floci wait` — polls `/_floci/health` until ready, with configurable timeout
- `floci version` — prints CLI version, server version, and image digest
- `floci services` — lists enabled services from the running instance
- `floci doctor` — runs 13 environment checks: Docker installation, daemon, socket, version, port availability, image presence and version, container state, endpoint reachability, AWS CLI endpoint configuration, S3 path-style config, Rust SDK XML warning, Go SDK endpoint warning
- `floci config show` — displays the active configuration and profile
- `floci config validate` — validates a docker-compose.yml for Floci compatibility
- `floci config profile list|show|create|delete` — manages profiles in `~/.floci/profiles/`
- `floci snapshot save|load|list|delete` — thin wrapper over `/_floci/snapshots/*` (stubs pending server implementation)
- `floci snapshot export|import` — stubs pending server implementation
- `floci completion bash|zsh` — generates shell completion scripts
- Global flags: `--endpoint`, `--container`, `--profile`, `--output text|json|yaml`, `--quiet`, `--verbose`, `--no-color`
- GraalVM native binary for linux/amd64, linux/arm64, darwin/arm64, windows/amd64
- Homebrew formula template and automated bump workflow
- Scoop bucket template
- `curl | sh` installer for Linux and macOS
- PowerShell installer for Windows

### Known Limitations

- `floci snapshot export` and `floci snapshot import` are stubs — server-side endpoints not yet implemented
- `floci logs --service` filter is not yet supported (requires server-side log routing)
- Shell completion for `fish` and `powershell` is not yet generated by Picocli

[Unreleased]: https://github.com/floci-io/floci-cli/compare/0.1.8...HEAD
[0.1.8]: https://github.com/floci-io/floci-cli/compare/0.1.7...0.1.8
[0.1.7]: https://github.com/floci-io/floci-cli/compare/0.1.6...0.1.7
[0.1.6]: https://github.com/floci-io/floci-cli/compare/0.1.5...0.1.6
[0.1.5]: https://github.com/floci-io/floci-cli/compare/0.1.4...0.1.5
[0.1.4]: https://github.com/floci-io/floci-cli/compare/0.1.3...0.1.4
[0.1.3]: https://github.com/floci-io/floci-cli/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/floci-io/floci-cli/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/floci-io/floci-cli/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/floci-io/floci-cli/releases/tag/0.1.0
