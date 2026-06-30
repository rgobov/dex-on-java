package com.example.dex.bridge;

import com.example.dex.disruptor.StateExecutionHandler;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TonWithdrawalFinalizer {

    private final TonBridge bridge;
    private final StateExecutionHandler handler;
    private final ScheduledExecutorService executor;
    private volatile boolean running;

    public TonWithdrawalFinalizer(TonBridge bridge, StateExecutionHandler handler) {
        this.bridge = bridge;
        this.handler = handler;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ton-withdrawal-finalizer");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;
        executor.scheduleAtFixedRate(this::finalizePending, 2000, 1000, TimeUnit.MILLISECONDS);
        System.out.println("[TON_WITHDRAWAL_FINALIZER] Started");
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
                bridge.sendToWallet(pw.userId, pw.amount);
                System.out.println("[TON_WITHDRAWAL_FINALIZER] Sent " + pw.amount
                        + " USDT to " + pw.userId + " via TON");
                handler.removePendingWithdrawal(pw);
            }
        } catch (Exception e) {
            System.err.println("[TON_WITHDRAWAL_FINALIZER] Error: " + e.getMessage());
        }
    }
}
