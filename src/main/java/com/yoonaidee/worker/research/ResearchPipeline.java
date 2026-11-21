package com.yoonaidee.worker.research;

import com.yoonaidee.worker.queue.AnalysisJob;
import com.yoonaidee.worker.queue.JobRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResearchPipeline {
    private final JobRepository jobs;
    private final ResearchToolPort tools;
    private final boolean enabled;
    public ResearchPipeline(JobRepository jobs, ResearchToolPort tools,
            @Value("${research.enabled:false}") boolean enabled) {
        this.jobs=jobs;this.tools=tools;this.enabled=enabled;
    }
    public void execute(AnalysisJob job,String workerId,Duration lease) {
        if (!enabled) throw new IllegalStateException("Research pipeline disabled: set RESEARCH_ENABLED=true only after implementing real provider adapters");
        jobs.step(job.id(),workerId,"COLLECTING",10,lease);
        // Contract: job.inputSnapshot() contains the immutable JSON profile from Next.js.
        // Real implementation: validate profile, plan tool calls, fetch provider data,
        // normalize features, score zones/properties, explain factual trade-offs,
        // and atomically insert analysis_results + mark job complete + insert notification_outbox.
        tools.collect(job.inputSnapshot());
        throw new UnsupportedOperationException("Wire scoring, explanation and transactional result persistence before enabling production jobs");
    }
}
