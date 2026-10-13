# Docker and Docker Compose

The emulators are ordinary container images. Use this when the user asks for a `docker run`
command or a Compose file, when the app itself runs in a container, or when floci-cli is not
wanted. Templates to copy are in `assets/compose/`: `aws.yml`, `gcp.yml`, `azure.yml`,
`oci.yml` and `multi-cloud.yml`.

## Facts per image

| | AWS | GCP | Azure | OCI |
|--|-----|-----|-------|-----|
| Image | `floci/floci` | `floci/floci-gcp` | `floci/floci-az` | `floci/floci-oci` |
| Port | 4566 | 4588 | 4577 | 4599 |
| Env prefix | `FLOCI_` | `FLOCI_GCP_` | `FLOCI_AZ_` | `FLOCI_OCI_` |
| Health URL | `/_floci/health` | `/_floci-gcp/health` | `/_floci/health` | `/_floci-oci/health` |
| Service name used in the templates | `floci` | `floci-gcp` | `floci-az` | `floci-oci` |

Common to all four:

- **Health check is built into the image.** Do not add a `healthcheck:` that calls `curl` or
  `wget`: the images contain neither, so it would never pass. `depends_on` with
  `condition: service_healthy` and `docker compose up --wait` work as they are.
- **Data directory is `/app/data`** and the persistent path already points there. State is
  kept only when a volume is mounted there **and** `<PREFIX>STORAGE_MODE` is set
  (`persistent`; `hybrid` and `wal` also exist, see the storage page of the docs).
- **Docker socket**: mount `/var/run/docker.sock` when a service that runs real containers is
  used (Lambda, ECS, RDS, Cloud Run, Cloud SQL, GKE, Azure Functions, AKS, OCI Functions, OKE
  and others). In-process services work without it.
- **Tags**: `latest` for local work; pin a release tag in CI. `latest-compat` variants include
  the vendor CLI inside the image (useful for init scripts); see the docs before relying on
  what they contain.
- **Fake credentials only**, the same ones `floci <tree> env` exports.
- **Publish the port on loopback** (`127.0.0.1:4566:4566`, as the templates and the commands
  below do). The emulator accepts the fake credentials from anyone who can reach the port,
  so a plain `4566:4566` exposes the user's local resources to the network. Containers on
  the same Compose network do not use the published port. Drop the `127.0.0.1:` only when
  the user asks for the emulator to be reachable from another machine, and say what it
  opens.

Variables you will actually set (`<P>` is the env prefix):

| Variable | When |
|----------|------|
| `<P>HOSTNAME` | Another container calls the emulator: set it to the Compose service name so returned URLs (SQS `QueueUrl`, object URLs, invoke endpoints) resolve from that container. Leave unset when only the host calls it. |
| `<P>BASE_URL` | The host port differs from the default (`http://localhost:<port>`), or GCP in Compose (`http://floci-gcp:4588`). |
| `<P>STORAGE_MODE` | With a volume at `/app/data`, to keep state. |
| `<P>DOCKER_RESOURCE_NAMESPACE` | More than one emulator instance on the same Docker host (see [namespaces.md](namespaces.md)). floci-cli sets it for its own instances; in Compose you set it. |
| `<P>SERVICES_DOCKER_NETWORK` (AWS, GCP, OCI) | Child containers must be reachable by name from the app: set it to the Compose network name. |
| `<P>SERVICES_<NAME>_ENABLED=false` | Switch one service off. Everything is on by default. |
| `FLOCI_AZ_TLS_ENABLED=true` | Azure, whenever the `az` CLI or `floci az setup` is used (they need HTTPS). HTTP keeps working on the same port. |

For anything else, read the environment variable page of that emulator (see
[docs.md](docs.md)); do not invent variable names.

## docker run

```sh
# AWS, with child-container support
docker run -d --name floci -p 127.0.0.1:4566:4566 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  floci/floci:latest

# Same, keeping state in ./floci-data
docker run -d --name floci -p 127.0.0.1:4566:4566 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD/floci-data:/app/data" -e FLOCI_STORAGE_MODE=persistent \
  floci/floci:latest

# A second instance on another host port
docker run -d --name floci-b -p 127.0.0.1:14566:4566 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e FLOCI_BASE_URL=http://localhost:14566 \
  -e FLOCI_DOCKER_RESOURCE_NAMESPACE=b \
  floci/floci:latest
```

For the other clouds change the image, the port and the prefix
(`floci/floci-gcp` 4588 `FLOCI_GCP_`, `floci/floci-az` 4577 `FLOCI_AZ_`,
`floci/floci-oci` 4599 `FLOCI_OCI_`). Azure for the `az` CLI also needs
`-e FLOCI_AZ_TLS_ENABLED=true`.

Wait for readiness without the CLI:

```sh
until [ "$(docker inspect -f '{{.State.Health.Status}}' floci)" = healthy ]; do sleep 1; done
```

`floci <tree> start` builds this same command, except that it publishes the port on every
interface. Prefer it when the CLI is installed: it also resolves the Docker socket (Podman,
remote daemons), derives the namespace and waits.

## Writing a Compose file

