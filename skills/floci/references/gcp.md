# GCP (floci-gcp)

Port 4588 (gRPC and REST on the same port), container `floci-gcp`, tree `floci gcp`,
image `floci/floci-gcp`. Default project: `floci-local`.

## Wiring for SDKs

`eval "$(floci gcp env -o text)"` exports a fixed set of emulator host variables (Storage,
Pub/Sub, Firestore, Datastore, Secret Manager). `IAM_EMULATOR_HOST` is only added when
requested with `--service`, e.g. `--service gcs,pubsub,iam` (unknown names are dropped
silently, so check the output):

| Variable | Service |
|----------|---------|
| `STORAGE_EMULATOR_HOST` | Cloud Storage |
| `PUBSUB_EMULATOR_HOST` | Pub/Sub |
| `FIRESTORE_EMULATOR_HOST` | Firestore |
| `DATASTORE_EMULATOR_HOST` | Datastore |
| `SECRET_MANAGER_EMULATOR_HOST` | Secret Manager |
| `IAM_EMULATOR_HOST` | IAM |

Client libraries skip credentials when these are set.
Pass `project="floci-local"` (or any id) explicitly to clients. Firebase Auth uses
`FIREBASE_AUTH_EMULATOR_HOST=localhost:4588`, which `floci gcp env` does not export: set it
yourself when the app uses the Firebase Admin SDK.

SDKs without an emulator variable (Cloud Run, Cloud Functions, KMS, Logging, Tasks, BigQuery,
etc.) need the endpoint set on the client: `http://localhost:4588` for REST, `localhost:4588`
plaintext for gRPC, with no credentials (`NoCredentialsProvider` in Java,
`AnonymousCredentials` in Python, `ClientOptions(api_endpoint=...)`).

## Wiring for gcloud (important)

**gcloud does not honor `*_EMULATOR_HOST`.** Without the overrides below, `gcloud` commands go
to real Google Cloud. Set these in the same shell:

```sh
E=http://localhost:4588
export CLOUDSDK_CORE_PROJECT=floci-local
export CLOUDSDK_AUTH_ACCESS_TOKEN=floci-fake-token     # any value; floci-gcp ignores it
export CLOUDSDK_CORE_DISABLE_PROMPTS=1
export CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE="$E/storage/v1/"   # storage needs the version path
export CLOUDSDK_API_ENDPOINT_OVERRIDES_SECRETMANAGER="$E/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_CLOUDKMS="$E/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_IAM="$E/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_CLOUDSCHEDULER="$E/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_CONTAINER="$E/container/"  # GKE lives under /container
export CLOUDSDK_API_ENDPOINT_OVERRIDES_SERVICEUSAGE="$E/"
export CLOUDSDK_CORE_SHOULD_PROMPT_TO_ENABLE_API=false
```

These are the overrides floci-gcp's own gcloud compatibility tests use (storage, secrets, kms,
iam, scheduler, container, serviceusage). For any other `gcloud` group, add
`CLOUDSDK_API_ENDPOINT_OVERRIDES_<API>` first, and if unsure, verify with
`gcloud ... --log-http` that requests go to `localhost:4588` before mutating anything.

Notes on gcloud:

- **Pub/Sub** is not in that test suite. `gcloud pubsub` has been seen working with
  `CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB="$E/"`; confirm with `--log-http` before relying on
  it, and prefer a client library with `PUBSUB_EMULATOR_HOST`.
- **Secret payloads**: with some gcloud versions the CRC32C check makes
  `gcloud secrets create --data-file` or `gcloud secrets versions add` exit non-zero although
  the version was created. If that happens, check `gcloud secrets versions list <name>` before
  retrying.
- **One request still goes to Google**: gcloud's own component update check
  (`dl.google.com/.../components-2.json`). It carries no project data and is not a leak.

Never run `gcloud auth login` or use the user's real gcloud configuration for Floci work.
Using a separate config dir avoids touching it: `export CLOUDSDK_CONFIG=~/.floci/gcp/gcloud`
(next to the rest of floci-cli's state).

## Projects

Resources are isolated per project id: `project-a` cannot see `project-b`. Use distinct project
ids for isolated test runs.

## Coverage

Run `floci gcp services -o json`. Services: Cloud Storage, Pub/Sub, Firestore, Datastore,
Secret Manager, Logging, KMS, IAM, IAM Credentials, STS, Managed Kafka, GKE, Cloud SQL
(PostgreSQL, MySQL), Cloud Run, Cloud Functions, Cloud Tasks, Cloud Scheduler, Monitoring,
Service Usage, Resource Manager, Eventarc, Firebase Auth, BigQuery, Compute Engine, Locations.

Docker-backed data planes (Kafka, PostgreSQL/MySQL, Kubernetes, Cloud Run containers) need the
Docker socket and expose their own generated host ports.

Docs: https://floci.io/floci-gcp/, one page per service at
`https://floci.io/floci-gcp/services/<slug>/` (slugs such as `gcs`, `pubsub`, `cloud-run`,
`cloud-sql-postgres`, which covers MySQL too; see [docs.md](docs.md)). Read the page before coding against a service.

## Recipes

```sh
# Python SDK
python3 - <<'EOF'
from google.cloud import storage, pubsub_v1
storage.Client(project="floci-local").create_bucket("my-bucket")
pub = pubsub_v1.PublisherClient()
pub.create_topic(request={"name": pub.topic_path("floci-local", "events")})
EOF

# gcloud, after the overrides above
gcloud storage buckets create gs://my-bucket
printf 's3cr3t' | gcloud secrets create api-key --data-file=-   # see "Known gcloud limits"
```
