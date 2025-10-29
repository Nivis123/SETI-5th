package com.places.model;

public class Weather {
    private String description;
    private double temperature;
    private double feelsLike;
    private int humidity;
    private double windSpeed;

    public Weather(String description, double temperature, double feelsLike, int humidity, double windSpeed) {
        this.description = description;
        this.temperature = temperature;
        this.feelsLike = feelsLike;
        this.humidity = humidity;
        this.windSpeed = windSpeed;
    }

    @Override
    public String toString() {
        return String.format("%s, Temperature: %.1f°C (feels like %.1f°C), Humidity: %d%%, Wind: %.1f m/s",
                description, temperature, feelsLike, humidity, windSpeed);
    }
}