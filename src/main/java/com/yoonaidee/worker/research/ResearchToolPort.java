package com.yoonaidee.worker.research;

/** Boundary for geocoding, Overpass POI, ORS routing, and licensed rental provider adapters. */
public interface ResearchToolPort { void collect(String profileJson); }
