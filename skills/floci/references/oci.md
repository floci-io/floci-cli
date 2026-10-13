# OCI (floci-oci)

Port 4599, container `floci-oci`, tree `floci oci`, image `floci/floci-oci`.
Object Storage namespace: `floci-local`. Throwaway tenancy:
`ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000`.

## One-time setup

The `oci` CLI and SDKs refuse to run without a config file and an API signing key, even
though floci-oci never verifies signatures.

```sh
floci oci start
floci oci setup                # RSA key at ~/.oci/floci_key.pem + [FLOCI] profile in ~/.oci/config
eval "$(floci oci env -o text)"
oci os ns get
```

`floci oci setup` never modifies existing profiles and is a no-op when re-run. It is shared by
all OCI instances; only the endpoint differs per instance.

## What `floci oci env` exports

| Variable | Purpose |
|----------|---------|
| `OCI_CLI_ENDPOINT` | Default `--endpoint` for every `oci` command |
| `FLOCI_OCI_ENDPOINT` | Used by floci-cli and the `ocilocal` wrapper |
| `TF_VAR_CLIENT_HOST_OVERRIDES` | Host overrides for the `oracle/oci` Terraform provider |
| `OCI_CLI_PROFILE` | `FLOCI`, when that profile exists (not exported for `DEFAULT`) |

OCI uses one endpoint for every service, so there are no per-service variables.

## Leaks to real OCI

- Without `OCI_CLI_PROFILE=FLOCI`, `oci` uses the `DEFAULT` profile, which may be the user's
  real tenancy. Always `eval "$(floci oci env -o text)"` first, or pass `--profile FLOCI`
  and `--endpoint http://localhost:4599` to `oci`.
- In SDK code, set the endpoint on every client (`client.setEndpoint("http://localhost:4599")`
  in Java, `service_endpoint=` in Python) and build auth from the FLOCI profile.

## Tenancy isolation

The tenancy OCID in the signing key's `keyId` is the storage partition: two profiles with
different tenancy OCIDs see separate worlds. Unsigned requests fall back to
`FLOCI_OCI_DEFAULT_TENANCY_ID`. Compartments are only an organizational filter, as on real OCI.

## Wire behavior worth knowing

- Missing resources return `404 NotAuthorizedOrNotFound`, as real OCI does.
- Async operations return `202` with `opc-work-request-id`; poll the work request instead of
  assuming completion. Identity, Queue and Streaming report `SUCCEEDED`; Object Storage and OKE
  report `COMPLETED`.
- Lists paginate with `limit`/`page` and the `opc-next-page` header.

## Coverage

Identity (compartments, users, groups, policies), Object Storage (buckets, objects, multipart,
pre-authenticated requests), Queue, Vault/KMS/Secrets (real AES-GCM, RSA, ECDSA crypto),
Streaming, Functions (real invocation via an
`fnproject/fnserver` sidecar), OKE (k3s sidecar). Functions and OKE need the Docker socket, or
their mock mode (`FLOCI_OCI_SERVICES_FUNCTIONS_MOCK=true`, `FLOCI_OCI_SERVICES_OKE_MOCK=true`).

Each service page lists what is not implemented yet (for example object versioning, lifecycle
policies, the S3 Compatibility API, queue consumer groups, secret rotation). Read it before
coding: `https://floci.io/floci-oci/services/<slug>/`, with slugs `identity`,
`object-storage`, `queue`, `kms-vault`, `streaming`, `functions`, `containerengine`
(see [docs.md](docs.md)).

Disabled services answer `503 ServiceUnavailable`.

Docs: https://floci.io/floci-oci/

## Recipes

```sh
T=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000
oci os bucket create --namespace-name floci-local --name my-bucket --compartment-id "$T"
oci os object put --namespace-name floci-local --bucket-name my-bucket --file ./hello.txt
oci iam compartment list --compartment-id "$T"
```
