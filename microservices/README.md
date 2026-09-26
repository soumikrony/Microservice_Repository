# ShopSphere Microservices

This repository contains a Java 25 / Spring Boot 3.5.0 sample composed of ten Maven modules. It demonstrates a small shopping workflow with synchronous REST calls and Kafka events. It is a learning project, not a production deployment claim.

## Services

| Module | Responsibility | Local port |
|---|---|---:|
| `api-gateway` | Browser entry point, routing, JWT validation | 8090 on host → 8080 in container |
| `auth-service` | Authentication and user lookup | 8081 |
| `catalog-service` | Product catalog | 8082 |
| `inventory-service` | Stock and inventory events | 8083 |
| `cart-service` | Cart operations | 8085 |
| `payment-service` | Payment workflow and events | 8086 |
| `order-service` | Checkout orchestration and compensation | 8087 |
| `notifications-service` | Consumes workflow events | 8088 |
| `config-server` | Centralized application configuration | 8888 |
| `eureka-server` | Service discovery | 8761 |

PostgreSQL, Redis, Kafka, and Schema Registry are external local prerequisites. The application Compose file does not provision them. Its containers connect through `host.docker.internal`; Kafka is expected on the Docker-host listener at `29092`, and Schema Registry at `8084`.

## Event flow

The documented checkout flow is:

1. `order-service` publishes `orders.created`.
2. `payment-service` and `inventory-service` consume the order event with deduplication.
3. `payment-service` publishes `payments.processed`; `order-service` correlates the result.
4. `notifications-service` consumes order, payment, and failure events.
5. A payment failure triggers inventory compensation and an `orders.failed` event.

Avro serialization and Confluent Schema Registry are used for the Kafka payloads. The code and docs also include idempotency handling, Resilience4j policies, Prometheus metrics, OTEL export configuration, and a Spring Cloud Contract test scaffold. These are repository features; this README makes no throughput or production-reliability claim.

## Versions and dependencies

- Java release: 25
- Spring Boot: 3.5.0
- Spring Cloud: 2025.0.0
- Avro: 1.12.0
- PostgreSQL/JPA, Redis, Kafka/Avro, JWT/OAuth2 resource server, Docker Compose, Actuator, Prometheus, OpenTelemetry, Resilience4j

## Configure local credentials

Copy the example file and set values for your local-only services:

```powershell
Copy-Item .env.example .env
notepad .env
```

Generate a fresh JWT signing value rather than reusing a repository or production value:

```powershell
[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

The `.env` file is ignored by Git. Do not paste its values into issues, commits, screenshots, or public configuration. Each service requires `SPRING_DATASOURCE_PASSWORD`; the auth service and gateway also require the same `JWT_SECRET`.

## Build and run

Start PostgreSQL on 5432, Redis on 6379, Kafka on the expected local listener (9092 for local clients and 29092 for Docker clients), and Schema Registry on 8084. Confirm the services are reachable before proceeding.

From the repository root:

```powershell
mvn -f microservices/pom.xml clean package -DskipTests
docker compose --env-file microservices/.env -f microservices/docker-compose.app.yml up --build -d
docker compose --env-file microservices/.env -f microservices/docker-compose.app.yml ps
```

Open <http://localhost:8090/>. The host gateway port is 8090; its container port is 8080.

Stop the application containers with:

```powershell
docker compose --env-file microservices/.env -f microservices/docker-compose.app.yml down
```

## Monitoring

The OTEL Collector starts with the app stack. Prometheus and Grafana are in a separate Compose file:

```powershell
docker compose --env-file microservices/.env -f microservices/docker-compose.monitoring.yml up -d
```

Prometheus: <http://localhost:9090/>. Grafana: <http://localhost:3000/>. The Grafana password comes from `GF_SECURITY_ADMIN_PASSWORD` in the ignored local `.env` file.

## Local demo data and security

SQL seed data exists for local demonstrations. It is not a production account or secret-management mechanism. The README intentionally does not publish sample passwords. Keep production credentials out of this repository and rotate any earlier committed values that were reused outside local development. A new commit removes values from the current tree but does not remove them from older Git commits.

See the repository root README for the architecture diagram and the full module overview.
