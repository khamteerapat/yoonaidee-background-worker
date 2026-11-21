package com.yoonaidee.worker.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JobRepository(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }

    public Optional<AnalysisJob> claim(String workerId, Duration lease) {
        return tx.execute(status -> {
            var rows = jdbc.query("""
              WITH candidate AS (
                SELECT id FROM analysis_jobs
                WHERE job_type = 'ANALYSIS'
                  AND attempts < max_attempts
                  AND ((status IN ('QUEUED','FAILED') AND run_after <= now())
                       OR (status IN ('PROCESSING','COLLECTING','SCORING','EXPLAINING') AND lease_until < now()))
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
              )
              UPDATE analysis_jobs j SET status='PROCESSING', attempts=j.attempts+1,
                lease_until=now()+(? * interval '1 second'), locked_by=?, updated_at=now()
              FROM candidate c WHERE j.id=c.id
              RETURNING j.id,j.session_id,j.profile_version,j.input_snapshot,j.attempts
              """, (rs, n) -> map(rs), lease.toSeconds(), workerId);
            return rows.stream().findFirst();
        });
    }
    private AnalysisJob map(ResultSet r) throws SQLException {
        return new AnalysisJob(r.getObject("id", UUID.class),r.getObject("session_id",UUID.class),
            r.getInt("profile_version"), r.getString("input_snapshot"),r.getInt("attempts"));
    }
    public void step(UUID jobId, String workerId, String phase, int progress, Duration lease) {
        int rows=jdbc.update("""
          UPDATE analysis_jobs SET status=?, progress=?, updated_at=now(),
          lease_until=now()+(? * interval '1 second')
          WHERE id=? AND locked_by=? AND status IN ('PROCESSING','COLLECTING','SCORING','EXPLAINING')
            AND lease_until > now()
          """, phase, progress, lease.toSeconds(), jobId, workerId);
        if(rows != 1) throw new IllegalStateException("Job lease lost: " + jobId);
    }
    public void failed(UUID jobId, String workerId, String message) {
        jdbc.update("""
          UPDATE analysis_jobs SET status='FAILED', last_error=left(?,2000), updated_at=now(),
          run_after=now()+ (least(300, attempts * attempts * 10) * interval '1 second'),
          lease_until=null,locked_by=null
          WHERE id=? AND locked_by=? AND status IN ('PROCESSING','COLLECTING','SCORING','EXPLAINING')
          """,message,jobId,workerId);
    }
}
