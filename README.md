# spend-recon

Card-transaction to receipt reconciliation. This is the project skeleton.

The locked design is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Money, ingest, matching, approval, and the outbox are later pull requests.

## Requirements

- JDK 21
- Docker, for Testcontainers and `docker compose`

Stack: Kotlin 2.2, Spring Boot 3.5, Gradle Kotlin DSL, PostgreSQL 16, Flyway.

## Build

```bash
./gradlew build
```

`./gradlew build` starts a Postgres 16 container and runs the migration, health, and audit tests.

## Run

```bash
docker compose up --build
```

- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/prometheus
- API reference (Scalar): http://localhost:8080/scalar

Postgres is published on `localhost:5432` as database `spendrecon`, user `spendrecon`, password `spendrecon`. Those values are local defaults.
