package com.yoonaidee.worker.research;

import org.springframework.stereotype.Component;

@Component
public class UnimplementedTools implements ResearchToolPort {
    @Override public void collect(String profileJson) {
        throw new UnsupportedOperationException("Implement real geocoding/POI/routing/rental adapters; no fake property listings");
    }
}
