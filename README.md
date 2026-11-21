# Yoo Nai Dee — Spring Boot Research Worker

A **headless, standalone Java 21 background worker** for the Yoo Nai Dee hackathon. It shares PostgreSQL with the Next.js LINE webhook / LIFF API, **does not serve frontend pages**, and never sends full analysis results through LINE.

## Versions

- Java 21 / Maven
- Spring Boot **4.2.0**
- Spring AI **2.1.0** (OpenAI starter and tool-calling foundations)
- Spring JDBC + HikariCP + PostgreSQL (SQL queue claiming, no JPA)
- Flyway (optional database initialization)
- Spring RestClient (external HTTP calls), Validation, Actuator libs, JUnit

## What is implemented

- Scheduled `ANALYSIS` job polling and atomic `FOR UPDATE SKIP LOCKED` claiming
- Attempt limits, exponential retry delay, worker leases, job phase/progress update
- A separate LINE notification outbox poller, claim/retry, and LINE Push via REST
- Stable deterministic weighted scoring utility with JUnit tests
- Database migration describing the **10 agreed tables**, including outbox
- Intentional fail-closed research pipeline: **does not claim ANALYSIS jobs** until `RESEARCH_ENABLED=true`; once enabled, unfinished research steps raise explicit errors rather than fabricate results
- Container-ready Dockerfile, Compose sample, Railway deployment notes

## Still to implement before real research works

The project is a **starter**, not an already functioning Research Agent. It does not process research jobs while `RESEARCH_ENABLED=false`. Finish these in order:

1. Validate the immutable `analysis_jobs.input_snapshot` profile with a versioned contract.
2. Implement real geocoding, Overpass POI, routing (ORS only for supported travel modes), and a legally usable rental-listing adapter behind `ResearchToolPort`. Apply cache/rate limits/timeouts.
3. Wire Spring AI `ChatClient` / tool callbacks into planning and factual explanations. `spring-ai-starter-model-openai` is installed but has no automatic external tool orchestration in this scaffold.
4. Calculate normalized features and hard-constraint checks; use `ZoneScorer` for deterministic ranking; calculate data confidence separately.
5. Insert `analysis_results`, set job `COMPLETED`, and insert `notification_outbox` **in one transaction**, with a compact `payload={"preview":"...","resultUrl":"https://..."}`. The Next.js Results API reads full results from PostgreSQL.
6. Implement long-running job lease renewal / cancellation and stronger observability as integrations are added. Current fixed lease is 5 minutes: avoid calls exceeding lease or another worker may reclaim.
7. Ensure outbox payload matches LINE Template Message limits, fallback copy, and quota. Use actual LIFF URL. The sample Push implementation is intentionally minimal.

## Run locally

```bash
# Option 1: Docker Compose (on a NEW, empty local database)
docker compose up --build

# Option 2: Maven + an existing PostgreSQL database
# configure environment variables in .env.example, then:
mvn test
mvn spring-boot:run
```

**IMPORTANT:** Maven does not load `.env` automatically. Export the variables or configure your IDE. `DB_MIGRATE=false` by default. Set it `true` only on a fresh database or when Flyway is selected as the single migration owner. **Do not run this V1 migration blindly over tables already created by Next.js/Drizzle**. If database already exists, reconcile the existing schema first and keep one migration owner.

`AI_CHAT_PROVIDER=none` prevents Spring AI OpenAI auto-configuration from requiring credentials at boot. Once research is implemented, supply an OpenAI key and change to `openai` (verify Spring AI 2.1 setting for the chosen model).

## Shared database contract

The Next.js webhook / conversation side persists `users`, `conversation_sessions`, and `conversation_messages` and creates `analysis_jobs` with `job_type='ANALYSIS'`, `status='QUEUED'`, a unique `idempotency_key`, a `profile_version`, and JSON `input_snapshot`.

Spring Boot claims **only ANALYSIS jobs**. Its eventual completed write transaction:

```text
analysis_results (full result_data JSONB)
analysis_jobs.status = COMPLETED
notification_outbox.status = PENDING (preview + LIFF URL)
```

LINE sends preview/link only. LIFF calls Next.js Results API with LINE identity checks. **Never expose a PostgreSQL credential to LIFF.**

## Railway

1. Create a separate **Railway service** for Spring Boot in the same Railway project as Next.js and PostgreSQL. That changes the previous single-app-container decision, but preserves one Railway project and shared PostgreSQL.
2. Deploy this folder with `Dockerfile`. No public domain or HTTP port is needed for the background worker.
3. Set `JDBC_DATABASE_URL=jdbc:postgresql://<private-host>:5432/<db>`, `PGUSER`, `PGPASSWORD` (or compose equivalents).
4. Supply `OPENAI_API_KEY` and provider/LINE credentials **only after** integrating providers. Never commit `.env`.
5. Keep one worker replica initially; coordinate with Next.js team to disable its ANALYSIS job runner. Leave CONVERSATION job processing in Next.js.

## Design notes

- `notification_outbox` is an *at least once* dispatcher. `X-Line-Retry-Key` is sent to reduce duplicate sends when retrying; external delivery still is not part of a PostgreSQL transaction.
- For this starter, job claims are safe between concurrent workers, but long transactions have been avoided. Complete work must regularly extend the lease; otherwise jobs can be retried while in progress.
- Consider retention of conversation content and third-party raw response caches; avoid persisting API secrets, personal identifiers, or unauthorised listing content in JSON.
- No Redis, Kafka, JPA, Spring Security, or Web MVC are required for this worker.
