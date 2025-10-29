package com.places.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class ApiService {
    private final HttpClient httpClient;

    public ApiService() {
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    public CompletableFuture<String> getAsync(String url) {
        return getAsync(url, "application/json");
    }

    public CompletableFuture<String> getAsync(String url, String acceptHeader) {
        try {
            String encodedUrl = URI.create(url).toASCIIString();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(encodedUrl))
                    .header("Accept", acceptHeader);

            if (url.contains("wikipedia.org")) {
                requestBuilder.header("User-Agent", "PlaceFinderApp/1.0 (https://github.com/yourusername/placefinder; abobus@student.com)");
            }

            HttpRequest request = requestBuilder.GET().build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .exceptionally(ex -> {
                        System.err.println("Error for request to " + url + ": " + ex.getMessage());
                        return "{}";
                    });
        } catch (Exception e) {
            System.err.println("Error creating request for URL: " + url + " - " + e.getMessage());
            return CompletableFuture.completedFuture("{}");
        }
    }
}