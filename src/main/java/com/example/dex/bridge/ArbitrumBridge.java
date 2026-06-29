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
 * Mock Arbitrum bridge: simulates L1<->L2 messaging with retryable tickets
 * and a challenge/dispute window for withdrawals.
 */
public class ArbitrumBridge {

    public static class RetryableTicket {
        public final String ticketId;
        public final String from;
        public final double amount;
        public final long createdAt;
        public boolean redeemed;

        public RetryableTicket(String ticketId, String from, double amount) {
            this.ticketId = ticketId;
            this.from = from;
            this.amount = amount;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public static class WithdrawalRequest {
        public final String requestId;
        public final String user;
        public final double amount;
        public final long createdAt;
        public boolean challenged;
        public boolean finalized;

        public WithdrawalRequest(String requestId, String user, double amount) {
            this.requestId = requestId;
            this.user = user;
            this.amount = amount;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final List<RetryableTicket> tickets = new CopyOnWriteArrayList<>();
    private final List<WithdrawalRequest> withdrawals = new CopyOnWriteArrayList<>();
    private final List<ChainTransaction> outbox = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    // Simulated L1 vault balance tracking
    private final Map<String, Double> l1Balances = new ConcurrentHashMap<>();

    // Rollup batches posted to L1
    private final List<RollupBatch> rollupBatches = new CopyOnWriteArrayList<>();

    // Challenge window in ms (real Arbitrum: ~7 days = 604800000 ms)
    private final long challengeWindowMs;
    private final long ticketCheckMs;
    private final long finalizeCheckMs;
    private final long ticketRedeemDelayMs;

    public ArbitrumBridge(long challengeWindowMs) {
        this(challengeWindowMs, 2000, 5000, 2000);
    }

    public ArbitrumBridge(long challengeWindowMs, long ticketCheckMs, long finalizeCheckMs, long ticketRedeemDelayMs) {
        this.challengeWindowMs = challengeWindowMs;
        this.ticketCheckMs = ticketCheckMs;
        this.finalizeCheckMs = finalizeCheckMs;
        this.ticketRedeemDelayMs = ticketRedeemDelayMs;
    }

    public void start() {
        executor.scheduleAtFixedRate(this::processTickets, ticketCheckMs, ticketCheckMs, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::finalizeWithdrawals, finalizeCheckMs, finalizeCheckMs, TimeUnit.MILLISECONDS);
        System.out.println("[ARBITRUM_BRIDGE] Mock bridge started (challenge window=" + challengeWindowMs + "ms)");
    }

    public void stop() {
        executor.shutdown();
    }

    // === L1 -> L2: Deposit via Retryable Ticket ===

    public void createRetryableTicket(String fromAddress, double amount) {
        String ticketId = "ticket-" + UUID.randomUUID().toString().substring(0, 8);
        l1Balances.merge(fromAddress, -amount, Double::sum);
        tickets.add(new RetryableTicket(ticketId, fromAddress, amount));
        System.out.println("[ARBITRUM] Retryable ticket " + ticketId + " created: " + amount + " for " + fromAddress);
    }

    private void processTickets() {
        for (RetryableTicket ticket : tickets) {
                if (!ticket.redeemed && System.currentTimeMillis() - ticket.createdAt > ticketRedeemDelayMs) {
                // Auto-redeem after 2 seconds (simulated L1->L2 delay)
                ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                        .userId(ticket.from)
                        .amount(ticket.amount)
                        .orderId(ticket.ticketId)
                        .timestamp(System.currentTimeMillis())
                        .build();
                outbox.add(tx);
                ticket.redeemed = true;
                System.out.println("[ARBITRUM] Ticket " + ticket.ticketId + " auto-redeemed to L2");
            }
        }
    }

    // === L2 -> L1: Withdrawal ===

    public String initiateWithdrawal(String userId, double amount) {
        String requestId = "withdraw-" + UUID.randomUUID().toString().substring(0, 8);
        withdrawals.add(new WithdrawalRequest(requestId, userId, amount));
        System.out.println("[ARBITRUM] Withdrawal request " + requestId + " initiated: " + amount + " for " + userId);
        return requestId;
    }

    private void finalizeWithdrawals() {
        for (WithdrawalRequest req : withdrawals) {
            if (!req.finalized && !req.challenged
                    && System.currentTimeMillis() - req.createdAt > challengeWindowMs) {
                l1Balances.merge(req.user, req.amount, Double::sum);
                req.finalized = true;
                System.out.println("[ARBITRUM] Withdrawal " + req.requestId + " finalized on L1");
            }
        }
    }

    // === Dispute (challenge) a withdrawal ===

    public void challengeWithdrawal(String requestId) {
        for (WithdrawalRequest req : withdrawals) {
            if (req.requestId.equals(requestId) && !req.finalized) {
                req.challenged = true;
                System.out.println("[ARBITRUM] Withdrawal " + requestId + " CHALLENGED!");
                return;
            }
        }
    }

    // === L1: Rollup batch posting ===

    public void postBatch(RollupBatch batch) {
        rollupBatches.add(batch);
        System.out.println("[ARBITRUM] Rollup batch #" + batch.getBatchId()
                + " posted to L1. StateRoot=" + batch.getStateRoot().substring(0, 12) + "...");
    }

    public List<RollupBatch> getRollupBatches() {
        return List.copyOf(rollupBatches);
    }

    // === Query ===

    public List<RetryableTicket> getPendingTickets() {
        return tickets.stream().filter(t -> !t.redeemed).toList();
    }

    public List<ChainTransaction> drainOutbox() {
        var copy = List.copyOf(outbox);
        outbox.clear();
        return copy;
    }

    public List<WithdrawalRequest> getWithdrawals() {
        return List.copyOf(withdrawals);
    }

    public double getL1Balance(String address) {
        return l1Balances.getOrDefault(address, 0.0);
    }

    public void depositL1(String address, double amount) {
        l1Balances.merge(address, amount, Double::sum);
    }
}
