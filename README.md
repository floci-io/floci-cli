# floci-cli

Official command-line interface for [Floci](https://floci.io) — the free, open-source local cloud emulator for AWS, GCP, Azure, and OCI.

```sh
# AWS (default)
floci start
eval $(floci env)
aws s3 mb s3://my-bucket

# GCP
floci gcp start
eval $(floci gcp env)
gcloud storage buckets create gs://my-bucket

# Azure
floci az start
floci az setup
eval $(floci az env)
az storage container create --name mycontainer
az group create -n my-rg -l westeurope

# OCI
floci oci start
floci oci setup
eval $(floci oci env)
oci os ns get
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

Download `floci.jar` from the [latest release](https://github.com/floci-io/floci-cli/releases/latest) and run it (requires **Java 25+**):

```sh
java -jar floci.jar version
```

### Staying up to date

Native-binary installs (install script, direct download) can self-update:

```sh
floci update --check   # exit 0: up to date, exit 1: update available
floci update           # download, verify checksum, replace the binary
```

Homebrew and Scoop installs are updated through their package manager (`brew upgrade floci`) — `floci update` detects Homebrew-managed binaries and refuses to touch them.

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

### GCP

```sh
# Start Floci GCP emulator
floci gcp start

# Check environment
floci gcp doctor

# Export GCP emulator host variables
eval $(floci gcp env)

# Use GCP services normally
gcloud storage buckets create gs://my-bucket
gcloud pubsub topics create my-topic

# Stop Floci GCP
floci gcp stop
```

### Azure

```sh
# Start Floci Azure emulator
floci az start

# Check environment
floci az doctor

# Point the az CLI at the emulator (certificate, cloud, login)
floci az setup

# Export the connection string and the az CLI config
eval $(floci az env)

# Use Azure services
az storage container create --name mycontainer
az storage blob upload --container-name mycontainer --name hello.txt --data "hello"
az group create -n my-rg -l westeurope

# Stop Floci Azure
floci az stop
```

### OCI

```sh
# Start Floci OCI emulator
floci oci start

# Check environment
floci oci doctor

# One-time: create a throwaway OCI CLI profile (API key + ~/.oci/config)
floci oci setup

# Export OCI endpoint variables
eval $(floci oci env)

# Use OCI services normally
oci os ns get
oci os bucket create --namespace-name floci-local --name my-bucket \
  --compartment-id ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

# Stop Floci OCI
floci oci stop
```

### Switch default product

Bare commands like `floci start` route to the configured default product (AWS by default).

```sh
floci config default-product gcp  # make floci gcp the default
floci config default-product az   # make floci az the default
floci config default-product oci  # make floci oci the default
floci config default-product aws  # revert to aws
```

---

## Command Reference

Commands are organized into four product groups — `floci aws` (or bare `floci`), `floci gcp`, `floci az`, and `floci oci`. All groups expose the same lifecycle commands.

### Shared commands (product-independent)

| Command | Description |
|---------|-------------|
| `floci config show` | Show active configuration |
| `floci config validate` | Validate a docker-compose.yml |
| `floci config profile` | Manage named profiles |
| `floci config default-product` | Set the default product (aws, gcp, az, or oci) |
| `floci update` | Self-update the CLI to the latest release |
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

### GCP commands (`floci gcp`)

| Command | Description |
|---------|-------------|
| `floci gcp start` | Launch the Floci GCP container |
| `floci gcp stop` | Stop (and optionally remove) the container |
| `floci gcp restart` | Stop then start |
| `floci gcp status` | Show container state and server health |
| `floci gcp logs` | Stream container logs |
| `floci gcp wait` | Poll until Floci GCP is ready (CI-friendly) |
| `floci gcp version` | Show CLI and server versions |
| `floci gcp services` | List enabled GCP services |
| `floci gcp doctor` | Run GCP environment diagnostics |
| `floci gcp env` | Print GCP SDK emulator host variables |
| `floci gcp snapshot` | Snapshot commands (coming soon) |

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
| `floci az setup` | Point the `az` CLI at the emulator: trust its certificate, register the cloud, log in |
| `floci az snapshot` | Snapshot commands (coming soon) |

### OCI commands (`floci oci`)

| Command | Description |
|---------|-------------|
| `floci oci start` | Launch the Floci OCI container |
| `floci oci stop` | Stop (and optionally remove) the container |
| `floci oci restart` | Stop then start |
| `floci oci status` | Show container state and server health |
| `floci oci logs` | Stream container logs |
| `floci oci wait` | Poll until Floci OCI is ready (CI-friendly) |
| `floci oci version` | Show CLI and server versions |
| `floci oci services` | List enabled OCI services |
| `floci oci doctor` | Run OCI environment diagnostics |
| `floci oci env` | Print OCI endpoint variables |
| `floci oci setup` | Create a local OCI CLI profile (API key + `~/.oci/config`) |
| `floci oci snapshot` | Snapshot commands (coming soon) |

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

Explicit flags override the profile; the profile overrides the environment variables.

### GCP global flags

```
--endpoint <url>            Floci GCP server URL    (default: http://localhost:4588, env: FLOCI_GCP_ENDPOINT)
--container <name>          Container name          (default: floci-gcp, env: FLOCI_GCP_CONTAINER)
--output|-o text|json|yaml  Output format           (default: text)
--quiet, -q                 Suppress non-error output
--verbose, -v               Debug logging to stderr
--no-color                  Disable ANSI colors
--profile <name>            Load settings from ~/.floci/profiles/<name>.yaml
```

Explicit flags override the profile; the profile overrides the environment variables.

### Azure global flags

```
--endpoint <url>            Floci Azure server URL  (default: http://localhost:4577, env: FLOCI_AZ_ENDPOINT)
--container <name>          Container name          (default: floci-az, env: FLOCI_AZ_CONTAINER)
--output|-o text|json|yaml  Output format           (default: text)
--quiet, -q                 Suppress non-error output
--verbose, -v               Debug logging to stderr
--no-color                  Disable ANSI colors
--profile <name>            Load settings from ~/.floci/profiles/<name>.yaml
```

Explicit flags override the profile; the profile overrides the environment variables.

### OCI global flags

```
--endpoint <url>            Floci OCI server URL    (default: http://localhost:4599, env: FLOCI_OCI_ENDPOINT)
--container <name>          Container name          (default: floci-oci, env: FLOCI_OCI_CONTAINER)
--output|-o text|json|yaml  Output format           (default: text)
--quiet, -q                 Suppress non-error output
--verbose, -v               Debug logging to stderr
--no-color                  Disable ANSI colors
--profile <name>            Load settings from ~/.floci/profiles/<name>.yaml
```

Explicit flags override the profile; the profile overrides the environment variables.

> **Port auto-detection** — `status`, `version`, `wait`, and `env` automatically derive the correct
> endpoint from the container's port mapping. You don't need to pass `--endpoint` when using
> a non-default port, as long as `--container` points to the right container.

---

## Commands

### `floci start` / `floci gcp start` / `floci az start` / `floci oci start`

Pulls the image (if needed), starts the container, and waits for readiness.

```sh
# AWS
floci start                          # default port 4566
floci start --port 4567              # custom host port
floci start --services s3,dynamodb   # enable specific services
floci start --persist ./data         # persist state to a host directory
floci start --pull always            # always pull the latest image
floci start --detach                 # return immediately, don't wait
floci start --namespace team-b       # scope the containers it launches (see below)

# GCP
floci gcp start                      # default port 4588
floci gcp start --persist ./data     # persist state to a host directory
floci gcp start --namespace team-b   # scope the containers it launches

# Azure
floci az start                       # default port 4577
floci az start --port 4578           # custom host port
floci az start --persist ./data      # persist state to a host directory
floci az start --namespace team-b    # scope the containers it launches

# OCI
floci oci start                      # default port 4599
floci oci start --persist ./data     # persist state to a host directory
floci oci start --namespace team-b   # scope the containers it launches
```

`--namespace` sets the emulator's Docker resource namespace (`FLOCI_DOCKER_RESOURCE_NAMESPACE`,
`FLOCI_GCP_…`, `FLOCI_AZ_…`, `FLOCI_OCI_…`). The emulator puts it in the names and the
`floci_namespace` label of the containers and volumes it launches (Lambda, ECS, RDS, Cloud Run,
Service Bus, ...) and only cleans up leftovers in its own namespace. When `--container` is not the
product default, the namespace is derived from it, without the default-container prefix the
emulator already adds (`floci-gcp-b` → `b`, so children are `floci-gcp-b-…`; `team-b` stays
`team-b`). The default container gets none.

On a `--port` other than the product default, `start` also sets the emulator's base URL
(`FLOCI_BASE_URL`, `FLOCI_GCP_BASE_URL`, ...) to `http://localhost:<port>`, so URLs it returns
(SQS `QueueUrl`, presigned URLs, the OCI functions invoke endpoint) point at that instance.

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

### `floci stop` / `floci gcp stop` / `floci az stop` / `floci oci stop`

```sh
floci stop                    # graceful stop (10s timeout)
floci stop --timeout 30       # wait up to 30s before force-kill
floci stop --remove           # also remove the container after stopping
```

### `floci status` / `floci gcp status` / `floci az status` / `floci oci status`

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

### `floci gcp env`

Prints GCP SDK emulator host variables for the running Floci GCP instance.

```sh
eval $(floci gcp env)                        # all emulator host vars
eval $(floci gcp env --service gcs,pubsub)   # specific services only

floci gcp env --shell fish | source          # fish
floci gcp env -o json                        # structured output
```

Variables exported (per enabled service):

| Variable | Service |
|----------|---------|
| `STORAGE_EMULATOR_HOST` | Cloud Storage (gcs) |
| `PUBSUB_EMULATOR_HOST` | Pub/Sub |
| `FIRESTORE_EMULATOR_HOST` | Firestore |
| `DATASTORE_EMULATOR_HOST` | Datastore |
| `SECRET_MANAGER_EMULATOR_HOST` | Secret Manager |
| `IAM_EMULATOR_HOST` | IAM |

### `floci az env`

Prints Azure connection variables for the running Floci Azure instance.
The connection string contains `;` separators, so the emitted value is single-quoted (as it is in every tree's `env` output) and is safe to `eval`.

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

**After `floci az setup`**, both modes also export:

| Variable | Value |
|----------|-------|
| `AZURE_CONFIG_DIR` | `~/.floci/az/<container>/azure-config`, the instance's isolated az config; after `--global` it is unset instead, so a value left by another instance's `eval` does not linger |
| `REQUESTS_CA_BUNDLE` | `~/.floci/az/<container>/ca-bundle.pem`, the public roots plus that instance's certificate |

If the instance's certificate changed since setup (a container recreated without `--persist`),
`floci az env` warns on stderr and tells you to re-run `floci az setup`.

### `floci az setup`

`az group create`, `az login` and every other control-plane command go to public Azure
unless the `az` CLI is told otherwise. `floci az setup` does it in one step:

1. fetches the emulator's CA certificate from `/_floci/tls-cert` and writes a CA bundle
   (the JDK's public roots plus that certificate);
2. registers (or updates) an az cloud named after the container (`floci-az` by default) whose ARM and Entra endpoints point at
   `https://localhost:<port>`, and makes it the active cloud;
3. turns off `core.instance_discovery`, which otherwise fails the login with `invalid_instance`;
4. logs in as a service principal with the dev identity (tenant
   `00000000-0000-0000-0000-000000000002`, subscription `00000000-0000-0000-0000-000000000001`).

Everything is kept per instance, in `~/.floci/az/<container>/`, so instances started from
different profiles never share a certificate, cloud or login (see
[Running several instances side by side](#running-several-instances-side-by-side)). By default the
az config itself is isolated there too and `~/.azure` is left untouched; `eval $(floci az env)`
then points `az` at it in the current shell only. `--global` writes to your default az config
instead, and removes that instance's earlier isolated config. Re-running is safe: the cloud is updated, not registered twice.

The az CLI only logs in over HTTPS, so `floci az start` always starts Floci Azure with
`FLOCI_AZ_TLS_ENABLED=true` (HTTP keeps working on the same port). A container started by an
older floci has TLS off; run `floci az restart` once.

```sh
floci az setup                          # isolated config under ~/.floci/az/floci-az
eval $(floci az env)                    # AZURE_CONFIG_DIR + REQUESTS_CA_BUNDLE + connection string
az group create -n my-rg -l westeurope

floci az setup --profile team-b         # a second instance: ~/.floci/az/<its container>
eval $(floci az env --profile team-b)

floci az setup --global                 # use your default az config (~/.azure)
eval $(floci az env)                    # REQUESTS_CA_BUNDLE, and clears a stale AZURE_CONFIG_DIR

floci az setup --reset                  # delete the isolated config
floci az setup --reset --global         # switch the default az config back to AzureCloud
floci az setup -o json                  # structured output
```

Requires the `az` CLI on your `PATH`. `--tenant`, `--subscription`, `--client-id` and
`--client-secret` override the dev identity; Floci Azure never checks the secret.

### `floci oci env`

Prints OCI endpoint variables for the running Floci OCI instance. OCI SDKs and the
`oci` CLI take a single endpoint for every service, so no per-service variables are needed.

```sh
eval $(floci oci env)                        # endpoint vars for CLI, wrappers, and Terraform

floci oci env --shell fish | source          # fish
floci oci env -o json                        # structured output
```

Variables exported:

| Variable | Purpose |
|----------|---------|
| `OCI_CLI_ENDPOINT` | Default `--endpoint` for the `oci` CLI |
| `FLOCI_OCI_ENDPOINT` | Used by this CLI and the `ocilocal` wrapper |
| `TF_VAR_CLIENT_HOST_OVERRIDES` | Per-client host overrides for the `oracle/oci` Terraform provider |
| `OCI_CLI_PROFILE` | The profile `floci oci setup` wrote (default `FLOCI`) — only exported when detected in `~/.oci/config`, and skipped for `DEFAULT` |

### `floci oci setup`

The OCI CLI and SDKs refuse to run without a config file and an API signing key — even
against an emulator that never validates them. `floci oci setup` creates both in one step:
a locally generated RSA-2048 key at `~/.oci/floci_key.pem` and a `[FLOCI]` profile in
`~/.oci/config` with the emulator's canonical throwaway tenancy. Existing profiles are
never touched; re-running is a no-op.

```sh
floci oci setup                        # create key + [FLOCI] profile
floci oci setup --profile-name DEFAULT # write a different profile section
floci oci setup -o json                # structured output

# then connect:
eval $(floci oci env)                  # exports OCI_CLI_PROFILE=FLOCI too
oci os ns get
```

### `floci logs` / `floci gcp logs` / `floci az logs` / `floci oci logs`

```sh
floci logs                       # last logs from the container
floci logs --tail 50             # last 50 lines
floci logs --since 5m            # logs from the last 5 minutes
floci logs --follow              # stream live logs (Ctrl-C to stop)
```

### `floci wait` / `floci gcp wait` / `floci az wait` / `floci oci wait`

```sh
floci wait                        # wait up to 30s (default)
floci wait --timeout 2m           # custom timeout (supports s, m, h)
floci wait --service dynamodb     # wait until a specific service is ready
floci wait -o json                # machine-readable output
```

### `floci doctor` / `floci gcp doctor` / `floci az doctor` / `floci oci doctor`

```sh
floci doctor                      # run all checks
floci doctor --check docker.installed   # run a single check by name
floci doctor --fix                # auto-fix fixable issues
floci doctor -o json              # structured output for scripts

floci gcp doctor                  # GCP-specific checks
floci az doctor                   # Azure-specific checks (includes az CLI + connection string)
floci oci doctor                  # OCI-specific checks
```

### `floci version` / `floci gcp version` / `floci az version` / `floci oci version`

```sh
floci version                     # CLI version, server version, image digest
floci version -o json
```

### `floci services` / `floci gcp services` / `floci az services` / `floci oci services`

```sh
floci services                    # list all enabled services
floci services -o json
```

### `floci config`

```sh
floci config show                          # show active configuration
floci config default-product aws|gcp|az|oci  # set the default product (persisted to ~/.floci/config.yaml)
floci config profile list                  # list saved profiles: container, port, data dir
floci config profile create <name>         # create a new profile with the tree's defaults
floci config profile create <name> --container <c> --port <p> --persist <dir> --services <csv> --image <img> --namespace <ns>
floci config profile show <name>           # show a profile
floci config profile delete <name>         # delete a profile
floci config validate -f docker-compose.yml  # validate a Compose file
```

#### Profiles

Profiles are stored in `~/.floci/profiles/<name>.yaml`. Pass `--profile <name>` to any command to
load one; `floci config profile create <name>` writes a starter file seeded with the defaults of
the tree you run it under.

```yaml
name: probe
endpoint: http://localhost:4566
container: floci-probe
image: floci/floci:enforced
port: 4599
persistDir: /tmp/floci-persist
services: s3,lambda
namespace: team-probe
output: json
```

Each field supplies the default for one flag, so a profile field only affects the commands that
have that flag:

| Profile field | Flag it supplies | Applies to |
|---|---|---|
| `endpoint` | `--endpoint` | every command |
| `container` | `--container` | every command |
| `output` | `--output` / `-o` | every command |
| `image` | `--image` | `start`, `restart` |
| `port` | `--port` | `start`, `restart` |
| `persistDir` | `--persist` | `start`, `restart` |
| `services` | `--services` | `start`, `restart` |
| `namespace` | `--namespace` | `start`, `restart` |

Resolution order, highest first:

```
command-line flag  >  --profile <name>  >  FLOCI_* environment variable  >  built-in default
```

So `floci start --profile probe --container other` starts `other`, and a profile beats a
`FLOCI_CONTAINER` exported in your shell. A field the profile leaves out changes nothing.

An unknown or unreadable profile is an error (exit 2), not a silent fall back to the defaults.
Values are interpolated by the CLI, so `persistDir: ${env:HOME}/floci-data` expands as you would
expect.

> Profiles are shared by all four product trees — there is one `~/.floci/profiles/` directory, not
> one per product. A profile created under `floci gcp` carries GCP defaults, so passing it to the
> AWS tree will start a GCP container; name them accordingly.

> `floci restart --profile <name>` re-applies the profile's `persistDir`, so state survives the
> restart. A plain `floci restart` has no `--persist` flag of its own and still falls back to the
> defaults.

#### Running several instances side by side

An instance is a profile. Its **container name** is its identity, its **port** is the only thing
that must differ from the other instances, and its **persist dir** keeps its state across
container recreation. Every command (`status`, `logs`, `env`, `doctor`, `stop`, ...) finds the
instance through its container, so passing the same `--profile` is all it takes. Any local state
the CLI keeps about an instance lives in `~/.floci/<product>/<container>/`.

Each instance's emulator also gets its own Docker resource namespace, derived from the container
name unless the profile sets `namespace`. That keeps the containers each instance launches (Lambda functions,
ECS tasks, Cloud Run jobs, Service Bus sidecars, ...) apart, so starting one instance never
cleans up another's. `floci config show --profile <name>` prints the namespace it will use.

Leave `endpoint` at the product default: the CLI reads the real host port from the running
container. `config profile create` warns when a new profile reuses another profile's container,
or its port or resource namespace on the same image.

```sh
# AWS
floci config profile create aws-a --container floci-a --port 4566  --persist ~/.floci/data/aws-a
floci config profile create aws-b --container floci-b --port 14566 --persist ~/.floci/data/aws-b
floci start --profile aws-a && floci start --profile aws-b
eval $(floci env --profile aws-b)          # AWS_ENDPOINT_URL points at :14566

# GCP
floci gcp config profile create gcp-b --container floci-gcp-b --port 14588 --persist ~/.floci/data/gcp-b
floci gcp start --profile gcp-b
eval $(floci gcp env --profile gcp-b)

# Azure: setup is per instance too (its own certificate, az cloud and login)
floci az config profile create az-b --container floci-az-b --port 14577 --persist ~/.floci/data/az-b
floci az start --profile az-b
floci az setup --profile az-b
eval $(floci az env --profile az-b)        # plain az now talks to floci-az-b only

# OCI: 'floci oci setup' is shared, the endpoint is per instance
floci oci config profile create oci-b --container floci-oci-b --port 14599 --persist ~/.floci/data/oci-b
floci oci start --profile oci-b
eval $(floci oci env --profile oci-b)

floci config profile list                  # one row per instance
floci stop --profile aws-b                 # stop one, the others keep running
```

Without `--persist` an instance starts empty each time its container is recreated. For floci-az
it also gets a new certificate, which `floci az env` reports so you can re-run `floci az setup`.

### `floci snapshot`

Save and restore named snapshots of Floci AWS state.

```sh
floci snapshot list
floci snapshot save <name>
floci snapshot load <name>
floci snapshot delete <name>
floci snapshot export <name> -f tarball.tar.gz
floci snapshot import tarball.tar.gz
```

> GCP, Azure, and OCI snapshots (`floci gcp snapshot` / `floci az snapshot` / `floci oci snapshot`) require server-side endpoints not yet implemented in Floci GCP / Floci Azure / Floci OCI — until then those commands report the missing API and exit 1. `floci snapshot export|import` (AWS) are also pending server support and exit 1.

### `floci update`

Self-updates a native-binary install to a newer release (checksum-verified, atomic replace).

```sh
floci update                      # update to the latest release
floci update --check              # only report; exit 0 = up to date, 1 = update available
floci update --version 0.1.8      # pin a specific version
```

```sh
floci update --check || floci update   # script-friendly: update only when stale
```

Homebrew-managed installs are refused — use `brew upgrade floci` instead.

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

### GCP CI

```sh
floci gcp start --detach
floci gcp wait --timeout 60s
eval $(floci gcp env)
pytest  # or your test command
floci gcp stop --remove
```

With Docker Compose:

```yaml
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

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

### OCI CI

```sh
floci oci start --detach
floci oci wait --timeout 60s
eval $(floci oci env)
pytest  # or your test command
floci oci stop --remove
```

With Docker Compose:

```yaml
services:
  floci-oci:
    image: floci/floci-oci:latest
    ports:
      - "4599:4599"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

---

## Scope

`floci-cli` manages Floci's lifecycle, config, state, and diagnostics.
It does **not** wrap the AWS, GCP, Azure, or OCI CLIs, or manage cloud resources directly.
Use `aws` with `AWS_ENDPOINT_URL`, `gcloud`/SDKs with the emulator host variables, `az` with the appropriate connection string, or `oci` with `OCI_CLI_ENDPOINT` for resource operations.

---

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test, and submit changes.

---

## License

MIT — see [LICENSE](LICENSE).
