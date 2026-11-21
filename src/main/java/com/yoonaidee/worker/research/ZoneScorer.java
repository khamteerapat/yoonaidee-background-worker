package com.yoonaidee.worker.research;

import java.util.Map;

/** Deterministic normalized score; unknown features must be omitted, never silently scored zero. */
public final class ZoneScorer {
    private ZoneScorer() {}
    public static double weighted(Map<String,Double> scores,Map<String,Double> weights) {
        double total=0,weight=0;
        for(var entry:weights.entrySet()) {
            var score=scores.get(entry.getKey());
            var w=entry.getValue();
            if(score == null) continue;
            if(!Double.isFinite(score)||score<0||score>100||!Double.isFinite(w)||w<0)
                throw new IllegalArgumentException("Invalid score/weight for " + entry.getKey());
            total += score*w;weight += w;
        }
        if(weight == 0) throw new IllegalArgumentException("No available weighted features");
        return total/weight;
    }
}
