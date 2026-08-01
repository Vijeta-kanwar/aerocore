# AirTicket — flight search and seat booking

[![CI](https://github.com/Vijeta-kanwar/air_ticket_system/actions/workflows/ci.yml/badge.svg)](https://github.com/Vijeta-kanwar/air_ticket_system/actions/workflows/ci.yml)

![AirTicket booking interface](/Users/vijetakanwar/Desktop/pic.png)

A Spring Boot service for searching flights and reserving seats, backed by PostgreSQL,
containerised, and deployed to Kubernetes with a CI pipeline that tests, builds and
publishes the image on every push to `main`.

The interesting part isn't the CRUD. It's that reserving a seat is a read-then-write on
a shared counter, and the app runs three replicas — so the booking path is written to be
correct under concurrency rather than merely to work when one person clicks at a time.

---

## Run it

One command, nothing to install beyond Docker:

```bash
docker compose up --build
```

Then open **http://localhost:8080**.

| What | Where |
| --- | --- |
| Booking UI | http://localhost:8080 |
| API reference (Swagger) | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

The database is seeded with ten flights on real Indian routes, so there is something to
search the moment it starts.

To stop and wipe the database volume: `docker compose down -v`

---

## Architecture

```
                        ┌──────────────────────────────┐
   push to main ───────▶│  GitHub Actions              │
                        │  test → smoke → publish      │
                        └───────────────┬──────────────┘
                                        │ image
                                        ▼
                              ghcr.io/…/air_ticket_system
                                        │
                                        │ kubectl apply -k k8s/
                                        ▼
   ┌────────────────────────────────────────────────────────────┐
   │  namespace: airticket                                      │
   │                                                            │
   │   Service (NodePort 30080)                                 │
   │        │                                                   │
   │        ├──▶ Pod ─┐                                         │
   │        ├──▶ Pod ─┼──▶ Service ──▶ StatefulSet: postgres:16 │
   │        └──▶ Pod ─┘   airticket-db      └── PVC (1Gi)       │
   │             ▲                                              │
   │             └── HPA: 2–6 replicas @ 70% CPU                │
   └────────────────────────────────────────────────────────────┘
```

Three app pods share one database. That is the whole reason the storage layer is a
StatefulSet with a volume rather than an in-memory database — see the note on state below.

---

## Stack

| Layer | Choice |
| --- | --- |
| Language / framework | Java 17, Spring Boot 3.2 |
| Persistence | PostgreSQL 16, Spring Data JPA |
| Schema management | Flyway (versioned migrations) |
| API docs | springdoc-openapi |
| Build | Maven |
| Container | Docker, multi-stage, layered JAR, non-root |
| Local environment | Docker Compose |
| Orchestration | Kubernetes + Kustomize |
| CI/CD | GitHub Actions → GitHub Container Registry |
| Tests | JUnit 5, Mockito, MockMvc, JaCoCo |

---

## Engineering notes

Things in here that were deliberate, and why.

**Seat reservation takes a row lock.** Booking reads `available_seats`, compares it to the
request, then writes the decremented value. Two pods running that at the same moment can
both read "2 seats left" and both succeed, overselling the flight. The read goes through
`findByIdForUpdate`, a `SELECT … FOR UPDATE`, inside a single transaction, so a concurrent
booking on the same flight blocks until the first commits. A `CHECK` constraint in the
schema backs this up at the database level in case application code ever regresses.

**The schema is versioned, not generated.** `spring.jpa.hibernate.ddl-auto=validate`, and
every table comes from a numbered file in `src/main/resources/db/migration`. Hibernate
verifies the entities match at boot and refuses to start if they've drifted. `ddl-auto=update`
would silently alter production tables on deploy and cannot express a `CHECK` constraint,
a partial index, or a backfill.

**Money is `BigDecimal` and `NUMERIC(10,2)`.** `double` cannot represent 5499.10 exactly;
multiply it by a seat count a few thousand times and the ledger drifts.

**Liveness and readiness are separate probes.** A pod that has lost its database connection
is not *ready* for traffic, but restarting it fixes nothing — so readiness reports the
database and liveness doesn't. A `startupProbe` absorbs slow JVM boot so liveness can stay
aggressive afterwards without killing pods that are merely still starting.

**Errors carry a status code and a shape.** A `@RestControllerAdvice` maps a missing flight
to 404, a full flight to 409, and a malformed payload to 400 with the offending field named.
Nothing reaches the client as a 500 with a stack trace.

**The image is layered.** The JAR is split by change frequency, so editing a controller
pushes a few hundred kilobytes rather than the whole ~60 MB image.

**The frontend uses relative paths.** `/api/flights`, never `http://localhost:8080/api/flights`,
so the same build works on Compose, on a NodePort, and behind an ingress.

### Known limits

Being explicit about what this doesn't do:

- **No authentication.** Anyone can cancel any booking if they know its id. Adding Spring
  Security with per-passenger authorisation is the obvious next step.
- **One database replica.** The StatefulSet is a single Postgres pod with no replication,
  so the database is a single point of failure. Real high availability needs an operator
  such as CloudNativePG.
- **Demo credentials are committed** in `k8s/postgres/secret.yaml` so the project runs from
  a clean clone. A real cluster would use Sealed Secrets or External Secrets Operator.
- **Seats are counted, not assigned.** There is no seat map; a booking reserves *n* seats
  rather than 12A and 12B.

---

## API

Full interactive reference at `/swagger-ui.html`. The common calls:

```bash
# Search a route
curl "http://localhost:8080/api/flights/search?origin=Delhi&destination=Mumbai"

# Reserve two seats
curl -X POST http://localhost:8080/api/bookings \
  -H 'Content-Type: application/json' \
  -d '{
    "flightId": 1,
    "passengerName": "Vijeta Kanwar",
    "passengerEmail": "vijeta@example.com",
    "passengerPhone": "9876543210",
    "seatsBooked": 2
  }'

# Find your bookings
curl "http://localhost:8080/api/bookings/passenger?email=vijeta@example.com"

# Cancel one (returns the seats to the flight)
curl -X POST http://localhost:8080/api/bookings/1/cancel
```

| Method | Endpoint | Returns |
| --- | --- | --- |
| GET | `/api/flights` | 200 — the whole schedule |
| GET | `/api/flights/search?origin=&destination=` | 200, 400 if a parameter is missing |
| GET | `/api/flights/{id}` | 200, 404 |
| POST | `/api/flights` | 201 + `Location`, 400, 409 on duplicate flight number |
| PUT | `/api/flights/{id}` | 200, 400, 404, 409 |
| DELETE | `/api/flights/{id}` | 204, 404 |
| GET | `/api/bookings` | 200 |
| GET | `/api/bookings/{id}` | 200, 404 |
| GET | `/api/bookings/reference/{ref}` | 200, 404 |
| GET | `/api/bookings/passenger?email=` | 200, 400 |
| POST | `/api/bookings` | 201 + `Location`, 400, 404, **409 when the flight is full** |
| POST | `/api/bookings/{id}/cancel` | 200, 404, 409 if already cancelled |
| DELETE | `/api/bookings/{id}` | 204, 404 |

Every error response has the same shape:

```json
{
  "timestamp": "2026-08-01T09:14:22Z",
  "status": 409,
  "error": "Conflict",
  "message": "Requested 5 seat(s) but only 2 remain on this flight",
  "path": "/api/bookings"
}
```

---

## Deploying to Kubernetes

Tested on minikube.

```bash
minikube start
minikube addons enable metrics-server     # the HPA needs this

kubectl apply -k k8s/

kubectl -n airticket rollout status deployment/airticket-app
minikube service airticket-app -n airticket --url
```

To run your own image instead of the published one:

```bash
cd k8s && kustomize edit set image ghcr.io/vijeta-kanwar/air_ticket_system=my-image:tag
```

---

## Tests

```bash
mvn verify          # unit + slice tests, then a JaCoCo report in target/site/jacoco
```

Three layers, each testing something the others can't:

- **Service tests** (Mockito, no Spring context) — overbooking is rejected, seat counts move
  by the right amount, cancelling twice doesn't credit seats twice, the flight row is locked
  rather than plainly read.
- **Controller tests** (`@WebMvcTest` + MockMvc) — validation rejects bad emails and zero-seat
  requests with 400 and a named field; a full flight returns 409, not 500.
- **Smoke test** (CI, `docker compose`) — the image builds, Flyway migrates a real Postgres,
  a booking actually decrements the seat count. This is the layer that catches what mocks
  can't: a broken Dockerfile, a bad migration, a misconfigured connection string.

---

## Repository layout

```
.github/workflows/ci.yml     test → smoke → publish to GHCR
src/main/java/com/airticket/
  controller/                REST endpoints, DTOs in and out
  dto/                       request/response records, bean validation
  exception/                 typed exceptions + @RestControllerAdvice
  model/                     JPA entities
  repository/                Spring Data interfaces, incl. the locking query
  service/                   transactional business logic
src/main/resources/
  db/migration/              Flyway V1 schema, V2 seed data
  static/index.html          the booking UI
  application*.properties    default / local / kubernetes profiles
src/test/java/               unit and slice tests
k8s/                         Kustomize: namespace, postgres, app
Dockerfile                   multi-stage, layered, non-root
docker-compose.yml           app + postgres for local work
```

---

## Author

**Vijeta Kanwar** — [github.com/Vijeta-kanwar](https://github.com/Vijeta-kanwar)

## License

MIT — see [LICENSE](LICENSE).
