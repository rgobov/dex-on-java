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
    private final String defaultLpAddress;

    public WithdrawalFinalizer(ArbitrumBridge bridge, StateExecutionHandler handler) {
        this(bridge, handler, "lp-1");
    }

    public WithdrawalFinalizer(ArbitrumBridge bridge, StateExecutionHandler handler, String defaultLpAddress) {
        this.bridge = bridge;
        this.handler = handler;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "withdrawal-finalizer");
            t.setDaemon(true);
            return t;
        });
        this.defaultLpAddress = defaultLpAddress;
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
                if (pw.isFastWithdraw) {
                    double fee = pw.amount * pw.fastFeeBps / 10000.0;
                    double netAmount = pw.amount - fee;
                    String lp = pw.beneficiary != null ? pw.beneficiary : defaultLpAddress;

                    bridge.depositL1(pw.userId, netAmount);
                    bridge.depositL1(lp, fee);

                    System.out.println("[WITHDRAWAL_FINALIZER] FAST withdrawal for " + pw.userId
                            + " net=" + String.format("%.2f", netAmount)
                            + " fee=" + String.format("%.2f", fee)
                            + " LP=" + lp);
                } else {
                    String requestId = bridge.initiateWithdrawal(pw.userId, pw.amount);
                    System.out.println("[WITHDRAWAL_FINALIZER] Initiated L1 withdrawal " + requestId
                            + " for " + pw.userId + " amount=" + pw.amount);
                }
                handler.removePendingWithdrawal(pw);
            }
        } catch (Exception e) {
            System.err.println("[WITHDRAWAL_FINALIZER] Error: " + e.getMessage());
        }
    }
}
