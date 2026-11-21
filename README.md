# Yoo Nai Dee ANALYSIS queue consumer

Java 21 / Spring Boot worker sharing the PostgreSQL database with the LINE application. This phase implements queue ownership only. It has no research handler, HTTP API, result persistence, or LINE sender.

## Run

For the existing dev database, apply `docs/migrations/20261121_analysis_waiting_for_handler.sql` once before starting the worker. For a fresh database, run `docs/yoo_nai_dee_init.sql` and then this migration. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` in the environment, then run `mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` elsewhere. The worker does not create or migrate tables. Keep database credentials outside source control. Set `worker.poll-delay-ms` as a Spring property to change the default one-second poll interval.

To build and run the worker in Docker from this directory:

```sh
docker build -t yoonaidee-background-worker .
docker run --rm --env SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/app --env SPRING_DATASOURCE_USERNAME=app --env SPRING_DATASOURCE_PASSWORD=app yoonaidee-background-worker
```

Replace the example database connection values with your local settings. On Linux, use a database host reachable from the container. The worker needs no published port.

## Current behavior

- Claims one due `ANALYSIS` job per poll from `QUEUED` or retryable `FAILED`, using a short transaction and `FOR UPDATE SKIP LOCKED`. `CONVERSATION` jobs are excluded from all queries.
- A claim sets `PROCESSING`, assigns a unique process identity and a 60-second lease, and increments `attempts`. The transaction commits before the next action.
- Because research is not implemented, the consumer parks the job in `WAITING_FOR_HANDLER`, clears the lease and worker identity, and restores the previous attempt count. The `input_snapshot` and `profile_version` are not changed. Parked jobs are not claimed again.
- An expired `ANALYSIS` lease is recovered under a row lock into `FAILED`. A failed park uses the same failure state if this worker still owns a live lease. Retries use `run_after` with `min(300, 5 * 2^(attempts-1))` seconds; `FAILED` at `max_attempts` is terminal. If the database is unavailable, lease expiry provides eventual recovery after connectivity returns. No database transaction stays open during processing.
- This phase never creates `analysis_results` or `notification_outbox` rows, changes `conversation_sessions`, marks jobs `COMPLETED`, or invents recommendations.

## Enable research later

Replace the park step with the real research handler and lease renewal before releasing parked jobs. Then run:

```sql
UPDATE analysis_jobs
SET status = 'QUEUED', run_after = now(), updated_at = now()
WHERE job_type = 'ANALYSIS' AND status = 'WAITING_FOR_HANDLER';
```

This preserves each job's snapshot, profile version, and attempt budget. Apply the incremental migration to existing databases before starting this consumer; do not rerun the initialization script as a migration.
