package com.example.dex.bridge;

import com.example.dex.models.ChainTransaction;
import com.example.dex.models.RollupBatch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mock TON bridge: simulates USDT deposits/withdrawals via TON blockchain.
 *
 * Unlike ArbitrumBridge:
 *   - No challenge window — withdrawals are immediate
 *   - No retryable tickets — just USDT transfer confirmation
 *   - No fraud proofs — TON finality is ~3 seconds
 */
public class TonBridge {

    public static class PendingDeposit {
        public final String depositId;
        public final String userId;
        public final double amount;
        public final long createdAt;
        public boolean confirmed;

        public PendingDeposit(String depositId, String userId, double amount) {
            this.depositId = depositId;
            this.userId = userId;
            this.amount = amount;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final List<PendingDeposit> pendingDeposits = new CopyOnWriteArrayList<>();
    private final List<ChainTransaction> outbox = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    // Simulated TON USDT balance tracking
    private final Map<String, Double> tonBalances = new ConcurrentHashMap<>();

    // Rollup batches posted to TON
    private final List<RollupBatch> rollupBatches = new CopyOnWriteArrayList<>();

    private final long confirmDelayMs;

    public TonBridge() {
        this(3000);
    }

    public TonBridge(long confirmDelayMs) {
        this.confirmDelayMs = confirmDelayMs;
    }

    public void start() {
        executor.scheduleAtFixedRate(this::processConfirmations, confirmDelayMs, confirmDelayMs, TimeUnit.MILLISECONDS);
        System.out.println("[TON_BRIDGE] Mock bridge started (confirm delay=" + confirmDelayMs + "ms)");
    }

    public void stop() {
        executor.shutdown();
    }

    // === Deposit: User sends USDT (TON) to our vault ===

    /**
     * Simulate user sending USDT from their TON wallet to our vault address.
     * After ~3 seconds the deposit is confirmed and appears in outbox.
     */
    public String depositUsdt(String userId, double amount) {
        String depositId = "ton-dep-" + UUID.randomUUID().toString().substring(0, 8);
        pendingDeposits.add(new PendingDeposit(depositId, userId, amount));
        System.out.println("[TON] Deposit pending " + depositId + ": " + amount + " USDT from " + userId);
        return depositId;
    }

    private void processConfirmations() {
        for (PendingDeposit dep : pendingDeposits) {
            if (!dep.confirmed && System.currentTimeMillis() - dep.createdAt >= confirmDelayMs) {
                ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                        .userId(dep.userId)
                        .amount(dep.amount)
                        .orderId(dep.depositId)
                        .timestamp(System.currentTimeMillis())
                        .build();
                outbox.add(tx);
                dep.confirmed = true;
                System.out.println("[TON] Deposit " + dep.depositId + " confirmed: " + dep.amount + " USDT for " + dep.userId);
            }
        }
    }

    // === Withdrawal: Send USDT from our vault to user's TON wallet ===

    /**
     * Send USDT to user's TON wallet immediately.
     * No challenge window — TON finality is ~3 seconds.
     */
    public void sendToWallet(String userId, double amount) {
        tonBalances.merge(userId, amount, Double::sum);
        System.out.println("[TON] Sent " + amount + " USDT to " + userId + " wallet");
    }

    // === Rollup batch posting ===

    public void postBatch(RollupBatch batch) {
        rollupBatches.add(batch);
        System.out.println("[TON] Rollup batch #" + batch.getBatchId()
                + " posted. StateRoot=" + batch.getStateRoot().substring(0, 12) + "...");
    }

    public List<RollupBatch> getRollupBatches() {
        return List.copyOf(rollupBatches);
    }

    // === Query ===

    public List<ChainTransaction> drainOutbox() {
        var copy = List.copyOf(outbox);
        outbox.clear();
        return copy;
    }

    public double getTonBalance(String address) {
        return tonBalances.getOrDefault(address, 0.0);
    }

    public void creditVault(String address, double amount) {
        tonBalances.merge(address, amount, Double::sum);
    }

    public List<PendingDeposit> getPendingDeposits() {
        return List.copyOf(pendingDeposits);
    }
}
