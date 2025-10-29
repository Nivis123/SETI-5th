package com.places.model;

public class Location {
    private String name;
    private double lat;
    private double lon;
    private String country;
    private String city;

    public Location(String name, double lat, double lon, String country, String city) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.country = country;
        this.city = city;
    }

    public String getName() { return name; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public String getCountry() { return country; }
    public String getCity() { return city; }

    @Override
    public String toString() {
        return String.format("%s (%s, %s)", name, city, country);
    }
}