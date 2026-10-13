# Troubleshooting

Start every investigation with:

```sh
floci <tree> doctor -o json
floci <tree> status -o json
floci <tree> logs --since 5m
```

`doctor -o json` is an array of `{name, status, message, fix}`; `status` is `ok`, `warn` or
`fail`, and the exit code is 1 only when a check fails. `doctor` changes nothing itself. Read
a `fix` critically and tell the user what it changes before applying it:

- `image.version` on GCP, Azure and OCI may warn that the server is "below minimum 1.5.0".
  That number is the AWS emulator's; ignore the warning when the image is current.
- `aws.cli.endpoint` and `az.cli.connection-string` warn whenever `doctor` runs in a shell
  that has not loaded `env`. Run both in one command line to check the wiring.
- `aws.cli.s3.pathstyle` suggests editing `~/.aws/config`. Do not: with the default
  `localhost.floci.io` endpoint S3 works without it, and the user's config is off limits.
- Fix texts may name bare `floci start`; use the explicit tree and the instance's `--profile`.
- `doctor` does not check whether `floci az setup` or `floci oci setup` was run.

## By symptom

**`start` fails or the port is in use**
Another process or Floci instance holds the port. `docker ps --filter publish=<port>`. Use a
profile with a different `--port` rather than stopping containers you did not start.

**`wait` times out**
Read `logs`. First pulls of the image and of child images (Lambda runtimes, postgres, k3s) can
be slow; raise `--timeout`. `wait --service <name>` waits for one service on AWS, GCP and
OCI; an unknown name, or any name on Azure, just times out.

**Requests reach real cloud**
Most common cause is a fresh shell without the `eval`. Re-run the `env` command in the same
command line. A profile with `output: json` makes `env` print JSON, which `eval` cannot use:
pass `-o text`. Then check the cloud-specific leaks: SQS endpoint bypass (AWS), gcloud ignoring
`*_EMULATOR_HOST` (GCP), `az` without `AZURE_CONFIG_DIR` (Azure), `DEFAULT` OCI profile (OCI).

**Docker-backed services fail (Lambda, ECS, RDS, Cloud Run, AKS, Functions, OKE)**
The emulator cannot reach a Docker daemon. floci-cli resolves `DOCKER_HOST`, then `DOCKER_SOCK`,
then the OS default socket. For Podman: `export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock`.
`doctor` shows the resolved endpoint. In Compose, mount the socket explicitly.

**Child containers vanish or another instance's tasks get killed**
Two instances share a Docker host without distinct namespaces. See namespaces.md.

**Azure: TLS, `invalid_instance`, or login errors**
Re-run `floci az setup`, then `eval "$(floci az env -o text)"`. If the container predates TLS
support, `floci az restart` once. A container recreated without `--persist` (by `start` or
`restart`) has a new certificate. An instance started by Compose or `docker run` needs
`FLOCI_AZ_TLS_ENABLED=true`.

**OCI: CLI complains about config or key**
Run `floci oci setup`, then `eval "$(floci oci env -o text)"`.

**Returned URLs point at the wrong host or port** (SQS `QueueUrl`, presigned URLs)
Set the base URL: floci-cli does it on `start --port`; in Compose set `FLOCI_BASE_URL` (or
`FLOCI_GCP_BASE_URL`, etc.), or `FLOCI_HOSTNAME=<service name>` for container-to-container calls.

**State disappeared**
The container was recreated without `--persist`: `start` removes a stopped container of the
same name before recreating it, and `restart` always removes and recreates. `restart` keeps the
container's settings, persist dir included, so it only preserves state for an instance that
has one. Use a profile with `persistDir`. In Compose, `docker compose down -v` deletes the
data volume.

**A profile's commands hit the default instance**
Its container is not running, so the CLI fell back to the default port (`status` then shows
`"state": "not found"` next to `"reachable": true`). Start the profile's instance first.

**Profile errors (exit 2)**
Unknown or unreadable profile. `floci <tree> config profile list`. Profiles are shared across trees: a
profile created under `floci gcp` carries GCP defaults even if passed to `floci aws`.

**An operation returns not-implemented or a canned response**
The service is partial or a stub. Read its page in the published docs (see docs.md), which
lists supported operations and gaps. Report it instead of retrying.

## Exit codes worth knowing

- `0` success; non-zero failure for every command.
- `2` usage or validation error: bad profile, duration, namespace, port, `--pull` or `--shell`.
- `1` from every snapshot subcommand (`save`, `load`, `list`, `delete`, `export`, `import`) in
  every tree: not available server-side yet. A name that is a path (`.`, `..`, or with `/`) is
  `2`, and a bare `floci <tree> snapshot` just prints its usage and exits `0`.
- `1` from `floci <tree> stop` when the container does not exist.
- `floci update --check`: `0` up to date, `1` update available, `2` the check failed.

The full table is in the floci-cli README ("Exit Codes").
