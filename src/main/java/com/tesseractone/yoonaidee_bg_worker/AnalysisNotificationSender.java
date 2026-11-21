package com.tesseractone.yoonaidee_bg_worker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AnalysisNotificationSender {
    private static final Logger log = LoggerFactory.getLogger(AnalysisNotificationSender.class);
    private static final String WORKER_ID = UUID.randomUUID().toString();
    private static final URI PUSH_URI = URI.create("https://api.line.me/v2/bot/message/push");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String channelAccessToken;

    public AnalysisNotificationSender(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                      @Value("${line.channel-access-token:}") String channelAccessToken) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.channelAccessToken = channelAccessToken;
        if (channelAccessToken.isBlank()) {
            log.warn("LINE channel access token is unset; analysis notifications will remain pending");
        }
    }

    @Scheduled(fixedDelayString = "${worker.poll-delay-ms:1000}")
    public void poll() {
        if (channelAccessToken.isBlank()) {
            return;
        }
        try {
            recoverExpiredLease();
            cancelStaleCompletions();
            cancelStaleFailures();
            Notification notification = transactions.execute(status -> claim());
            if (notification == null) return;

            boolean accepted = false;
            int httpStatus = 0;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("to", notification.lineUserId());
                body.add("messages", JsonParser.parseString(notification.payload()).getAsJsonObject().getAsJsonArray("messages"));
                HttpRequest request = HttpRequest.newBuilder(PUSH_URI)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + channelAccessToken)
                    .header("X-Line-Retry-Key", notification.id().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                httpStatus = response.statusCode();
                accepted = httpStatus >= 200 && httpStatus < 300
                    || httpStatus == 409 && response.headers().firstValue("x-line-accepted-request-id").isPresent();
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("LINE analysis push failed for notification {}: {}", notification.id(), error.getClass().getSimpleName());
            }

            boolean sent = accepted;
            int updated = transactions.execute(status -> finish(notification, sent));
            if (updated == 0) {
                log.warn("Lost lease before recording LINE analysis push {}", notification.id());
            } else {
                log.info("LINE analysis push {}: HTTP {}, status {}", notification.id(), httpStatus,
                    sent ? "SENT" : notification.attempts() >= notification.maxAttempts() ? "FAILED" : "PENDING");
            }
        } catch (DataAccessException error) {
            log.warn("Analysis notification poll failed: {}", error.getClass().getSimpleName());
        }
    }

    private record Notification(UUID id, String lineUserId, String payload, int attempts, int maxAttempts) {}

    private Notification claim() {
        List<Notification> notifications = jdbc.query("""
            WITH candidate AS (
                SELECT n.id FROM notification_outbox n
                WHERE n.notification_type IN ('ANALYSIS_ACKNOWLEDGED', 'ANALYSIS_COMPLETED', 'ANALYSIS_FAILED')
                  AND n.status = 'PENDING' AND n.next_attempt_at <= now()
                  AND n.attempts < n.max_attempts
                  AND NOT EXISTS (
                      SELECT 1 FROM notification_outbox earlier
                      WHERE earlier.session_id = n.session_id AND earlier.status IN ('PENDING', 'PROCESSING')
                        AND (earlier.created_at, earlier.id) < (n.created_at, n.id)
                  )
                ORDER BY n.created_at, n.id
                FOR UPDATE OF n SKIP LOCKED LIMIT 1
            )
            UPDATE notification_outbox n
            SET status = 'PROCESSING', attempts = n.attempts + 1,
                locked_by = ?, lease_until = now() + interval '30 seconds', updated_at = now()
            FROM candidate WHERE n.id = candidate.id
            RETURNING n.id, (SELECT u.line_user_id FROM users u WHERE u.id = n.user_id) AS line_user_id,
                      n.payload::text AS payload, n.attempts, n.max_attempts
            """, (rs, row) -> new Notification(rs.getObject("id", UUID.class), rs.getString("line_user_id"),
                rs.getString("payload"), rs.getInt("attempts"), rs.getInt("max_attempts")), WORKER_ID);
        return notifications.isEmpty() ? null : notifications.get(0);
    }

    private int finish(Notification notification, boolean accepted) {
        return jdbc.update("""
            UPDATE notification_outbox
            SET status = CASE WHEN ? THEN 'SENT'
                              WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                next_attempt_at = now() + interval '10 seconds',
                sent_at = CASE WHEN ? THEN now() ELSE NULL END,
                last_error = CASE WHEN ? THEN NULL ELSE 'LINE push failed' END,
                locked_by = NULL, lease_until = NULL, updated_at = now()
            WHERE id = ? AND status = 'PROCESSING' AND locked_by = ? AND lease_until > now()
            """, accepted, accepted, accepted, notification.id(), WORKER_ID);
    }

    private void recoverExpiredLease() {
        jdbc.update("""
            WITH candidate AS (
                SELECT id FROM notification_outbox
                WHERE notification_type IN ('ANALYSIS_ACKNOWLEDGED', 'ANALYSIS_COMPLETED', 'ANALYSIS_FAILED')
                  AND status = 'PROCESSING' AND lease_until <= now()
                ORDER BY lease_until, id FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE notification_outbox n
            SET status = CASE WHEN n.attempts >= n.max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                next_attempt_at = now() + interval '10 seconds',
                last_error = 'LINE push lease expired',
                locked_by = NULL, lease_until = NULL, updated_at = now()
            FROM candidate WHERE n.id = candidate.id
            """);
    }

    private void cancelStaleCompletions() {
        jdbc.update("""
            UPDATE notification_outbox n
            SET status = 'FAILED', last_error = 'Superseded by newer profile',
                updated_at = now()
            FROM analysis_results r
            JOIN analysis_jobs j ON j.id = r.job_id
            JOIN conversation_sessions s ON s.id = j.session_id
            WHERE n.analysis_result_id = r.id AND n.notification_type = 'ANALYSIS_COMPLETED'
              AND n.status = 'PENDING'
              AND (j.profile_version IS DISTINCT FROM s.profile_version OR s.closed_at IS NOT NULL)
            """);
    }

    private void cancelStaleFailures() {
        jdbc.update("""
            UPDATE notification_outbox n
            SET status = 'FAILED', last_error = 'Superseded by newer analysis',
                updated_at = now()
            FROM analysis_jobs j
            JOIN conversation_sessions s ON s.id = j.session_id
            WHERE n.idempotency_key = 'analysis-failed:' || j.id
              AND n.notification_type = 'ANALYSIS_FAILED' AND n.status = 'PENDING'
              AND (j.profile_version IS DISTINCT FROM s.profile_version OR s.closed_at IS NOT NULL
                   OR EXISTS (
                       SELECT 1 FROM analysis_jobs newer
                       WHERE newer.session_id = j.session_id AND newer.job_type = 'ANALYSIS'
                         AND newer.profile_version = j.profile_version
                         AND (newer.created_at, newer.id) > (j.created_at, j.id)
                   ))
            """);
    }
}
