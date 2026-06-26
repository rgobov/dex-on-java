package com.example.dex.l2.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class ValidatorNetwork {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String myId;
    private final int myPort;
    private final List<String> peerAddresses;
    private final Map<String, Consumer<ConsensusMessage>> handlers = new ConcurrentHashMap<>();
    private final List<ConsensusMessage> receivedMessages = new CopyOnWriteArrayList<>();
    private Javalin server;

    public ValidatorNetwork(String myId, int myPort, Map<String, Integer> validators) {
        this.myId = myId;
        this.myPort = myPort;
        this.peerAddresses = new CopyOnWriteArrayList<>();
        for (var entry : validators.entrySet()) {
            if (!entry.getKey().equals(myId)) {
                peerAddresses.add("http://localhost:" + entry.getValue());
            }
        }
    }

    public void start() {
        server = Javalin.create(config -> {}).start(myPort);
        server.post("/consensus", ctx -> {
            ConsensusMessage msg = ctx.bodyAsClass(ConsensusMessage.class);
            receivedMessages.add(msg);
            Consumer<ConsensusMessage> handler = handlers.get(msg.getType().name());
            if (handler != null) {
                handler.accept(msg);
            }
            ctx.status(HttpStatus.OK).result("ack");
        });
    }

    public void stop() {
        if (server != null) server.stop();
    }

    public void registerHandler(ConsensusMessage.Type type, Consumer<ConsensusMessage> handler) {
        handlers.put(type.name(), handler);
    }

    public void broadcast(ConsensusMessage msg) {
        String json;
        try {
            json = MAPPER.writeValueAsString(msg);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
        for (String peer : peerAddresses) {
            sendToPeer(peer, json);
        }
    }

    public void sendTo(String validatorId, ConsensusMessage msg) {
        // Find the peer by validator ID prefix match
        for (String peer : peerAddresses) {
            // Simple mapping: port 9001 for val-1, 9002 for val-2, etc.
            // In real impl, use a registry
            sendToPeer(peer, serialize(msg));
        }
    }

    private String serialize(ConsensusMessage msg) {
        try {
            return MAPPER.writeValueAsString(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendToPeer(String peerUrl, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(peerUrl + "/consensus"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[NET] Failed to send to " + peerUrl + ": " + e.getMessage());
        }
    }

    public List<ConsensusMessage> getReceivedMessages() {
        return List.copyOf(receivedMessages);
    }
}
