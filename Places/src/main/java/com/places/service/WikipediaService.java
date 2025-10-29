package com.places.service;

import com.places.model.WikipediaArticle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

public class WikipediaService {
    private final ApiService apiService;
    private final ObjectMapper mapper;

    public WikipediaService(ApiService apiService) {
        this.apiService = apiService;
        this.mapper = new ObjectMapper();
    }

    public CompletableFuture<List<WikipediaArticle>> getNearbyArticles(double lat, double lon, int radius) {
        try {
            String coord = String.format(Locale.US, "%.4f|%.4f", lat, lon);
            String encodedCoord = URLEncoder.encode(coord, StandardCharsets.UTF_8.toString());

            String url = String.format(
                    "https://en.wikipedia.org/w/api.php?action=query&format=json&list=geosearch&gscoord=%s&gsradius=%d&gslimit=10",
                    encodedCoord, radius
            );

            return apiService.getAsync(url)
                    .thenApply(response -> {
                        try {
                            if (response.contains("Please set a user-agent") || response.contains("robot policy")) {
                                System.err.println("Wikipedia API requires proper User-Agent header");
                                return new ArrayList<WikipediaArticle>();
                            }

                            JsonNode root = mapper.readTree(response);

                            if (root.has("error")) {
                                System.err.println("Wikipedia API error: " + root.path("error").path("info").asText());
                                return new ArrayList<WikipediaArticle>();
                            }

                            JsonNode pages = root.path("query").path("geosearch");
                            List<WikipediaArticle> articles = new ArrayList<>();

                            for (JsonNode page : pages) {
                                String title = page.path("title").asText();
                                double pageLat = page.path("lat").asDouble();
                                double pageLon = page.path("lon").asDouble();
                                int pageId = page.path("pageid").asInt();
                                double distance = page.path("dist").asDouble();

                                articles.add(new WikipediaArticle(pageId, title, pageLat, pageLon, distance));
                            }

                            return articles;
                        } catch (Exception e) {
                            System.err.println("Error parsing Wikipedia articles: " + e.getMessage());
                            return new ArrayList<WikipediaArticle>();
                        }
                    });
        } catch (Exception e) {
            System.err.println("Error building Wikipedia URL: " + e.getMessage());
            return CompletableFuture.completedFuture(new ArrayList<WikipediaArticle>());
        }
    }

    public CompletableFuture<String> getArticleDetails(int pageId) {
        try {
            String props = URLEncoder.encode("extracts|pageimages", StandardCharsets.UTF_8.toString());

            String url = String.format(
                    "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=%s&exintro=true&explaintext=true&pageids=%d&pithumbsize=300",
                    props, pageId
            );

            return apiService.getAsync(url)
                    .thenApply(response -> {
                        try {
                            if (response.contains("Please set a user-agent") || response.contains("robot policy")) {
                                return "Wikipedia API requires proper User-Agent header";
                            }

                            JsonNode root = mapper.readTree(response);

                            if (root.has("error")) {
                                return "Error: " + root.path("error").path("info").asText();
                            }

                            JsonNode pages = root.path("query").path("pages").path(String.valueOf(pageId));

                            if (pages.has("missing")) {
                                return "Article not found";
                            }

                            String extract = pages.path("extract").asText();
                            if (extract.isEmpty()) {
                                extract = "No description available in English. Try switching to Russian Wikipedia.";
                            } else if (extract.length() > 300) {
                                extract = extract.substring(0, 300) + "...";
                            }

                            String imageUrl = "";
                            if (pages.has("thumbnail")) {
                                imageUrl = pages.path("thumbnail").path("source").asText();
                            }

                            return String.format("%s\n\nImage: %s", extract, imageUrl.isEmpty() ? "No image available" : "Available");
                        } catch (Exception e) {
                            System.err.println("Error parsing Wikipedia details for page " + pageId + ": " + e.getMessage());
                            return "No detailed information available";
                        }
                    });
        } catch (Exception e) {
            System.err.println("Error building Wikipedia details URL: " + e.getMessage());
            return CompletableFuture.completedFuture("Error loading article details");
        }
    }
}