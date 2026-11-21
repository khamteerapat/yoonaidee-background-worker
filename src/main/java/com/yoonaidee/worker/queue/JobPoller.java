package com.yoonaidee.worker.queue;

import com.yoonaidee.worker.research.ResearchPipeline;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobPoller {
    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);
    private final JobRepository repo;
    private final ResearchPipeline pipeline;
    private final boolean enabled;
    private final boolean researchEnabled;
    private final String workerId;
    private final Duration lease;
    public JobPoller(JobRepository repo, ResearchPipeline pipeline,
       @Value("${worker.enabled:true}") boolean enabled,
       @Value("${research.enabled:false}") boolean researchEnabled,
       @Value("${worker.identifier:local-worker}") String workerId,
       @Value("${worker.lease-seconds:300}") long leaseSeconds) {
        this.repo=repo; this.pipeline=pipeline; this.enabled=enabled; this.researchEnabled=researchEnabled;
        this.workerId=workerId; this.lease=Duration.ofSeconds(leaseSeconds);
    }
    @Scheduled(fixedDelayString="${worker.poll-ms:3000}")
    public void poll() {
        if(!enabled || !researchEnabled) return;
        try {
            repo.claim(workerId,lease).ifPresent(job -> {
                try { pipeline.execute(job,workerId,lease); }
                catch (Exception ex) {
                    log.error("Job {} failed: {}",job.id(),ex.toString());
                    repo.failed(job.id(),workerId,ex.toString());
                }
            });
        } catch(Exception ex) { log.error("Polling failed",ex); }
    }
}
