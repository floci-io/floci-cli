# AWS (floci)

Port 4566, container `floci`, tree `floci aws`, image `floci/floci`.

## Wiring

`eval "$(floci aws env -o text)"` exports:

| Variable | Value |
|----------|-------|
| `AWS_ENDPOINT_URL` | `http://localhost.floci.io:4566` |
| `AWS_ACCESS_KEY_ID` | `test` |
| `AWS_SECRET_ACCESS_KEY` | `test` |
| `AWS_DEFAULT_REGION` | `us-east-1` |

Options: `--region eu-west-1`, `--host <name>`, `--shell fish|powershell`, `-o json`,
`--profile <name>`. `localhost.floci.io` resolves to `127.0.0.1` and makes virtual-hosted S3
bucket URLs work. The region default is taken from an `AWS_DEFAULT_REGION` already set in the
shell, and the host from `FLOCI_HOST`; pass the flags when the value matters.

Modern AWS CLI v2 and SDKs honor `AWS_ENDPOINT_URL` for every service. After `eval`, plain
`aws` works.

## Leaks to public AWS

- Some SDKs and older botocore versions skip `AWS_ENDPOINT_URL` for specific services (SQS is
  the known case). When in doubt, pass `--endpoint-url "$AWS_ENDPOINT_URL"` explicitly. The
  `awslocal` wrapper from the floci repo always passes an endpoint, but it reads
  `FLOCI_ENDPOINT` (default `http://localhost:4566`), not `AWS_ENDPOINT_URL`: on a custom
  port or profile, set `FLOCI_ENDPOINT="$AWS_ENDPOINT_URL"` first.
- S3 with a host other than `localhost.floci.io` (a Compose service name, an IP) needs
  path-style addressing, set in code: `forcePathStyle: true` (JS v3),
  `forcePathStyle(true)` (Java v2), `config=Config(s3={"addressing_style": "path"})` (boto3).
- A named profile in `~/.aws/config` with its own `endpoint_url` or credentials can override
  the env vars. Do not pass `--profile` to `aws` unless it is a Floci profile.
- In code, set the endpoint on the client builder when the SDK version predates
  `AWS_ENDPOINT_URL` support (Java v2 `endpointOverride`, boto3 `endpoint_url`, JS v3
  `endpoint`), and set S3 `forcePathStyle` if you do not use `localhost.floci.io`.

## Accounts and regions

- Default account id `000000000000` (`FLOCI_DEFAULT_ACCOUNT_ID`).
- A 12-digit access key id is used as the account id, so `AWS_ACCESS_KEY_ID=111111111111`
  gives an isolated account. Same convention as LocalStack.
- Signatures are not verified by default (`FLOCI_AUTH_VALIDATE_SIGNATURES=false`).

## Starting options

```sh
floci aws start --persist ./data             # keep state across recreation
floci aws start --port 4567                  # custom host port; base URL is adjusted
floci aws start --namespace team-b           # scope child containers (see namespaces.md)
floci aws start --pull always                # newest image
```

Every service is on by default and starts instantly, so there is nothing to select. Do not use
`start --services`: no emulator reads the list it passes. To switch a service off, set
`FLOCI_SERVICES_<NAME>_ENABLED=false` on the container (Compose or `docker run`, see
[docker.md](docker.md)).

Snapshots: the `floci aws snapshot` commands exist, but the emulator has no snapshot API
yet, so they fail with exit 1. Use `--persist` to keep state.

## Coverage

More than 120 services are documented, one page each. Do not rely on a list from memory:

1. `floci aws services -o json` shows what is enabled in the running instance.
2. Read the service page before coding against it: `https://floci.io/floci/services/<slug>/`
   (find the slug in `https://floci.io/floci/sitemap.xml`; see [docs.md](docs.md)).

Services come in three kinds, and the page says which one applies:

- **Real containers** (need the Docker socket), e.g. Lambda, ECS, EC2, RDS, ElastiCache,
  OpenSearch, MSK. Several have a mock mode for when no Docker is available.
- **In-process, full behavior**, e.g. S3, DynamoDB, SQS, SNS, IAM, STS, KMS, Secrets Manager,
  SSM, EventBridge, Step Functions, Kinesis, CloudFormation, API Gateway, Cognito.
- **Canned or proxied responses**, e.g. Bedrock Runtime, Textract, Transcribe, Comprehend,
  Rekognition, Translate. Fine for wiring and control-plane tests, not for checking real
  output.

Docs: https://floci.io/floci/ (configuration, ports, storage, TLS, init hooks, Terraform,
migration from LocalStack).

## Recipes

```sh
# S3
aws s3 mb s3://my-bucket && echo hi | aws s3 cp - s3://my-bucket/hi.txt

# DynamoDB
aws dynamodb create-table --table-name users \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH --billing-mode PAY_PER_REQUEST

# SQS: use the returned QueueUrl as-is
aws sqs create-queue --queue-name jobs --query QueueUrl --output text

# Lambda (needs Docker): zip, create, invoke
aws lambda create-function --function-name hello --runtime python3.12 \
  --handler app.handler --zip-file fileb://fn.zip --role arn:aws:iam::000000000000:role/lambda
aws lambda invoke --function-name hello out.json && cat out.json
```

## Containers reaching floci

Other containers on the same Compose network should use the service name, and the emulator
should advertise it: set `FLOCI_HOSTNAME=floci` so returned URLs (SQS `QueueUrl`, presigned
URLs) use `http://floci:4566`. See [docker.md](docker.md) and `assets/compose/aws.yml`.
