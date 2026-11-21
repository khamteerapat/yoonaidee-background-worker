package com.tesseractone.yoonaidee_bg_worker;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One-instance public Nominatim client. Deploy only one worker replica with this endpoint. */
@Component
public class NominatimGeocoder {
    public record Candidate(String id, String label, double latitude, double longitude,
                            String osmType, String osmId) {}
    public record SearchResult(List<Candidate> matches, Instant fetchedAt) {}

    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final long MIN_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, SearchResult> cache = new ConcurrentHashMap<>();
    private final URI baseUrl;
    private final String userAgent;
    private long lastRequestNanos;

    public NominatimGeocoder(
        @Value("${nominatim.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
        @Value("${nominatim.user-agent:}") String userAgent
    ) {
        this.baseUrl = URI.create(baseUrl.replaceAll("/+$", "") + "/");
        this.userAgent = userAgent;
    }

    public synchronized SearchResult search(String placeText) {
        if (placeText == null || placeText.isBlank() || placeText.length() > 200) {
            throw new IllegalArgumentException("Destination must contain 1–200 characters");
        }
        String query = placeText.trim();
        String cacheKey = query.toLowerCase(Locale.ROOT);
        SearchResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalStateException("Set NOMINATIM_USER_AGENT to an app name and contact URL or email");
        }
        waitForRateLimit();
        String params = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&format=jsonv2&countrycodes=th&addressdetails=1&limit=5&accept-language=th,en";
        URI uri = baseUrl.resolve("search?" + params);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET().build();
        lastRequestNanos = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Nominatim request failed with HTTP " + response.statusCode());
            }
            JsonArray rows = JsonParser.parseString(response.body()).getAsJsonArray();
            if (rows.size() > 5) {
                throw new IllegalStateException("Nominatim returned more than five matches");
            }
            List<Candidate> matches = new ArrayList<>();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                double lat = Double.parseDouble(row.get("lat").getAsString());
                double lon = Double.parseDouble(row.get("lon").getAsString());
                if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90
                    || lon < -180 || lon > 180) {
                    throw new IllegalStateException("Nominatim returned invalid coordinates");
                }
                String label = row.get("display_name").getAsString();
                if (label.isBlank()) {
                    throw new IllegalStateException("Nominatim returned an empty place label");
                }
                String osmType = row.has("osm_type") ? row.get("osm_type").getAsString() : "";
                String osmId = row.has("osm_id") ? row.get("osm_id").getAsString() : "";
                matches.add(new Candidate(Integer.toString(matches.size()), label, lat, lon, osmType, osmId));
            }
            SearchResult result = new SearchResult(List.copyOf(matches), Instant.now());
            if (cache.size() >= 1000) cache.clear();
            cache.put(cacheKey, result);
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("Nominatim network request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Nominatim request interrupted", error);
        }
    }

    private void waitForRateLimit() {
        long remaining = MIN_INTERVAL_NANOS - (System.nanoTime() - lastRequestNanos);
        if (lastRequestNanos == 0 || remaining <= 0) return;
        try {
            Thread.sleep(Duration.ofNanos(remaining));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Nominatim rate limit wait interrupted", error);
        }
    }
}
