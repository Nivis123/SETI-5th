package com.places.service;

import com.places.model.Weather;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.Locale;

public class WeatherService {
    private final ApiService apiService;
    private final ObjectMapper mapper;
    private final String apiKey = "565a61e109da7ff7c68b597566397181";

    public WeatherService(ApiService apiService) {
        this.apiService = apiService;
        this.mapper = new ObjectMapper();
    }

    public CompletableFuture<Weather> getWeather(double lat, double lon) {
        String url = String.format(
                Locale.US,
                "https://api.openweathermap.org/data/2.5/weather?lat=%.4f&lon=%.4f&appid=%s&units=metric&lang=ru",
                lat, lon, apiKey
        );

        return apiService.getAsync(url)
                .thenApply(response -> {
                    try {
                        JsonNode root = mapper.readTree(response);
                        JsonNode weather = root.path("weather").get(0);
                        JsonNode main = root.path("main");
                        JsonNode wind = root.path("wind");

                        String description = weather.path("description").asText();
                        double temperature = main.path("temp").asDouble();
                        double feelsLike = main.path("feels_like").asDouble();
                        int humidity = main.path("humidity").asInt();
                        double windSpeed = wind.path("speed").asDouble();

                        return new Weather(description, temperature, feelsLike, humidity, windSpeed);
                    } catch (Exception e) {
                        System.err.println("Error of weather parsing: " + e.getMessage());
                        return new Weather("Unknown", 0, 0, 0, 0);
                    }
                });
    }
}