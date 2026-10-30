# JiaxianOp repository guide

## Purpose and layout

This repository is a simulation and validation reference for an internal community operations analytics platform. The simulator generates the community data; it is not a production data integration. Read `README.md` for the product scope, setup, and known limitations before changing behavior.

- `backend/`: Java 17, Spring Boot 3, Maven. API, simulator, points, Kafka consumer, and analytics modules live under `backend/src/main/java/com/jiaxian/analytics/`.
- `frontend/`: Vue 3, TypeScript, Vite, Element Plus, and ECharts. Pages are in `src/views/`, API clients in `src/api/`, reusable calculations in `src/utils/` and `src/charts/`.
- `scripts/`: local setup, demo seeding, backend test, and smoke test commands.
- `docs/superpowers/specs/` and `docs/superpowers/plans/`: existing design decisions and implementation history. Consult the relevant documents before changing a feature's definitions or metrics.
- `docker-compose.yml`: OpenGauss, Redis, Kafka, and the optional application profile.

## Development and checks

- Use Java 17 for Maven. On macOS with Homebrew, set `JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"` and put `$JAVA_HOME/bin` first in `PATH`; see `README.md`.
- Start infrastructure with `docker compose up -d`. Run the backend from `backend/` with `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093`; run the frontend from `frontend/` with `VITE_API_TARGET=http://localhost:8093 npm run dev`.
- Frontend checks: `cd frontend && npm test` and `cd frontend && npm run build`.
- Backend unit tests without infrastructure: `cd backend && mvn test '-Dtest=!*IT'`.
- Backend integration tests require OpenGauss, Redis, and Kafka: start infrastructure, then run `scripts/test-backend.sh`. The script creates an isolated test database; test settings also use a separate Kafka topic and consumer group and Redis database 1.
- Full application smoke test: start the `app` Compose profile, seed demo data, then run `scripts/smoke-test.sh`. See `README.md` for the exact sequence.
- Match existing Java package boundaries and frontend module patterns. Add or update focused tests when changing behavior; run the relevant checks before reporting success.

## Data and deployment constraints

- `scripts/seed-demo.sh` and the simulator backfill endpoint clear and regenerate simulated data. Do not run them against an existing demo dataset without an explicit reason. `scripts/demo-snapshot.sh` supports save and restore; restore overwrites the current database.
- Admin API endpoints under `/api/admin/**` have no authentication. This reference stack is for an internal network and must not be exposed publicly as is.
- `.env` and `.demo-snapshots/` are local and ignored by Git. Do not commit credentials, database dumps, `frontend/node_modules/`, `frontend/dist/`, or `backend/target/`.
- The default demo credentials are not deployment credentials. Check `README.md` and `.env.example` when configuring services.
