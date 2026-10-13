# Floci container namespaces

Many Floci services run real Docker containers next to the emulator: Lambda functions, ECS
tasks, RDS/ElastiCache/OpenSearch (AWS); Cloud Run, GKE, Cloud SQL (GCP); Functions, AKS,
Service Bus sidecars, PostgreSQL/MySQL (Azure); Functions and OKE sidecars (OCI). The Docker
resource namespace scopes those child containers and volumes to one emulator instance.

## What the namespace does

- It is inserted into the names of every child container and volume the emulator creates
  (`floci-gcp-<ns>-...`, `floci-az-<ns>-...`, `floci-oci-<ns>-...`; for AWS Lambda,
  `<prefix>-<ns>-...`).
- It is stamped as the `floci_namespace` label on each of them.
- Startup cleanup of leftovers only touches containers in the emulator's own namespace, so one
  instance never deletes another instance's children.

Without a namespace, two instances on the same Docker host can clean up each other's
containers. A known AWS case: two floci instances sharing a daemon with the same API port and
no namespace carry the same ECS owner label and remove each other's task containers.

## Setting it

| Way | AWS | GCP | Azure | OCI |
|-----|-----|-----|-------|-----|
| CLI flag | `floci aws start --namespace <ns>` | `floci gcp start --namespace <ns>` | `floci az start --namespace <ns>` | `floci oci start --namespace <ns>` |
| Profile field | `namespace: <ns>` | same | same | same |
| Emulator env var (Compose) | `FLOCI_DOCKER_RESOURCE_NAMESPACE` | `FLOCI_GCP_DOCKER_RESOURCE_NAMESPACE` | `FLOCI_AZ_DOCKER_RESOURCE_NAMESPACE` | `FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE` |

Precedence for CLI-started instances: `--namespace` flag > profile `namespace` > derived from
the effective container name (which `FLOCI_CONTAINER`, `FLOCI_GCP_CONTAINER`,
`FLOCI_AZ_CONTAINER` or `FLOCI_OCI_CONTAINER` can set). The
`*_DOCKER_RESOURCE_NAMESPACE` variables in your shell are not read by floci-cli; they only apply
when you run the image directly (Compose, `docker run`).

## How floci-cli derives it automatically

- **Default container** (`floci`, `floci-gcp`, `floci-az`, `floci-oci`): no namespace.
- **Any other container name**: the namespace is derived from it, minus the default-container
  prefix the emulator already adds. `floci-gcp-b` becomes `b`, so children are
  `floci-gcp-b-...`. A name without that prefix, like `team-b`, stays `team-b`.
- An explicit `--namespace` or profile `namespace` always wins.

Check before starting: `floci <tree> config show --profile <name>` prints the namespace it
will use. The row is absent without a profile, and for a profile on the default container
(which gets no namespace). `floci <tree> config profile create` warns when a new profile
reuses another profile's container, port or namespace on the same image.

## Labels for discovery

Every container and volume a Floci emulator creates carries (one exception: OCI function
containers are started by the `fnserver` sidecar and carry no labels):

| Label | Value |
|-------|-------|
| `floci` | `true` (all emulators) |
| `floci_emulator` | `floci-aws`, `floci-gcp`, `floci-az` or `floci-oci` |
| `floci_namespace` | the namespace, only when one is set |

Containers backing an emulated resource also carry `io.floci` (the cloud, e.g. `aws`),
`io.floci.service` (e.g. `rds`), `io.floci.resource-id` (e.g. the DB instance id), plus scope
keys (`io.floci.account`, `io.floci.region` on AWS; GCP, Azure and OCI use their own scope keys).

```sh
# Everything Floci created on this host
docker ps -a --filter label=floci=true

# Only one instance's children
docker ps -a --filter label=floci_namespace=team-b

# Only GCP children of that instance
docker ps -a --filter label=floci_emulator=floci-gcp --filter label=floci_namespace=team-b

# The container behind a specific emulated resource
docker ps --filter label=io.floci.resource-id=orders-db-primary
```

## Cleanup

Prefer letting the emulator do it. On a graceful stop some services stop their children (ECS
tasks, Cloud Run jobs); others may leave them running until the next start of the same
instance sweeps leftovers in its namespace. `floci <tree> stop` force-kills after 10s, which
can cut a graceful shutdown short: use `stop --timeout 30` when many children are running.
Manual cleanup is a last resort. Always scope it
to a namespace and emulator, show the user the list first, and get confirmation:

```sh
docker ps -a --filter label=floci_namespace=team-b --filter label=floci_emulator=floci-aws
# after the user confirms:
docker rm -f $(docker ps -aq --filter label=floci_namespace=team-b --filter label=floci_emulator=floci-aws)
docker volume ls -q --filter label=floci_namespace=team-b
```

Never filter only on `label=floci=true` for removal: that matches every instance on the host.
User-supplied labels can not override the reserved keys (`floci`, `floci_emulator`,
`floci_namespace`, `io.floci.*`); the emulator ignores such entries with a warning.

## Recipes

### Two AWS instances side by side

```sh
floci aws config profile create aws-a --container floci-a --port 4566  --persist ~/.floci/data/aws-a
floci aws config profile create aws-b --container floci-b --port 14566 --persist ~/.floci/data/aws-b
floci aws start --profile aws-a && floci aws start --profile aws-b
eval "$(floci aws env -o text --profile aws-b)"   # AWS_ENDPOINT_URL now points at :14566
floci aws stop --profile aws-b              # aws-a keeps running
```

Here the namespaces are derived as `a` and `b`.

**Start a profile's instance before loading its `env`.** The CLI finds a non-default port
through the running container. Before `start` (or after `stop --remove`),
`env --profile aws-b` prints the default port and `status --profile aws-b` reports the default
instance as reachable, so anything you run next would change the wrong instance. In the
profile itself read `port`; its `endpoint` field keeps the default port, and
`config profile list` does not show the derived namespace (`config show --profile` does).

Removing an instance: `floci aws stop --remove --profile aws-b`, then
`floci aws config profile delete aws-b`. The persist directory is left on disk; delete it
yourself only if the user wants the state gone.

### One namespace per git branch or worktree

```sh
ns="wt-$(git rev-parse --abbrev-ref HEAD | tr -c 'a-zA-Z0-9\n-' '-' | cut -c1-20)"
floci aws config profile create "$ns" --container "floci-$ns" --port 14566 --namespace "$ns"
```

Pick a free port per profile; the port is the one thing that must differ between instances.

### Compose with two instances on one daemon

```yaml
services:
  floci-a:
    image: floci/floci:latest
    ports: ["4566:4566"]
    environment:
      FLOCI_DOCKER_RESOURCE_NAMESPACE: a
    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
  floci-b:
    image: floci/floci:latest
    ports: ["14566:4566"]
    environment:
      FLOCI_DOCKER_RESOURCE_NAMESPACE: b
      FLOCI_BASE_URL: http://localhost:14566
    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
```

In Compose, set the namespace yourself: floci-cli derivation only applies to instances it
starts (see [docker.md](docker.md)). Set `FLOCI_BASE_URL` (or the product's `*_BASE_URL`) when the host port differs from
the default, so returned URLs such as SQS `QueueUrl` and presigned URLs are correct.
