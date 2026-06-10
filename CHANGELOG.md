# Changelog

All notable changes to `floci-cli` will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `floci start` (and `floci az`/`floci gcp start`) now honor the standard `DOCKER_HOST` environment variable, so Podman, rootless setups, and remote Docker contexts work without assuming `/var/run/docker.sock`. `DOCKER_HOST` is resolved into a unix socket, remote TCP daemon, or Windows named pipe — unix sockets are bind-mounted, remote TCP daemons are passed through to the container via `DOCKER_HOST`. Precedence is `DOCKER_HOST` → `DOCKER_SOCK` (legacy override) → OS default. `floci doctor`'s `docker.socket` check reports the resolved endpoint ([#3](https://github.com/floci-io/floci-cli/issues/3))

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

[Unreleased]: https://github.com/floci-io/floci-cli/compare/0.1.2...HEAD
[0.1.2]: https://github.com/floci-io/floci-cli/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/floci-io/floci-cli/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/floci-io/floci-cli/releases/tag/0.1.0
