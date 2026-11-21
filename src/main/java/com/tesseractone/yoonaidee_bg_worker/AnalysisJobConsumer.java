package com.tesseractone.yoonaidee_bg_worker;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AnalysisJobConsumer {
    private static final Logger log = LoggerFactory.getLogger(AnalysisJobConsumer.class);
    private static final String WORKER_ID = UUID.randomUUID().toString();
    private static final String RETRY_DELAY = "make_interval(secs => LEAST(300, (5 * power(2, LEAST(attempts - 1, 6)))::int))";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OpenAiZoneResearchAgent agent;

    public AnalysisJobConsumer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               OpenAiZoneResearchAgent agent) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.agent = agent;
    }

    private record Job(UUID id, String snapshot) {}
    private record Result(JsonObject data, JsonObject preview, List<String> names) {}

    @Scheduled(fixedDelayString = "${worker.poll-delay-ms:1000}")
    public void poll() {
        try {
            recoverExpiredLease();
            enqueueTerminalFailures();
            Job job = transactions.execute(status -> claim());
            if (job == null) return;
            String stage = "validate_snapshot";
            try {
                JsonObject snapshot = JsonParser.parseString(job.snapshot()).getAsJsonObject();
                validateSnapshot(snapshot);
                stage = "agent_research_and_save_call";
                JsonObject researched = agent.researchAndCallSave(snapshot);
                stage = "persist_result";
                int completed = transactions.execute(status -> complete(job, snapshot, researched));
                if (completed == 0) log.warn("Lost lease before completing ANALYSIS job {}", job.id());
                else log.info("Completed researched ANALYSIS job {}", job.id());
            } catch (Exception error) {
                String detail = error instanceof IllegalStateException
                    && error.getStackTrace().length > 0
                    && error.getStackTrace()[0].getClassName().equals(OpenAiZoneResearchAgent.class.getName())
                    ? error.getMessage() : error.getClass().getSimpleName();
                log.warn("ANALYSIS job {} failed at {}: {}", job.id(), stage, detail);
                failOwnedJob(job.id());
            }
        } catch (DataAccessException error) {
            log.warn("Analysis queue poll failed: {}", error.getClass().getSimpleName());
        }
    }

    private Job claim() {
        List<Job> jobs = jdbc.query("""
            WITH candidate AS (
                SELECT id FROM analysis_jobs
                WHERE job_type = 'ANALYSIS' AND attempts < max_attempts
                  AND ((status IN ('QUEUED', 'FAILED') AND run_after <= now())
                    OR status = 'WAITING_FOR_HANDLER')
                ORDER BY run_after, created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE analysis_jobs j SET status = 'PROCESSING', attempts = j.attempts + 1,
                locked_by = ?, lease_until = now() + interval '240 seconds', updated_at = now()
            FROM candidate WHERE j.id = candidate.id
            RETURNING j.id, j.input_snapshot::text
            """, (rs, row) -> new Job(rs.getObject("id", UUID.class), rs.getString("input_snapshot")), WORKER_ID);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private void validateSnapshot(JsonObject snapshot) {
        if (!snapshot.has("schemaVersion") || snapshot.get("schemaVersion").getAsInt() != 1
            || !snapshot.has("destinations") || snapshot.getAsJsonArray("destinations").isEmpty()
            || snapshot.getAsJsonArray("destinations").get(0).getAsJsonObject().get("name").getAsString().isBlank()
            || !snapshot.has("budget") || snapshot.getAsJsonObject("budget").get("maxMonthlyRent").getAsInt() <= 0
            || !snapshot.has("transport") || snapshot.getAsJsonObject("transport").get("primary").getAsString().isBlank()) {
            throw new IllegalArgumentException("Invalid ANALYSIS input snapshot");
        }
    }

    private Result buildResult(JsonObject snapshot, JsonObject researched) {
        JsonObject data = new JsonObject();
        JsonObject summary = new JsonObject();
        summary.addProperty("text", researched.get("summary").getAsString());
        summary.addProperty("recommendedCount", 3);
        summary.addProperty("demo", false);
        data.add("summary", summary);
        JsonArray recommendations = new JsonArray();
        JsonArray sourceUrls = new JsonArray();
        JsonArray zoneNames = new JsonArray();
        List<String> names = new java.util.ArrayList<>();
        JsonArray researchedZones = researched.getAsJsonArray("recommendations");
        for (int i = 0; i < researchedZones.size(); i++) {
            JsonObject zone = researchedZones.get(i).getAsJsonObject();
            String name = zone.get("name").getAsString().trim();
            UUID zoneId = findOrCreateZone(name);
            names.add(name);
            zoneNames.add(name);
            JsonObject item = new JsonObject();
            item.addProperty("zoneId", zoneId.toString());
            item.addProperty("rank", i + 1);
            item.addProperty("zoneName", name);
            item.add("overallScore", JsonNull.INSTANCE);
            item.add("confidence", JsonNull.INSTANCE);
            JsonObject scores = new JsonObject();
            scores.add("commute", JsonNull.INSTANCE);
            scores.add("affordability", JsonNull.INSTANCE);
            scores.add("lifestyle", JsonNull.INSTANCE);
            item.add("scores", scores);
            item.add("pros", zone.getAsJsonArray("pros").deepCopy());
            item.add("cons", zone.getAsJsonArray("cons").deepCopy());
            item.addProperty("reasoning", zone.get("reasoning").getAsString());
            JsonObject commute = new JsonObject();
            commute.addProperty("mode", snapshot.getAsJsonObject("transport").get("primary").getAsString());
            commute.add("minutes", JsonNull.INSTANCE);
            commute.add("source", JsonNull.INSTANCE);
            item.add("commute", commute);
            item.add("nearbyPlaces", new JsonArray());
            item.add("properties", new JsonArray());
            item.addProperty("demo", false);
            item.add("sources", zone.getAsJsonArray("sources").deepCopy());
            for (var url : zone.getAsJsonArray("sources")) sourceUrls.add(url.deepCopy());
            recommendations.add(item);
        }
        data.add("recommendations", recommendations);
        data.add("sources", sourceUrls);
        JsonArray limitations = new JsonArray();
        limitations.add("Web research is not a verified rent or commute measurement.");
        limitations.add("Scores, rent and travel times are unavailable without verified data.");
        data.add("dataLimitations", limitations);
        JsonObject preview = new JsonObject();
        preview.addProperty("title", "ผลค้นหาย่านที่น่าสนใจ");
        preview.add("zoneNames", zoneNames);
        preview.addProperty("demo", false);
        return new Result(data, preview, names);
    }

    private UUID findOrCreateZone(String name) {
        List<UUID> existing = jdbc.query(
            "SELECT id FROM zones WHERE name_th = ? ORDER BY is_active DESC, id LIMIT 1",
            (rs, row) -> rs.getObject("id", UUID.class), name);
        if (!existing.isEmpty()) return existing.get(0);
        return jdbc.queryForObject(
            "INSERT INTO zones (slug, name_th, is_active) VALUES (?, ?, false) RETURNING id",
            UUID.class, "researched-" + UUID.randomUUID(), name);
    }

    private int complete(Job job, JsonObject snapshot, JsonObject researched) {
        List<Boolean> owned = jdbc.query("""
            SELECT j.profile_version = s.profile_version AND s.closed_at IS NULL AS current_profile
            FROM analysis_jobs j JOIN conversation_sessions s ON s.id = j.session_id
            WHERE j.id = ? AND j.job_type = 'ANALYSIS' AND j.status = 'PROCESSING'
              AND j.locked_by = ? AND j.lease_until > now()
            FOR UPDATE OF j, s
            """, (rs, row) -> rs.getBoolean("current_profile"), job.id(), WORKER_ID);
        if (owned.isEmpty()) return 0;
        Result result = buildResult(snapshot, researched);
        UUID resultId = jdbc.queryForObject("""
            INSERT INTO analysis_results (job_id, profile_snapshot, result_schema_version,
                                          scoring_version, result_data, line_preview)
            VALUES (?, ?::jsonb, 1, 'web-research-unscored-v1', ?::jsonb, ?::jsonb)
            RETURNING id
            """, UUID.class, job.id(), snapshot.toString(), result.data().toString(), result.preview().toString());
        jdbc.update("""
            UPDATE analysis_jobs SET status = 'COMPLETED', progress = 100,
                progress_detail = jsonb_set(progress_detail, '{demo}', 'false'::jsonb, true),
                lease_until = NULL, locked_by = NULL, last_error = NULL,
                completed_at = now(), updated_at = now()
            WHERE id = ?
            """, job.id());
        jdbc.update("""
            UPDATE notification_outbox SET status = 'FAILED',
                last_error = 'Superseded by completed analysis', updated_at = now()
            WHERE idempotency_key = 'analysis-ack:' || ?::text
              AND notification_type = 'ANALYSIS_ACKNOWLEDGED' AND status = 'PENDING'
            """, job.id());
        if (owned.get(0)) {
            jdbc.update("""
                UPDATE conversation_sessions SET state = 'COMPLETED', updated_at = now()
                WHERE id = (SELECT session_id FROM analysis_jobs WHERE id = ?)
                """, job.id());
            jdbc.update("""
                INSERT INTO notification_outbox
                    (user_id, session_id, analysis_result_id, idempotency_key, notification_type, payload)
                SELECT s.user_id, s.id, ?, 'analysis-complete:' || j.id,
                       'ANALYSIS_COMPLETED', ?::jsonb
                FROM analysis_jobs j JOIN conversation_sessions s ON s.id = j.session_id
                WHERE j.id = ? ON CONFLICT (idempotency_key) DO NOTHING
                """, resultId, flexPayload(result).toString(), job.id());
        }
        return 1;
    }

    private JsonObject flexPayload(Result result) {
        JsonObject payload = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject flex = new JsonObject();
        flex.addProperty("type", "flex");
        flex.addProperty("altText", "ผลค้นหาย่านที่น่าสนใจ: " + String.join(", ", result.names()));
        JsonObject bubble = new JsonObject();
        bubble.addProperty("type", "bubble");
        JsonObject body = new JsonObject();
        body.addProperty("type", "box");
        body.addProperty("layout", "vertical");
        body.addProperty("spacing", "md");
        JsonArray contents = new JsonArray();
        contents.add(flexText("ผลค้นหาย่านที่น่าสนใจ", true));
        JsonArray recommendations = result.data().getAsJsonArray("recommendations");
        for (int i = 0; i < recommendations.size(); i++) {
            JsonObject zone = recommendations.get(i).getAsJsonObject();
            contents.add(flexText((i + 1) + ". " + zone.get("zoneName").getAsString(), true));
            contents.add(flexText(zone.get("reasoning").getAsString(), false));
            contents.add(flexText("ข้อควรพิจารณา: " + zone.getAsJsonArray("cons").get(0).getAsString(), false));
            JsonObject source = flexText("ดูแหล่งข้อมูล", false);
            JsonObject action = new JsonObject();
            action.addProperty("type", "uri");
            action.addProperty("label", "ดูแหล่งข้อมูล");
            action.addProperty("uri", zone.getAsJsonArray("sources").get(0).getAsString());
            source.add("action", action);
            contents.add(source);
        }
        contents.add(flexText("คะแนน ค่าเช่า และเวลาเดินทางยังไม่ได้ยืนยัน", false));
        body.add("contents", contents);
        bubble.add("body", body);
        flex.add("contents", bubble);
        messages.add(flex);
        payload.add("messages", messages);
        return payload;
    }

    private JsonObject flexText(String text, boolean heading) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        item.addProperty("wrap", true);
        item.addProperty("size", heading ? "md" : "sm");
        if (heading) item.addProperty("weight", "bold");
        return item;
    }

    private void recoverExpiredLease() {
        transactions.execute(status -> jdbc.update("""
            WITH candidate AS (
                SELECT id FROM analysis_jobs
                WHERE job_type = 'ANALYSIS'
                  AND status IN ('PROCESSING', 'COLLECTING', 'SCORING', 'EXPLAINING')
                  AND lease_until <= now()
                ORDER BY lease_until, id FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE analysis_jobs j SET status = 'FAILED', run_after = now() + %s,
                lease_until = NULL, locked_by = NULL,
                last_error = 'Worker lease expired', updated_at = now()
            FROM candidate WHERE j.id = candidate.id
            """.formatted(RETRY_DELAY)));
    }

    private void failOwnedJob(UUID jobId) {
        try {
            jdbc.update("""
                UPDATE analysis_jobs
                SET status = 'FAILED', run_after = now() + %s,
                    lease_until = NULL, locked_by = NULL,
                     last_error = 'Research analysis failed', updated_at = now()
                WHERE id = ? AND job_type = 'ANALYSIS' AND status = 'PROCESSING'
                  AND locked_by = ? AND lease_until > now()
                """.formatted(RETRY_DELAY), jobId, WORKER_ID);
        } catch (DataAccessException error) {
            log.warn("Could not record failure for analysis job {}: {}", jobId, error.getClass().getSimpleName());
        }
    }

    private void enqueueTerminalFailures() {
        jdbc.update("""
            INSERT INTO notification_outbox
                (user_id, session_id, idempotency_key, notification_type, payload)
            SELECT s.user_id, s.id, 'analysis-failed:' || j.id, 'ANALYSIS_FAILED',
                   '{"messages":[{"type":"text","text":"การวิเคราะห์ย่านไม่สำเร็จในรอบนี้ พิมพ์ retry เพื่อลองอีกครั้ง"}]}'::jsonb
            FROM analysis_jobs j JOIN conversation_sessions s ON s.id = j.session_id
            WHERE j.job_type = 'ANALYSIS' AND j.status = 'FAILED' AND j.attempts >= j.max_attempts
              AND j.profile_version = s.profile_version AND s.closed_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM analysis_jobs newer
                  WHERE newer.session_id = j.session_id AND newer.job_type = 'ANALYSIS'
                    AND newer.profile_version = j.profile_version
                    AND (newer.created_at, newer.id) > (j.created_at, j.id)
              )
            ON CONFLICT (idempotency_key) DO NOTHING
            """);
    }
}
