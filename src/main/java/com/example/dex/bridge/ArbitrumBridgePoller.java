package com.example.dex.bridge;

import com.example.dex.l2.mempool.Mempool;
import com.example.dex.models.ChainTransaction;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ArbitrumBridgePoller {

    private final ArbitrumBridge bridge;
    private final Mempool mempool;
    private final Set<String> processedTicketIds;
    private final ScheduledExecutorService executor;
    private volatile boolean running;

    public ArbitrumBridgePoller(ArbitrumBridge bridge, Mempool mempool, Set<String> processedTicketIds) {
        this.bridge = bridge;
        this.mempool = mempool;
        this.processedTicketIds = processedTicketIds;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bridge-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;
        executor.scheduleAtFixedRate(this::poll, 2000, 1000, TimeUnit.MILLISECONDS);
        System.out.println("[BRIDGE_POLLER] Started polling ArbitrumBridge for deposits");
    }

    public void stop() {
        running = false;
        executor.shutdown();
    }

    void poll() {
        try {
            List<ChainTransaction> deposits = bridge.drainOutbox();
            for (ChainTransaction tx : deposits) {
                String ticketId = tx.getOrderId();
                if (ticketId != null && !processedTicketIds.contains(ticketId)) {
                    mempool.add(tx);
                    processedTicketIds.add(ticketId);
                    System.out.println("[BRIDGE_POLLER] Deposit ticket " + ticketId
                            + " for " + tx.getUserId() + " amount=" + tx.getAmount()
                            + " → mempool");
                }
            }
        } catch (Exception e) {
            System.err.println("[BRIDGE_POLLER] Error: " + e.getMessage());
        }
    }

    public Set<String> getProcessedTicketIds() {
        return processedTicketIds;
    }
}
