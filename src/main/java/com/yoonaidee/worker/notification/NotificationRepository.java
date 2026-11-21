package com.yoonaidee.worker.notification;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class NotificationRepository {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public NotificationRepository(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
 public Optional<NotificationJob> claim(String worker, Duration lease){
  return tx.execute(s->{
   var jobs=jdbc.query("""
    WITH candidate AS (
     SELECT id FROM notification_outbox WHERE attempts<max_attempts
       AND ((status IN ('PENDING','FAILED') AND next_attempt_at<=now())
         OR (status='PROCESSING' AND lease_until<now()))
     ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
    )
    UPDATE notification_outbox o SET status='PROCESSING',attempts=o.attempts+1,
      lease_until=now()+(? * interval '1 second'),locked_by=?
    FROM candidate c WHERE o.id=c.id
    RETURNING o.id,o.user_id,o.payload
    """,(rs,n)->new Object[]{rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getString("payload")},lease.toSeconds(),worker);
   if(jobs.isEmpty())return Optional.<NotificationJob>empty();
   Object[] j=jobs.getFirst();
   String lineId=jdbc.queryForObject("SELECT line_user_id FROM users WHERE id=?",String.class,j[1]);
   return Optional.of(new NotificationJob((UUID)j[0],lineId,(String)j[2]));
  });
 }
 public void sent(UUID id,String worker){jdbc.update("UPDATE notification_outbox SET status='SENT',sent_at=now(),lease_until=null,locked_by=null WHERE id=? AND locked_by=?",id,worker);}
 public void failed(UUID id,String worker,String msg){jdbc.update("""
  UPDATE notification_outbox SET status='FAILED',last_error=left(?,2000),
  next_attempt_at=now()+(least(600,attempts*attempts*10)*interval '1 second'),
  lease_until=null,locked_by=null WHERE id=? AND locked_by=?
  """,msg,id,worker);}
}
