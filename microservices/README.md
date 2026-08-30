# ShopSphere Microservices Platform

This project is a Spring Boot learning setup for:

- PostgreSQL for business data persistence
- Kafka + Avro + Schema Registry for service-to-service communication
- Redis for caching
- JWT authentication for secured API access
- Spring Cloud Gateway as the browser entry point
- Docker Desktop for containerized execution

It can be run in two recommended ways:

- locally from Eclipse or terminal
- fully inside Docker Desktop with Docker Compose

## Runtime services

1. api-gateway: 8080
2. auth-service: 8081
3. catalog-service: 8082
4. inventory-service: 8083
5. cart-service: 8085
6. payment-service: 8086
7. order-service: 8087
8. notifications-service: 8088
9. kafka (KRaft mode): 9092

## Login users (PostgreSQL + Redis/simple cache)

- Auth users are loaded from PostgreSQL table `auth_users`.
- Default bootstrap users are inserted by `auth-service/src/main/resources/data.sql`.
- Credentials:
  - `alice / alice123` (USER)
  - `admin / admin123` (USER, ADMIN)

Connection defaults:
- DB: `jdbc:postgresql://localhost:5432/postgres`
- Username: `postgres`
- Password: `12345`
- Redis: `redis:6379` in Kubernetes (`localhost:6379` fallback for local)
- Schema Registry: `http://localhost:8084` for local
- Kafka bootstrap server: `localhost:9092` for local

## Local build
```bash
mvn clean package -DskipTests
```

## What is persisted in PostgreSQL

These tables are created from each service's `schema.sql` when that service starts:

- `auth_users`
- `catalog_products`
- `inventory_items`
- `cart_items`
- `payment_records`
- `order_records`

Default seed data is provided for:

- `auth_users`
- `catalog_products`
- `inventory_items`

## Start local infrastructure with Docker Desktop

This is the easiest way to test PostgreSQL, Redis, Kafka, and Schema Registry together:

```powershell
docker compose -f docker-compose.learning.yml up -d
```

Check that the containers are healthy:

```powershell
docker ps
```

## Run the microservices locally from terminal

Build first:

```powershell
mvn clean package -DskipTests
```

Then start each service in a separate terminal:

```powershell
java -jar .\auth-service\target\auth-service-1.0.0.jar --server.port=8081
java -jar .\catalog-service\target\catalog-service-1.0.0.jar --server.port=8082
java -jar .\inventory-service\target\inventory-service-1.0.0.jar --server.port=8083
java -jar .\cart-service\target\cart-service-1.0.0.jar --server.port=8085
java -jar .\payment-service\target\payment-service-1.0.0.jar --server.port=8086
java -jar .\order-service\target\order-service-1.0.0.jar --server.port=8087
java -jar .\notifications-service\target\notifications-service-1.0.0.jar --server.port=8088
java -jar .\api-gateway\target\api-gateway-1.0.0.jar --server.port=8090
```

Browser UI:

- `http://localhost:8090/`

## Run the full microservices system only with Docker Desktop

This mode does not use Kubernetes or Minikube.

1. Build the jars first:

```powershell
mvn clean package -DskipTests
```

2. Build and start everything:

```powershell
docker compose -f docker-compose.app.yml up --build -d
```

3. Open the UI:

- `http://localhost:8090/`

4. Check running containers:

```powershell
docker ps
```

5. Stop everything:

```powershell
docker compose -f docker-compose.app.yml down
```

6. Stop and delete containers plus volumes:

```powershell
docker compose -f docker-compose.app.yml down -v
```

## Run from Eclipse

1. Import the root folder as `Existing Maven Projects`.
2. Wait for Maven dependencies to finish downloading.
3. Start Docker Desktop.
4. Start learning infrastructure:

```powershell
docker compose -f docker-compose.learning.yml up -d
```

5. In Eclipse, run these classes as `Java Application` or `Spring Boot App`:

- `com.example.auth.AuthServiceApplication`
- `com.example.catalog.CatalogServiceApplication`
- `com.example.inventory.InventoryServiceApplication`
- `com.example.cart.CartServiceApplication`
- `com.example.payment.PaymentServiceApplication`
- `com.example.order.OrderServiceApplication`
- `com.example.notifications.NotificationsServiceApplication`
- `com.example.gateway.ApiGatewayApplication`

6. For `ApiGatewayApplication`, set program arguments:

```text
--server.port=8090
```

7. If you want explicit environment variables in Eclipse run configs, use:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=12345
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
SCHEMA_REGISTRY_URL=http://localhost:8084
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
SPRING_CACHE_TYPE=redis
```

If Redis is not running, set:

```text
SPRING_CACHE_TYPE=simple
```

## Learning checklist

Use these to test each technology intentionally:

### PostgreSQL

- Start `auth-service`, `catalog-service`, `inventory-service`, `cart-service`, `payment-service`, `order-service`
- Verify table creation in PostgreSQL
- Create catalog items, restock inventory, add cart items, checkout, and inspect persisted rows

### Kafka

- Start Kafka and Schema Registry
- Start `order-service`, `payment-service`, `inventory-service`, `notifications-service`
- Perform checkout from the UI
- Observe:
  - `orders.created`
  - `payments.processed`
  - `orders.failed` on failure paths

### Redis

- Set `SPRING_CACHE_TYPE=redis`
- Start Redis
- Hit repeated catalog/cart/order summary endpoints
- Inspect Redis keys and cached behavior

### JWT

- Start `auth-service` and `api-gateway`
- Login from the UI using `alice/alice123` or `admin/admin123`
- Confirm a JWT token is returned
- Test admin-only actions with `admin`

## Notes

- Local application properties default to localhost-friendly values for PostgreSQL, Redis, Kafka, and Schema Registry.
- Docker Compose file `docker-compose.app.yml` is the recommended containerized run mode.
- No Eureka server dependency is used in the active modules.

## Kafka integration use cases

1. Order checkout publishes `orders.created` from `order-service`.
2. `payment-service` and `inventory-service` consume `orders.created` with event dedupe.
3. Payment charge publishes `payments.processed`.
4. `order-service` consumes `payments.processed` for correlation.
5. If payment fails, `order-service` triggers compensation via `inventory-service` release endpoint and publishes `orders.failed`.
6. `notifications-service` consumes `orders.created`, `orders.failed`, and `payments.processed`.

Kafka payload format:
- Avro via Confluent serializer/deserializer.
- Schema Registry URL: `http://schema-registry:8081`.

### Demo endpoints

1. Trigger checkout: `POST /orders/checkout`
2. View order events from payment stream: `GET /orders/events/payments`
3. View payment consumed order events: `GET /payments/events/orders`
4. View inventory consumed order events: `GET /inventory/events/orders`
5. View notifications stream: `GET /notifications/admin/events`
6. View observability snapshot: `GET /notifications/admin/observability`

## Implemented scale-up features

1. Saga-style compensation on checkout failure (release inventory on payment failure).
2. Clean unversioned API flow (`/checkout`, `/charge`) with idempotency + dedupe.
3. Idempotency key handling for order and payment write APIs.
4. Kafka consumer dedupe to avoid duplicate-event side effects.
5. Resilience4j retry/circuit-breaker/bulkhead on orchestration path.
6. Prometheus endpoint enabled across services.
7. OpenTelemetry exporter/tracing properties added for OTLP pipeline.
8. Kafka moved to KRaft mode.
9. Redis cache baseline for auth lookups.
10. Contract-test scaffold in `payment-service` using Spring Cloud Contract.

