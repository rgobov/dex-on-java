package com.example.dex.bridge;

import com.example.dex.l2.mempool.Mempool;
import com.example.dex.models.ChainTransaction;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TonBridgePoller {

    private final TonBridge bridge;
    private final Mempool mempool;
    private final Set<String> processedDepositIds;
    private final ScheduledExecutorService executor;
    private volatile boolean running;

    public TonBridgePoller(TonBridge bridge, Mempool mempool, Set<String> processedDepositIds) {
        this.bridge = bridge;
        this.mempool = mempool;
        this.processedDepositIds = processedDepositIds;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ton-bridge-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;
        executor.scheduleAtFixedRate(this::poll, 2000, 1000, TimeUnit.MILLISECONDS);
        System.out.println("[TON_BRIDGE_POLLER] Started polling TonBridge for deposits");
    }

    public void stop() {
        running = false;
        executor.shutdown();
    }

    void poll() {
        try {
            List<ChainTransaction> deposits = bridge.drainOutbox();
            for (ChainTransaction tx : deposits) {
                String depositId = tx.getOrderId();
                if (depositId != null && !processedDepositIds.contains(depositId)) {
                    mempool.add(tx);
                    processedDepositIds.add(depositId);
                    System.out.println("[TON_BRIDGE_POLLER] Deposit " + depositId
                            + " for " + tx.getUserId() + " amount=" + tx.getAmount()
                            + " → mempool");
                }
            }
        } catch (Exception e) {
            System.err.println("[TON_BRIDGE_POLLER] Error: " + e.getMessage());
        }
    }

    public Set<String> getProcessedDepositIds() {
        return processedDepositIds;
    }
}
