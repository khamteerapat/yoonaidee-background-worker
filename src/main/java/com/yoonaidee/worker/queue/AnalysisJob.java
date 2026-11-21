package com.yoonaidee.worker.queue;

import java.util.UUID;

public record AnalysisJob(UUID id, UUID sessionId, int profileVersion, String inputSnapshot, int attempts) {}
