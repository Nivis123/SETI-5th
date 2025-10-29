package com.places.model;

public class WikipediaArticle {
    private int pageId;
    private String title;
    private double lat;
    private double lon;
    private double distance;

    public WikipediaArticle(int pageId, String title, double lat, double lon, double distance) {
        this.pageId = pageId;
        this.title = title;
        this.lat = lat;
        this.lon = lon;
        this.distance = distance;
    }

    public int getPageId() { return pageId; }
    public String getTitle() { return title; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public double getDistance() { return distance; }

    @Override
    public String toString() {
        return String.format("%s (Distance: %.1f m)", title, distance);
    }
}