package com.yoonaidee.worker.research;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZoneScorerTest {
 @Test void weightedAverage() {assertEquals(85.0,ZoneScorer.weighted(Map.of("commute",90.0,"gym",80.0),Map.of("commute",2.0,"gym",2.0)),0.00001);}
 @Test void ignoresMissingData() {assertEquals(90.0,ZoneScorer.weighted(Map.of("commute",90.0),Map.of("commute",2.0,"gym",3.0)),0.00001);}
 @Test void rejectsNoData() {assertThrows(IllegalArgumentException.class,()->ZoneScorer.weighted(Map.of(),Map.of("gym",1.0)));}
}
