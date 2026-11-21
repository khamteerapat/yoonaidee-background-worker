package com.tesseractone.yoonaidee_bg_worker;

import java.util.List;
import java.util.UUID;

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
    private long lastHeartbeatMillis;

    public AnalysisJobConsumer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${worker.poll-delay-ms:1000}")
    public void poll() {
        try {
            int recovered = recoverExpiredLease();
            if (recovered > 0) {
                log.info("Recovered {} expired ANALYSIS lease(s)", recovered);
            }
            UUID jobId = transactions.execute(status -> claim());
            if (jobId == null) {
                logHeartbeat();
                return;
            }
            log.info("Claimed ANALYSIS job {}", jobId);
            try {
                // Research is not installed. Release the lease without spending a work attempt.
                int parked = jdbc.update("""
                    UPDATE analysis_jobs
                    SET status = 'WAITING_FOR_HANDLER', attempts = attempts - 1,
                        lease_until = NULL, locked_by = NULL, last_error = NULL,
                        updated_at = now()
                    WHERE id = ? AND job_type = 'ANALYSIS' AND status = 'PROCESSING'
                      AND locked_by = ? AND lease_until > now()
                    """, jobId, WORKER_ID);
                if (parked == 0) {
                    log.warn("Lost lease before parking analysis job {}", jobId);
                } else {
                    log.info("Parked ANALYSIS job {} in WAITING_FOR_HANDLER", jobId);
                    logHeartbeat();
                }
            } catch (DataAccessException error) {
                log.warn("Could not park analysis job {}: {}", jobId, error.getClass().getSimpleName());
                // A database outage may also prevent this update; lease recovery handles it later.
                failOwnedJob(jobId);
            }
        } catch (DataAccessException error) {
            log.warn("Analysis queue poll failed: {}", error.getClass().getSimpleName());
        }
    }

    private void logHeartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMillis >= 60_000) {
            log.info("ANALYSIS queue polling is healthy (worker {})", WORKER_ID);
            lastHeartbeatMillis = now;
        }
    }

    private UUID claim() {
        List<UUID> ids = jdbc.query("""
            WITH candidate AS (
                SELECT id FROM analysis_jobs
                WHERE job_type = 'ANALYSIS' AND status IN ('QUEUED', 'FAILED')
                  AND run_after <= now() AND attempts < max_attempts
                ORDER BY run_after, created_at, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE analysis_jobs AS job
            SET status = 'PROCESSING', attempts = job.attempts + 1,
                locked_by = ?, lease_until = now() + interval '60 seconds', updated_at = now()
            FROM candidate WHERE job.id = candidate.id
            RETURNING job.id
            """, (rs, row) -> rs.getObject("id", UUID.class), WORKER_ID);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private int recoverExpiredLease() {
        return transactions.execute(status -> jdbc.update("""
            WITH candidate AS (
                SELECT id FROM analysis_jobs
                WHERE job_type = 'ANALYSIS'
                  AND status IN ('PROCESSING', 'COLLECTING', 'SCORING', 'EXPLAINING')
                  AND lease_until <= now()
                ORDER BY lease_until, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE analysis_jobs AS job
            SET status = 'FAILED', run_after = now() + %s,
                lease_until = NULL, locked_by = NULL,
                last_error = 'Worker lease expired', updated_at = now()
            FROM candidate WHERE job.id = candidate.id
            """.formatted(RETRY_DELAY)));
    }

    private void failOwnedJob(UUID jobId) {
        try {
            jdbc.update("""
                UPDATE analysis_jobs
                SET status = 'FAILED', run_after = now() + %s,
                    lease_until = NULL, locked_by = NULL,
                    last_error = 'Failed to park job', updated_at = now()
                WHERE id = ? AND job_type = 'ANALYSIS' AND status = 'PROCESSING'
                  AND locked_by = ? AND lease_until > now()
                """.formatted(RETRY_DELAY), jobId, WORKER_ID);
        } catch (DataAccessException error) {
            log.warn("Could not record failure for analysis job {}: {}", jobId, error.getClass().getSimpleName());
        }
    }
}
