# CI and Testcontainers

## CLI in a pipeline (any cloud)

```sh
floci <tree> start --detach
floci <tree> wait --timeout 60s
eval "$(floci <tree> env -o text)"
# Azure control plane: run floci az setup first, then the eval line
# OCI: run floci oci setup first, then the eval line
<test command>
floci <tree> stop --remove || true   # exits 1 when the container is already gone
```

The update check is disabled automatically in CI.

Install into a directory the job can write to. The install script defaults to
`/usr/local/bin` and calls `sudo` when that is not writable, which fails on runners without
passwordless `sudo`:

```sh
curl -fsSL https://floci.io/install.sh | FLOCI_INSTALL_DIR="$HOME/.local/bin" sh
export PATH="$HOME/.local/bin:$PATH"
```

The install script does not create the directory: run `mkdir -p "$HOME/.local/bin"` first
where it may be missing. `brew install floci-io/floci/floci` also works where Homebrew is
present.

### GitHub Actions

```yaml
- name: Install floci
  run: |
    mkdir -p "$HOME/.local/bin"
    curl -fsSL https://floci.io/install.sh | FLOCI_INSTALL_DIR="$HOME/.local/bin" sh
    echo "$HOME/.local/bin" >> "$GITHUB_PATH"
- name: Start emulator
  run: |
    floci aws start --detach
    floci aws wait --timeout 90s
    floci aws env -o json | jq -r 'to_entries[] | "\(.key)=\(.value)"' >> "$GITHUB_ENV"
- run: pytest
```

`$GITHUB_ENV` wants `KEY=value` lines; `env -o json` is a flat object, so `jq` converts it.
Alternatively, run `eval "$(floci aws env -o text)"` at the top of each `run:` step.

Parallel jobs on one runner: give each job its own profile, port and namespace
(see namespaces.md), e.g. `--namespace "ci-${GITHUB_RUN_ID}-${GITHUB_JOB}"`.

## Docker Compose and service containers

When the pipeline already uses Compose, or the CI system offers service containers, run the
image directly instead of installing the CLI: see [docker.md](docker.md) and the templates in
`assets/compose/`. The images have a built-in health check, so
`docker compose up -d --wait` returns when the emulator is ready.

GitHub Actions service container (enough for in-process services; Docker-backed ones also
need the socket mounted, see docker.md):

```yaml
services:
  floci:
    image: floci/floci:latest
    ports: ["4566:4566"]
env:
  AWS_ENDPOINT_URL: http://localhost:4566
  AWS_ACCESS_KEY_ID: test
  AWS_SECRET_ACCESS_KEY: test
  AWS_DEFAULT_REGION: us-east-1
```

## Testcontainers

Prefer a module over scripting the CLI inside test code, where one exists:

- **AWS**: all five languages below (the Go module is not released yet; check its README).
- **Azure**: the Java module only.
- **GCP, OCI**: no module. Start the emulator with the CLI or Compose before the tests, or use
  the language's generic Testcontainers container with the image, port and health path from
  [docker.md](docker.md).

| Language | Module |
|----------|--------|
| Java | https://github.com/floci-io/testcontainers-floci |
| Node.js / TypeScript | https://github.com/floci-io/testcontainers-floci-node |
| Python | https://github.com/floci-io/testcontainers-floci-python |
| Go | https://github.com/floci-io/testcontainers-floci-go |
| .NET | https://github.com/floci-io/testcontainers-floci-dotnet |

Read the module's README for the current API before writing test code; do not guess class or
method names.
