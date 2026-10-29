<div align="center">
  <h1>Continuum Feature KNIME</h1>
  <strong>Run KNIME workflows as durable steps in <a href="https://github.com/projectcontinuum-org">Project Continuum</a></strong>
</div>

<div align="center">
  <img src="https://img.shields.io/badge/License-Apache_2.0-green" alt="License: Apache 2.0">
  <img src="https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-green?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/KNIME-5.x-orange" alt="KNIME 5">
  <img src="https://img.shields.io/badge/JDK-21-red" alt="JDK 21">
</div>

---

## Part of Project Continuum

[Project Continuum](https://github.com/projectcontinuum-org) is an open-source, cloud-native, no-code workflow platform with durable execution on Temporal. This repository lets Continuum run existing [KNIME](https://www.knime.com/) workflows (`.knwf` files) as nodes inside a Continuum workflow, so teams can reuse their KNIME work while gaining crash-proof, observable execution.

## What is in this repository

| Module | Purpose |
|---|---|
| [`features/continuum-feature-knime-executor`](features/continuum-feature-knime-executor) | The **KNIME Workflow Executor** node: downloads a `.knwf` file, runs it with KNIME's batch executor, captures the logs and uploads the executed workflow. See its [README](features/continuum-feature-knime-executor/README.md) for configuration and usage. |
| [`worker`](worker) | Spring Boot worker that bundles the executor feature and registers it with Temporal. Published as `projectcontinuum/continuum-feature-knime`. |
| [`scheduler`](scheduler) | REST service for storing KNIME workflows in S3 or MinIO (`/api/v1/knime-workflows`) and scheduling them (`/api/v1/knime-workflow-schedules`), with a small web UI. Published as `projectcontinuum/continuum-knime-scheduler`. |
| [`base-image`](base-image) | Docker base image with KNIME Analytics Platform, extra KNIME extensions and a separate Temurin 21 runtime for the worker. Published as `projectcontinuum/continuum-feature-knime-base`. |
| [`charts`](charts) | Helm chart for deploying the worker and scheduler on Kubernetes. |

## Prerequisites

- **JDK 21** ([Eclipse Temurin](https://adoptium.net/) recommended)
- **Docker**, to build the base image and run the platform locally
- A running Continuum platform. The quickest way is [continuum-local-setup](https://github.com/projectcontinuum-org/continuum-local-setup).

This repository builds against Continuum platform `0.0.20` (see `gradle.properties`).

## Build and test

```bash
git clone https://github.com/projectcontinuum-org/continuum-feature-knime.git
cd continuum-feature-knime

./gradlew :features:continuum-feature-knime-executor:test
./gradlew :features:continuum-feature-knime-executor:build
./gradlew :worker:build
./gradlew :scheduler:test
```

The KNIME base image is published for `linux/amd64` only. On Apple Silicon, build it with `docker buildx build --platform linux/amd64`. The `base-image/Dockerfile` header explains how to do this, including how to pass a corporate root CA when building behind a TLS-intercepting proxy.

## Deployment

Install the Helm chart into the same namespace as the Continuum platform:

```bash
helm repo add continuum https://projectcontinuum-org.github.io/continuum-charts
helm repo update
```

See [continuum-charts](https://github.com/projectcontinuum-org/continuum-charts) and the [deployment docs](https://docs.projectcontinuum.org) for details.

## Related repositories

| Repository | Description |
|---|---|
| [continuum-platform-core](https://github.com/projectcontinuum-org/continuum-platform-core) | Core backend: API server, orchestration, worker framework |
| [continuum-workbench](https://github.com/projectcontinuum-org/continuum-workbench) | Browser IDE and workflow editor |
| [continuum-feature-base](https://github.com/projectcontinuum-org/continuum-feature-base) | Base analytics and data-processing nodes |
| [continuum-feature-template](https://github.com/projectcontinuum-org/continuum-feature-template) | Starter template for building your own feature worker |

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and the organization's [Code of Conduct](https://github.com/projectcontinuum-org/.github/blob/main/CODE_OF_CONDUCT.md).

## License

[Apache 2.0](LICENSE). KNIME is a trademark of KNIME AG and is not affiliated with this project.
