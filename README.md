# floci-cli

Official command-line interface for [Floci](https://floci.io) — the free, open-source local cloud emulator for AWS and Azure.

```sh
# AWS (default)
floci start
eval $(floci env)
aws s3 mb s3://my-bucket

# Azure
floci az start
eval $(floci az env)
az storage container create --name mycontainer
```

## Installation

### Homebrew (macOS / Linux)

```sh
brew install floci-io/floci/floci
```

### Install script (Linux / macOS)

```sh
curl -fsSL https://floci.io/install.sh | sh
```

### Windows (PowerShell)

```powershell
iwr https://floci.io/install.ps1 | iex
```

### Scoop (Windows)

```powershell
scoop bucket add floci https://github.com/floci-io/scoop-floci
scoop install floci
```

### JVM fallback

Download `floci.jar` from the [latest release](https://github.com/floci-io/floci-cli/releases/latest) and run:

```sh
java -jar floci.jar version
```

---

## Quick Start

### AWS

```sh
# Start Floci (AWS emulator)
floci start

# Check environment
floci doctor

# Export AWS environment variables
eval $(floci env)

# Use AWS services normally
aws s3 mb s3://my-bucket
aws dynamodb create-table --table-name users \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

# Stop Floci
floci stop
```

### Azure

```sh
# Start Floci Azure emulator
floci az start

# Check environment
floci az doctor

# Export Azure connection string
eval $(floci az env)

# Use Azure services
az storage container create --name mycontainer
az storage blob upload --container-name mycontainer --name hello.txt --data "hello"

# Stop Floci Azure
floci az stop
```

### Switch default product

Bare commands like `floci start` route to the configured default product (AWS by default).

```sh
floci config default-product az   # make floci az the default
floci config default-product aws  # revert to aws
```

---

## Command Reference

Commands are organized into two product groups — `floci aws` (or bare `floci`) and `floci az`. Both groups expose the same lifecycle commands.

### Shared commands (product-independent)

| Command | Description |
|---------|-------------|
| `floci config show` | Show active configuration |
| `floci config validate` | Validate a docker-compose.yml |
| `floci config profile` | Manage named profiles |
| `floci config default-product` | Set the default product (aws or az) |
| `floci completion bash\|zsh` | Generate shell completion scripts |
| `floci update` | Update the CLI to the latest release |

### AWS commands (`floci` / `floci aws`)

| Command | Description |
|---------|-------------|
| `floci start` | Launch the Floci AWS container |
| `floci stop` | Stop (and optionally remove) the container |
| `floci restart` | Stop then start |
| `floci status` | Show container state and server health |
| `floci logs` | Stream container logs |
| `floci wait` | Poll until Floci is ready (CI-friendly) |
| `floci version` | Show CLI and server versions |
| `floci services` | List enabled AWS services |
| `floci doctor` | Run environment diagnostics |
| `floci env` | Print AWS environment variables |
| `floci snapshot save/load/list/delete` | Manage state snapshots |

### Azure commands (`floci az`)

| Command | Description |
|---------|-------------|
| `floci az start` | Launch the Floci Azure container |
| `floci az stop` | Stop (and optionally remove) the container |
| `floci az restart` | Stop then start |
| `floci az status` | Show container state and server health |
| `floci az logs` | Stream container logs |
| `floci az wait` | Poll until Floci Azure is ready (CI-friendly) |
| `floci az version` | Show CLI and server versions |
| `floci az services` | List enabled Azure services |
| `floci az doctor` | Run Azure environment diagnostics |
| `floci az env` | Print Azure connection string / SDK env vars |
| `floci az snapshot` | Snapshot commands (coming soon) |

All commands support `--help`.

---

## Global Flags

### AWS global flags

```
--endpoint <url>            Floci server URL     (default: http://localhost:4566, env: FLOCI_ENDPOINT)
--container <name>          Container name       (default: floci, env: FLOCI_CONTAINER)
--output|-o text|json|yaml  Output format        (default: text)
--quiet, -q                 Suppress non-error output
--verbose, -v               Debug logging to stderr
--no-color                  Disable ANSI colors
--profile <name>            Load settings from ~/.floci/profiles/<name>.yaml
```

### Azure global flags

```
--endpoint <url>            Floci Azure server URL  (default: http://localhost:4577, env: FLOCI_AZ_ENDPOINT)
--container <name>          Container name          (default: floci-az, env: FLOCI_AZ_CONTAINER)
--output|-o text|json|yaml  Output format           (default: text)
--quiet, -q                 Suppress non-error output
--verbose, -v               Debug logging to stderr
--no-color                  Disable ANSI colors
```

> **Port auto-detection** — `status`, `version`, `wait`, and `env` automatically derive the correct
> endpoint from the container's port mapping. You don't need to pass `--endpoint` when using
> a non-default port, as long as `--container` points to the right container.

---

## Commands

### `floci start` / `floci az start`

Pulls the image (if needed), starts the container, and waits for readiness.

```sh
# AWS
floci start                          # default port 4566
floci start --port 4599              # custom host port
floci start --services s3,dynamodb   # enable specific services
floci start --persist ./data         # persist state to a host directory
floci start --pull always            # always pull the latest image
floci start --detach                 # return immediately, don't wait

# Azure
floci az start                       # default port 4577
floci az start --port 4578           # custom host port
floci az start --persist ./data      # persist state to a host directory
```

#### Docker daemon resolution (Podman, rootless, remote contexts)

The Floci container needs access to a Docker-compatible daemon (for Lambda, EC2,
EKS, MSK, ECR, CodeBuild, and Kafka/Redpanda support). By default `floci start`
bind-mounts `/var/run/docker.sock`, but it honors the standard `DOCKER_HOST`
environment variable, so Podman, rootless setups, and remote Docker contexts work
without extra flags:

```sh
# Rootless Podman
export DOCKER_HOST=unix:///run/user/1000/podman/podman.sock
floci start

# Rootful Podman
export DOCKER_HOST=unix:///run/podman/podman.sock
floci start

# Remote daemon over TCP
export DOCKER_HOST=tcp://10.0.0.5:2375
floci start
```

Resolution precedence:

1. **`DOCKER_HOST`** — the standard Docker/Podman variable (`unix://` socket, `tcp://` daemon, or `npipe://` on Windows)
2. **`DOCKER_SOCK`** — legacy override (a bare socket path)
3. **OS default** — `/var/run/docker.sock` on Linux/macOS, or the Docker named pipe on Windows

For a `unix://` socket the resolved path is bind-mounted into the container; for a
remote `tcp://` daemon the `DOCKER_HOST` value is passed through to the container
instead. Run `floci doctor` to see which endpoint was resolved.

### `floci stop` / `floci az stop`

```sh
floci stop                    # graceful stop (10s timeout)
floci stop --timeout 30       # wait up to 30s before force-kill
floci stop --remove           # also remove the container after stopping
```

### `floci status` / `floci az status`

```sh
floci status                          # auto-detects endpoint from container port mapping
floci status --container myfloci      # target a specific container
floci status -o json                  # structured output
```

### `floci env`

Prints AWS environment variables pointing at the running Floci instance. The default
hostname is `localhost.floci.io` (resolves to `127.0.0.1`, enables virtual-hosted S3 bucket names).

```sh
eval $(floci env)                          # bash/zsh — sets all four AWS vars
floci env --shell fish | source            # fish
floci env --shell powershell | Invoke-Expression  # PowerShell

floci env --host myhost.local              # custom hostname
floci env --region eu-west-1              # custom region (default: us-east-1)
floci env -o json                          # structured output for scripts
```

Variables exported:

| Variable | Default value |
|----------|---------------|
| `AWS_ENDPOINT_URL` | `http://localhost.floci.io:<port>` |
| `AWS_ACCESS_KEY_ID` | `test` |
| `AWS_SECRET_ACCESS_KEY` | `test` |
| `AWS_DEFAULT_REGION` | `us-east-1` |

### `floci az env`

Prints Azure connection variables for the running Floci Azure instance.

```sh
eval $(floci az env)                                # connection string (default)
eval $(floci az env --format sdk-vars)              # individual SDK endpoint vars
eval $(floci az env --format sdk-vars --service blob,queue)  # specific services only

floci az env --shell fish | source                  # fish
floci az env -o json                                # structured output
```

**Connection string mode** (default) exports:

| Variable | Value |
|----------|-------|
| `AZURE_STORAGE_CONNECTION_STRING` | Full Azurite-compatible connection string |

**SDK vars mode** (`--format sdk-vars`) exports:

| Variable | Default value |
|----------|---------------|
| `AZURE_STORAGE_ACCOUNT` | `devstoreaccount1` |
| `AZURE_STORAGE_KEY` | Azurite dev key |
| `AZURE_STORAGE_BLOB_ENDPOINT` | `http://localhost.floci.io:<port>/devstoreaccount1` |
| `AZURE_STORAGE_QUEUE_ENDPOINT` | `http://localhost.floci.io:<port>/devstoreaccount1-queue` |
| `AZURE_STORAGE_TABLE_ENDPOINT` | `http://localhost.floci.io:<port>/devstoreaccount1-table` |
| `AZURE_FUNCTIONS_ENDPOINT` | `http://localhost.floci.io:<port>/devstoreaccount1-functions` |
| `AZURE_APP_CONFIGURATION_ENDPOINT` | `http://localhost.floci.io:<port>/devstoreaccount1-appconfig` |
| `AZURE_KEY_VAULT_ENDPOINT` | `http://localhost.floci.io:<port>/devstoreaccount1-keyvault` |

### `floci logs` / `floci az logs`

```sh
floci logs                       # last logs from the container
floci logs --tail 50             # last 50 lines
floci logs --since 5m            # logs from the last 5 minutes
floci logs --follow              # stream live logs (Ctrl-C to stop)
```

### `floci wait` / `floci az wait`

```sh
floci wait                        # wait up to 30s (default)
floci wait --timeout 2m           # custom timeout (supports s, m, h)
floci wait --service dynamodb     # wait until a specific service is ready
floci wait -o json                # machine-readable output
```

### `floci doctor` / `floci az doctor`

```sh
floci doctor                      # run all checks
floci doctor --check docker.installed   # run a single check by name
floci doctor --fix                # auto-fix fixable issues
floci doctor -o json              # structured output for scripts

floci az doctor                   # Azure-specific checks (includes az CLI + connection string)
```

### `floci version` / `floci az version`

```sh
floci version                     # CLI version, server version, image digest
floci version -o json
```

### `floci services` / `floci az services`

```sh
floci services                    # list all enabled services
floci services -o json
```

### `floci config`

```sh
floci config show                          # show active configuration
floci config default-product aws|az        # set the default product (persisted to ~/.floci/config.yaml)
floci config profile list                  # list saved profiles
floci config profile create <name>         # create a new profile
floci config profile show <name>           # show a profile
floci config profile delete <name>         # delete a profile
floci config validate -f docker-compose.yml  # validate a Compose file
```

Profiles are stored in `~/.floci/profiles/<name>.yaml` and can override any global option.
Use `--profile <name>` on any command to load one.

### `floci snapshot`

Save and restore named snapshots of Floci AWS state.

```sh
floci snapshot list
floci snapshot save <name> --message "before migration"
floci snapshot load <name>
floci snapshot delete <name>
floci snapshot export <name> -o tarball.tar.gz
floci snapshot import tarball.tar.gz
```

> Azure snapshots (`floci az snapshot`) are not yet available — they require server-side endpoints not yet implemented in Floci Azure.

### `floci completion`

```sh
floci completion bash >> ~/.bashrc
floci completion zsh  >> ~/.zshrc
```

### `floci update`

Self-update the CLI in place: downloads the release binary from GitHub, verifies its
sha256 against the release's `sha256sums.txt`, and atomically replaces the running binary.

```sh
floci update                      # update to the latest release
floci update --check              # exit 0: up to date, exit 1: update available
floci update --version 0.1.7      # install a specific version
```

Homebrew installs are managed by brew and are detected and refused — use `brew upgrade floci` there instead.

When run from an interactive terminal, floci also checks for new releases in the
background (at most once per 24h, cached in `~/.floci/update-check.json`) and prints a
hint before the command output when one is available. Set `FLOCI_NO_UPDATE_CHECK=1` to
opt out; the check is automatically disabled in CI and for piped output.

---

## CI Usage

### AWS CI

```sh
floci start --detach
floci wait --timeout 60s
eval $(floci env)
pytest  # or your test command
floci stop --remove
```

With Docker Compose:

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

> Using Podman or a non-default daemon? Swap the host side of the socket mount for
> your daemon's socket (e.g. `/run/user/1000/podman/podman.sock:/var/run/docker.sock`).
> With the CLI, setting `DOCKER_HOST` is enough — see
> [Docker daemon resolution](#docker-daemon-resolution-podman-rootless-remote-contexts).

### Azure CI

```sh
floci az start --detach
floci az wait --timeout 60s
eval $(floci az env)
pytest  # or your test command
floci az stop --remove
```

With Docker Compose:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

---

## Scope

`floci-cli` manages Floci's lifecycle, config, state, and diagnostics.
It does **not** wrap the AWS CLI, Azure CLI, or manage cloud resources directly.
Use `aws` with `AWS_ENDPOINT_URL` or `az` with the appropriate connection string for resource operations.

---

## License

MIT — see [LICENSE](LICENSE).
