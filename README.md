# Yoo Nai Dee ANALYSIS worker

Java 21 / Spring Boot worker. It polls PostgreSQL for ANALYSIS jobs, asks an OpenAI agent to research Bangkok neighborhoods with OpenAI web search, executes the agent's `saveAnalysisResult` tool call against PostgreSQL, and queues a LINE Flex push. The notification poller sends the push after the database transaction commits. The analysis path does not call Nominatim, routing, rental, or other data providers.

## Database setup

For an existing database, apply these migrations in order:

1. `docs/migrations/20261121_analysis_waiting_for_handler.sql`
2. `docs/migrations/20261121_analysis_acknowledged.sql`
3. `docs/migrations/20261121_demo_analysis_completion.sql`

For a fresh database, run `docs/yoo_nai_dee_init.sql` first, then the three migrations. The completion migration also requeues parked ANALYSIS jobs. Apply it once before starting the worker.

Set `JDBC_DATABASE_URL`, `PGUSER`, `PGPASSWORD`, `OPENAI_API_KEY`, and `LINE_CHANNEL_ACCESS_TOKEN` in the environment. `OPENAI_MODEL` defaults to `gpt-4.1-mini`; it must support Responses API web search and function calling. Run `mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` elsewhere. The worker does not migrate tables automatically. `worker.poll-delay-ms` defaults to 1000.

## Flow

1. Claim one due ANALYSIS job with `FOR UPDATE SKIP LOCKED`, a lease, and bounded retries.
2. Validate its saved profile snapshot. The agent uses OpenAI web search to find three neighborhoods, then calls `saveAnalysisResult` with explanations, trade-offs, and cited URLs.
3. Validate the tool arguments and require each URL to match a web search citation. In one database transaction, resolve or create inactive zone IDs, insert `analysis_results`, complete the job, and enqueue an `ANALYSIS_COMPLETED` notification only for the current profile version. Stale jobs retain their result without notifying the user.
4. Push a LINE Flex with the three names, reasons, and caveats. The sender uses the outbox UUID as `X-Line-Retry-Key` and retries failed pushes without rerunning research. Terminal job failures queue one `ANALYSIS_FAILED` text notification.

The result leaves scores, rent estimates, and commute times null because web search alone does not verify them. Sources are stored with each recommendation. The Flex has no LIFF link because the result page and authorized result API are not implemented yet.
