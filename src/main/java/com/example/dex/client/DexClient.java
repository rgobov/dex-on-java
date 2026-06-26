package com.example.dex.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class DexClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final List<String> validatorUrls;
    private int currentIndex = 0;

    public DexClient(List<String> validatorUrls) {
        this.validatorUrls = List.copyOf(validatorUrls);
    }

    /** Returns the current (or first available) validator URL */
    public synchronized String getEndpoint() {
        return validatorUrls.get(currentIndex);
    }

    /** Rotates to the next validator (failover) */
    public synchronized void failover() {
        currentIndex = (currentIndex + 1) % validatorUrls.size();
        System.out.println("[CLIENT] Failing over to " + getEndpoint());
    }

    /** Sends a request to the current endpoint, failing over if needed */
    public String sendRequest(String method, String path, Object body) throws Exception {
        for (int attempt = 0; attempt < validatorUrls.size(); attempt++) {
            String url = getEndpoint() + path;
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5));

                if ("GET".equalsIgnoreCase(method)) {
                    reqBuilder.GET();
                } else {
                    String json = body instanceof String s ? s : MAPPER.writeValueAsString(body);
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(json))
                            .header("Content-Type", "application/json");
                }

                HttpResponse<String> resp = HTTP.send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                System.err.println("[CLIENT] HTTP " + resp.statusCode() + " from " + url);
            } catch (Exception e) {
                System.err.println("[CLIENT] Connection failed to " + url + ": " + e.getMessage());
            }
            failover();
        }
        throw new RuntimeException("All validators unreachable");
    }

    // === API Methods ===

    public Map<String, Object> getState() throws Exception {
        return parseMap(sendRequest("GET", "/api/state", null));
    }

    public Map<String, Object> deposit(String userId, double amount) throws Exception {
        return parseMap(sendRequest("POST", "/api/deposit",
                Map.of("userId", userId, "amount", amount)));
    }

    public Map<String, Object> withdraw(String userId, double amount) throws Exception {
        return parseMap(sendRequest("POST", "/api/withdraw",
                Map.of("userId", userId, "amount", amount)));
    }

    public Map<String, Object> placeOrder(String userId, boolean isBuy, double price,
                                           double amount, double leverage, boolean isIsolated) throws Exception {
        return parseMap(sendRequest("POST", "/api/order", Map.of(
                "userId", userId, "isBuy", isBuy, "price", price,
                "amount", amount, "leverage", leverage, "isIsolated", isIsolated)));
    }

    public Map<String, Object> cancelOrder(String userId, String orderId) throws Exception {
        return parseMap(sendRequest("POST", "/api/cancel",
                Map.of("userId", userId, "orderId", orderId)));
    }

    public Map<String, Object> getOrderBook() throws Exception {
        return parseMap(sendRequest("GET", "/api/orderbook", null));
    }

    public List<Map<String, Object>> getTrades() throws Exception {
        String json = sendRequest("GET", "/api/trades", null);
        return MAPPER.readValue(json, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }
}
