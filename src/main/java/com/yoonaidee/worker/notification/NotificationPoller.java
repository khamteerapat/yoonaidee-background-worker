package com.yoonaidee.worker.notification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;

@Component
public class NotificationPoller {
 private static final Logger log=LoggerFactory.getLogger(NotificationPoller.class);
 private final NotificationRepository repo;private final LinePushClient line;private final ObjectMapper mapper;
 private final boolean enabled;private final String workerId;
 public NotificationPoller(NotificationRepository repo,LinePushClient line,ObjectMapper mapper,
    @Value("${worker.enabled:true}") boolean enabled,@Value("${worker.identifier:local-worker}") String workerId){
  this.repo=repo;this.line=line;this.mapper=mapper;this.enabled=enabled;this.workerId=workerId;}
 @Scheduled(fixedDelayString="${worker.poll-ms:3000}")
 public void poll(){
  if(!enabled)return;
  try{repo.claim(workerId,Duration.ofMinutes(2)).ifPresent(job->{
    try{
      JsonNode data=mapper.readTree(job.payloadJson());
      String preview=data.path("preview").asText("วิเคราะห์ทำเลเสร็จแล้ว");
      String url=data.path("resultUrl").asText("");
      line.send(job.lineUserId(),preview,url,job.id().toString());
      repo.sent(job.id(),workerId);
    }catch(Exception ex){log.warn("LINE notification {} failed",job.id(),ex);repo.failed(job.id(),workerId,ex.toString());}
  });}catch(Exception ex){log.error("Notification poll failed",ex);}
 }
}
