package com.places.service;

import com.places.model.Location;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

public class LocationService {
    private final ApiService apiService;
    private final ObjectMapper mapper;
    private final String apiKey = "d2faa875-a0a8-438e-bfd3-b9a831d40891";

    public LocationService(ApiService apiService) {
        this.apiService = apiService;
        this.mapper = new ObjectMapper();
    }

    public CompletableFuture<List<Location>> searchLocations(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
            String url = String.format(
                    Locale.US,
                    "https://graphhopper.com/api/1/geocode?q=%s&key=%s&limit=10",
                    encodedQuery, apiKey
            );

            return apiService.getAsync(url)
                    .thenApply(response -> {
                        try {
                            JsonNode root = mapper.readTree(response);
                            JsonNode hits = root.path("hits");
                            List<Location> locations = new ArrayList<>();

                            for (JsonNode hit : hits) {
                                String name = hit.path("name").asText();
                                double lat = hit.path("point").path("lat").asDouble();
                                double lon = hit.path("point").path("lng").asDouble();
                                String country = hit.path("country").asText();
                                String city = hit.path("city").asText();

                                if (city.isEmpty()) {
                                    city = name;
                                }

                                locations.add(new Location(name, lat, lon, country, city));
                            }

                            return locations;
                        } catch (Exception e) {
                            System.err.println("Error parsing locations: " + e.getMessage());
                            return new ArrayList<Location>();
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new ArrayList<Location>());
        }
    }
}