1. **Look first.** If the project already has `docker-compose.yml`, `compose.yaml` or an
   override file, add a service to it and show the diff. Never overwrite an existing file, and
   keep the file name and style the project uses.
2. **Copy the template** for the cloud from `assets/compose/` and trim it:
   - no app container calling the emulator: drop the `app` service and the `HOSTNAME` line;
   - only in-process services: the socket mount may go, together with the
     `SERVICES_DOCKER_NETWORK` line and the `networks:` block that only serve child
     containers. `config validate` then reports the missing mount: an error with exit 1 for
     AWS, a warning for the others. That is expected; do not gate CI on it for such a file;
   - no need to keep state: drop the data volume and `STORAGE_MODE` together;
   - the namespace line may go only when this is the single emulator instance of that cloud
     on the Docker host (other clouds do not count; each has its own name prefix).

   `myapp` and `myapp-net` are placeholders. Replace them with a name of this project, and
   when another project on the same host already uses the default `container_name`, rename
   that too: container name, namespace and network name change together.
3. **Wire the app** with the service name as host, never `localhost`: inside a container,
   `localhost` is the container itself. Use the fake credentials from the template.
4. **Several clouds**: start from `multi-cloud.yml` and delete the services not needed. Each
   emulator keeps its own port, so no remapping is required. That file is the minimal form;
   copy the rest from the per-cloud templates as needed:
   - persistence: the `STORAGE_MODE` line and the data volume;
   - an app service: the `app` block;
   - an app that reaches child containers by name (RDS, ElastiCache, Cloud SQL, OKE, ...):
     the `SERVICES_DOCKER_NETWORK` line of each AWS, GCP and OCI emulator and the top-level
     `networks:` block, all with the same network name. Without them the child containers
     start on Docker's default bridge, where the app cannot resolve them.
5. **Validate**: `docker compose -f <file> config -q`, and when floci-cli is installed,
   `floci <tree> config validate -f <file>` (checks image, port mapping, socket and
   persistence; it looks at one emulator service per run).
6. **Run**: `docker compose up -d --wait`, then check the health URL from the host, e.g.
   `curl -fsS http://localhost:4566/_floci/health`.

`HOSTNAME` is a trade-off: with it set, returned URLs (for example an SQS `QueueUrl` of
`http://floci:4566/...`) resolve from other containers but not from the host. Host tools can
still call the emulator on `localhost`; they just cannot follow those returned URLs. Pick the
side that consumes them, which is normally the app container.

Ask before running `docker compose down -v`: `-v` deletes the data volume. Plain
`docker compose down` keeps it.

### Per-cloud wiring inside Compose

- **AWS**: `AWS_ENDPOINT_URL=http://floci:4566`, `AWS_ACCESS_KEY_ID=test`,
  `AWS_SECRET_ACCESS_KEY=test`, `AWS_DEFAULT_REGION=us-east-1`. S3 clients need path-style
  addressing with a plain service-name host; it is set in code, not by an env var (see
  aws.md). ElastiCache and RDS proxy extra host port ranges;
  take them from `https://floci.io/floci/configuration/docker-compose/` when those services
  are used.
- **GCP**: `STORAGE_EMULATOR_HOST=http://floci-gcp:4588` (with scheme) and
  `PUBSUB_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST`, `DATASTORE_EMULATOR_HOST`,
  `SECRET_MANAGER_EMULATOR_HOST` set to `floci-gcp:4588` (no scheme). Set both
  `FLOCI_GCP_HOSTNAME` and `FLOCI_GCP_BASE_URL` on the emulator.
- **Azure**: a connection string with the service name in each endpoint (see `azure.yml`).
  The control plane (`az`, ARM, Terraform) needs TLS on and the CA trusted: from the host run
  `floci az setup --container <container name>`; inside another container, read
  `https://floci.io/floci-az/getting-started/azure-setup/`.
- **OCI**: the endpoint is `http://floci-oci:4599` for every service, but the SDK and CLI
  also need a config file and a signing key in the app container (any key works). Read
  `https://floci.io/floci-oci/getting-started/oci-setup/` and mount the throwaway config.

Init scripts that create resources at startup are supported by the AWS and OCI images through
mounted hook directories; read the "initialization hooks" page of that emulator before using
them. For the others, create resources from the app or a one-shot service that depends on the
emulator being healthy.

## Using floci-cli with a Compose-run emulator

The CLI finds an instance by container name. The templates set `container_name` to the CLI's
default (`floci`, `floci-gcp`, `floci-az`, `floci-oci`), so these work unchanged:

```sh
floci aws status -o json
floci aws wait --timeout 2m
eval "$(floci aws env -o text)"
floci aws logs --tail 100
floci aws doctor -o json
```

With another container name (two projects on one host cannot share a `container_name`), pass
`--container <name>` to each command, or create a profile once:
`floci aws config profile create myapp --container myapp-floci --port 4566`.

Do not run `floci <tree> start`, `restart` or `stop --remove` against a Compose-managed
container: the CLI would replace or delete it behind Compose's back. Use `docker compose`.

## Podman and other daemons

Mount the daemon's own socket on the left side and keep `/var/run/docker.sock` on the right,
for example `/run/user/1000/podman/podman.sock:/var/run/docker.sock` for rootless Podman.
