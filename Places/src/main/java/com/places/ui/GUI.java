package com.places.ui;

import com.places.model.Location;
import com.places.model.Weather;
import com.places.model.WikipediaArticle;
import com.places.service.LocationService;
import com.places.service.WeatherService;
import com.places.service.WikipediaService;

import javax.swing.*;
import java.awt.*;
import java.util.AbstractMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GUI {
    private final LocationService locationService;
    private final WeatherService weatherService;
    private final WikipediaService wikipediaService;

    private JFrame frame;
    private JTextField searchField;
    private JButton searchButton;
    private JComboBox<Location> locationComboBox;
    private JTextArea resultArea;
    private JPanel mainPanel;
    private DefaultComboBoxModel<Location> locationModel;

    public GUI(LocationService locationService, WeatherService weatherService, WikipediaService wikipediaService) {
        this.locationService = locationService;
        this.weatherService = weatherService;
        this.wikipediaService = wikipediaService;
        initialize();
    }

    private void initialize() {
        frame = new JFrame("Place Finder");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 800);

        mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel searchPanel = new JPanel(new BorderLayout(5, 5));
        searchField = new JTextField();
        searchButton = new JButton("Search");

        searchPanel.add(new JLabel("Enter place name:"), BorderLayout.NORTH);
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);

        JPanel locationPanel = new JPanel(new BorderLayout(5, 5));
        locationModel = new DefaultComboBoxModel<>();
        locationComboBox = new JComboBox<>(locationModel);
        locationComboBox.setEnabled(false);

        locationPanel.add(new JLabel("Select location:"), BorderLayout.NORTH);
        locationPanel.add(locationComboBox, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        topPanel.add(searchPanel);
        topPanel.add(locationPanel);

        resultArea = new JTextArea();
        resultArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(resultArea);

        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        frame.add(mainPanel);

        setupEventHandlers();
    }

    private void setupEventHandlers() {
        searchButton.addActionListener(e -> searchLocations());
        locationComboBox.addActionListener(e -> {
            if (locationComboBox.getSelectedIndex() > 0) {
                Location selectedLocation = (Location) locationComboBox.getSelectedItem();
                if (selectedLocation != null) {
                    getLocationDetails(selectedLocation);
                }
            }
        });

        searchField.addActionListener(e -> searchLocations());
    }

    private void searchLocations() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) return;

        resultArea.setText("Searching locations...\n");
        searchButton.setEnabled(false);
        locationComboBox.setEnabled(false);

        locationService.searchLocations(query)
                .thenAccept(locations -> {
                    SwingUtilities.invokeLater(() -> {
                        locationModel.removeAllElements();
                        locationModel.addElement(new Location("Select location...", 0, 0, "", ""));

                        if (locations.isEmpty()) {
                            resultArea.setText("No locations found.\n");
                        } else {
                            for (Location location : locations) {
                                locationModel.addElement(location);
                            }
                            resultArea.setText("Found " + locations.size() + " locations. Please select one.\n");
                            locationComboBox.setEnabled(true);
                        }
                        searchButton.setEnabled(true);
                    });
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        resultArea.setText("Error searching locations: " + ex.getMessage() + "\n");
                        searchButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void getLocationDetails(Location location) {
        resultArea.setText("Loading information for: " + location.getName() + "\n");
        locationComboBox.setEnabled(false);

        CompletableFuture<Weather> weatherFuture = weatherService.getWeather(location.getLat(), location.getLon());
        CompletableFuture<List<WikipediaArticle>> articlesFuture = wikipediaService.getNearbyArticles(location.getLat(), location.getLon(), 10000);

        weatherFuture.thenCombine(articlesFuture, (weather, articles) -> {
                    SwingUtilities.invokeLater(() -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append("=== ").append(location.getName()).append(" ===\n\n");
                        sb.append("=== WEATHER ===\n").append(weather).append("\n\n");

                        sb.append("=== NEARBY WIKIPEDIA ARTICLES ===\n");
                        if (articles.isEmpty()) {
                            sb.append("No Wikipedia articles found nearby.\n");
                        } else {
                            for (WikipediaArticle article : articles) {
                                sb.append("• ").append(article).append("\n");
                            }
                        }
                        resultArea.setText(sb.toString());
                    });

                    if (articles.isEmpty()) {
                        return CompletableFuture.completedFuture(new AbstractMap.SimpleEntry<>(articles, List.of()));
                    }

                    List<CompletableFuture<String>> detailFutures = articles.stream()
                            .map(article -> wikipediaService.getArticleDetails(article.getPageId()))
                            .collect(Collectors.toList());

                    return CompletableFuture.allOf(detailFutures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> {
                                List<String> details = detailFutures.stream()
                                        .map(CompletableFuture::join)
                                        .collect(Collectors.toList());
                                return new AbstractMap.SimpleEntry<>(articles, details);
                            });
                })
                .thenCompose(entryFuture -> entryFuture)
                .thenAccept(entry -> {
                    List<WikipediaArticle> articles = entry.getKey();
                    List<String> details = (List<String>) entry.getValue();

                    SwingUtilities.invokeLater(() -> {
                        StringBuilder sb = new StringBuilder(resultArea.getText());

                        sb.append("\n=== ARTICLE DETAILS ===\n");
                        if (articles.isEmpty()) {
                            sb.append("No article details available.\n");
                        } else {
                            for (int i = 0; i < articles.size(); i++) {
                                sb.append("\n--- ").append(articles.get(i).getTitle()).append(" ---\n");
                                String detail = details.get(i);
                                if (detail.contains("\n\nImage:")) {
                                    detail = detail.substring(0, detail.indexOf("\n\nImage:"));
                                }
                                sb.append(detail).append("\n");
                            }
                        }
                        resultArea.setText(sb.toString());
                        locationComboBox.setEnabled(true);
                    });
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        resultArea.setText("Error loading details: " + ex.getMessage() + "\n");
                        locationComboBox.setEnabled(true);
                    });
                    return null;
                });
    }

    public void show() {
        frame.setVisible(true);
    }
}