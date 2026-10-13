# Azure (floci-az)

Port 4577, container `floci-az`, tree `floci az`, image `floci/floci-az`. Dev storage
account: `devstoreaccount1`.

`floci az start` turns TLS on (`FLOCI_AZ_TLS_ENABLED=true`), which serves HTTP and HTTPS on
the same port; the `az` CLI needs HTTPS. The image's own default is TLS off, so an instance
started with `docker run` or Compose needs that variable before `floci az setup` can work.

## Two planes, two wirings

- **Data plane (storage SDKs, `az storage ...`)**: a connection string is enough.
- **Control plane (`az group create`, ARM, Entra login, Terraform azurerm)**: `az` must be told
  about the emulator, otherwise it talks to public Azure. That is what `floci az setup` does.

## One-time setup per instance

```sh
floci az start
floci az setup                 # per-instance isolated az config; ~/.azure untouched
eval "$(floci az env -o text)"
az group create -n my-rg -l westeurope
```

`floci az setup`:
1. fetches the emulator CA and writes a CA bundle (public roots + emulator cert);
2. registers an az cloud named after the container, pointing ARM and Entra at
   `https://localhost:<port>`, and makes it active;
3. disables `core.instance_discovery` (otherwise login fails with `invalid_instance`);
4. logs in as a service principal: tenant `00000000-0000-0000-0000-000000000002`,
   subscription `00000000-0000-0000-0000-000000000001`. The secret is never checked.

State lives in `~/.floci/az/<container>/`. Use `--profile <name>` for other instances,
`--global` only if the user wants their default `~/.azure` switched (`--reset --global` switches
it back), `--reset` to delete the isolated config.

## What `floci az env` exports

Default (connection string mode): `AZURE_STORAGE_CONNECTION_STRING`. It contains `;`, and the
output is already quoted, so always `eval "$(floci az env -o text)"` with the double quotes.

The emulator does not check SharedKey signatures, so the connection string works for normal
data-plane calls. Blob SAS tokens are checked against the emulator's real account key: when
the user needs SAS, take the key from the floci-az docs
(`https://floci.io/floci-az/services/blob/`) rather than from the exported string.

`--format sdk-vars` instead exports `AZURE_STORAGE_ACCOUNT`, `AZURE_STORAGE_KEY` and per-service
endpoints (`AZURE_STORAGE_BLOB_ENDPOINT`, `..._QUEUE_...`, `..._TABLE_...`,
`AZURE_FUNCTIONS_ENDPOINT`, `AZURE_APP_CONFIGURATION_ENDPOINT`, `AZURE_KEY_VAULT_ENDPOINT`).
Limit with `--service blob,queue`.

After setup, both modes also export `AZURE_CONFIG_DIR` (the isolated az config) and
`REQUESTS_CA_BUNDLE` (the CA bundle). These only apply in the current shell. After
`floci az setup --global` the output unsets `AZURE_CONFIG_DIR` instead, because the user's
default `~/.azure` is then the one pointing at the emulator.

## Leaks to public Azure

- Control-plane commands (`az group`, `az resource`, anything ARM or login) run without
  `AZURE_CONFIG_DIR` from `floci az env` use the user's real config and real cloud. Data-plane
  `az storage` commands with the connection string go to the emulator either way, but still
  read and touch the user's `~/.azure`; run `floci az setup` first to keep `az` fully isolated. Check with `az cloud show --query name`: it must be the Floci cloud
  (`floci-az` by default), not `AzureCloud`.
- If the container was recreated without `--persist`, its certificate changed. `floci az env`
  warns on stderr; re-run `floci az setup`.
- Containers started by an older floci-cli may have TLS off. `floci az restart` once, then
  `floci az setup` again.

## Endpoint layout

All services share port 4577, routed by path, with the account as prefix:
Blob `/{account}/`, Queue `/{account}-queue/`, Table `/{account}-table/`,
Functions `/{account}-functions/`, App Config `/{account}-appconfig/`,
Cosmos `/{account}-cosmos/`, Key Vault `/{account}-keyvault/`,
Service Bus `/{account}-servicebus/`. ARM resources use the normal `/subscriptions/...` paths.
Other services have their own prefixes; read the service page for the exact URL shape.

AMQP (Event Hubs, Service Bus) and Kafka (Event Hubs) do not go through 4577. They are served
by sidecar containers the emulator starts, which bind their own ports on the host;
`floci az start` publishes only 4577. Read
`https://floci.io/floci-az/services/event-hub/` and `.../services/service-bus/` for the ports
and connection strings before writing a messaging client.

## Coverage

`floci az services -o json` returns an empty list and `floci az version -o json` reports the
server as unavailable, both with exit 0: floci-az does not serve the endpoint they read. That
does not mean nothing is enabled. Take the version from `floci az status -o json`, and
coverage from the docs. `floci az wait --service <name>` times out for the same reason; use
plain `wait`. Read the service page:
`https://floci.io/floci-az/services/<slug>/` (30 pages, slugs such as `blob`, `queue`,
`table`, `cosmos`, `key-vault`, `service-bus`, `event-hub`, `functions`, `aks`, `sql`,
`postgresql`, `entra`, `arm`; see [docs.md](docs.md)).

Rough guide: Blob (incl. ADLS Gen2), Queue, Table, App Configuration, Cosmos DB SQL API, Key
Vault and ARM resource groups are in-process with full CRUD. Functions, the database engines
(PostgreSQL, MySQL, MariaDB, managed SQL, the other Cosmos APIs), AKS, Container Apps,
Container Instances, Redis, ACR, and the Event Hubs and Service Bus brokers run real
containers and need the Docker socket. Some services are control plane only (Policy) or do
not deliver anything (Communication Email). The page says which.

Docs: https://floci.io/floci-az/

## Recipes

```sh
# Data plane
az storage container create --name uploads
az storage blob upload --container-name uploads --name hello.txt --data "hello"

# Control plane (after setup)
az group create -n my-rg -l westeurope
az group list -o table
```

For Key Vault, Cosmos and the other data planes, read the service page first
(`https://floci.io/floci-az/services/<slug>/`): their URL shapes differ from public Azure.

```python
import os
from azure.storage.blob import BlobServiceClient
svc = BlobServiceClient.from_connection_string(os.environ["AZURE_STORAGE_CONNECTION_STRING"])
svc.create_container("uploads")
```
