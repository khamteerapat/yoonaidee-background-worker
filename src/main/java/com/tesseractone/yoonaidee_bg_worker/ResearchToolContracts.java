package com.tesseractone.yoonaidee_bg_worker;

import java.math.BigDecimal;
import java.util.List;

/** Research boundaries; destination geocoding is implemented by NominatimGeocoder. */
public final class ResearchToolContracts {
    private ResearchToolContracts() {}

    public record Coordinate(double latitude, double longitude) {}

    public record GeocodingRequest(String placeText) {}
    public record GeocodingResponse(List<GeocodedPlace> matches) {}
    public record GeocodedPlace(String label, Coordinate coordinate, String source) {}

    public record NearbyPoiRequest(Coordinate center, String category, int radiusMeters) {}
    public record NearbyPoiResponse(List<Poi> places) {}
    public record Poi(String externalId, String name, String category, Coordinate coordinate, String source) {}

    public record RoutingRequest(Coordinate origin, Coordinate destination, String travelMode) {}
    public record RoutingResponse(Integer durationMinutes, Integer distanceMeters, String source, String limitation) {}

    public record RentalListingsRequest(String zoneId, BigDecimal maxMonthlyRentThb) {}
    public record RentalListingsResponse(List<RentalListing> listings) {}
    public record RentalListing(String externalId, String title, BigDecimal monthlyRentThb,
                                String currency, String listingUrl, String source) {}
}
