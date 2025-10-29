package com.places;

import com.places.service.ApiService;
import com.places.service.LocationService;
import com.places.service.WeatherService;
import com.places.service.WikipediaService;
import com.places.ui.GUI;

public class Main {
    public static void main(String[] args) {
        ApiService apiService = new ApiService();
        LocationService locationService = new LocationService(apiService);
        WeatherService weatherService = new WeatherService(apiService);
        WikipediaService wikipediaService = new WikipediaService(apiService);

        GUI gui = new GUI(locationService, weatherService, wikipediaService);
        gui.show();
    }
}