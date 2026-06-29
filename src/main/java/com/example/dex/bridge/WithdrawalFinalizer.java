package com.example.dex.bridge;

import com.example.dex.disruptor.StateExecutionHandler;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WithdrawalFinalizer {

    private final ArbitrumBridge bridge;
    private final StateExecutionHandler handler;
    private final ScheduledExecutorService executor;
    private volatile boolean running;

    public WithdrawalFinalizer(ArbitrumBridge bridge, StateExecutionHandler handler) {
        this.bridge = bridge;
        this.handler = handler;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "withdrawal-finalizer");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;
        executor.scheduleAtFixedRate(this::finalizePending, 5000, 3000, TimeUnit.MILLISECONDS);
        System.out.println("[WITHDRAWAL_FINALIZER] Started");
    }

    public void stop() {
        running = false;
        executor.shutdown();
    }

    void finalizePending() {
        if (!running) return;
        try {
            List<StateExecutionHandler.PendingWithdrawal> pending = handler.getPendingWithdrawals();
            if (pending.isEmpty()) return;

            for (StateExecutionHandler.PendingWithdrawal pw : List.copyOf(pending)) {
                String requestId = bridge.initiateWithdrawal(pw.userId, pw.amount);
                System.out.println("[WITHDRAWAL_FINALIZER] Initiated L1 withdrawal " + requestId
                        + " for " + pw.userId + " amount=" + pw.amount);
                handler.removePendingWithdrawal(pw);
            }
        } catch (Exception e) {
            System.err.println("[WITHDRAWAL_FINALIZER] Error: " + e.getMessage());
        }
    }
}
