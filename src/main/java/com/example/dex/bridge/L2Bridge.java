package com.example.dex.bridge;

import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.models.ChainTransaction;
import com.lmax.disruptor.RingBuffer;

import java.util.List;

/**
 * Блокчейн-мост L2 (L2 Bridge Listener).
 * Сканирует депозитные события в L1Vault и транслирует их как транзакции
 * напрямую в RingBuffer Execution Engine.
 */
public final class L2Bridge {

    private final L1Vault vault;
    private final RingBuffer<ChainTxEvent> ringBuffer;
    private long lastProcessedDepositId = 0;

    public L2Bridge(L1Vault vault, RingBuffer<ChainTxEvent> ringBuffer) {
        this.vault = vault;
        this.ringBuffer = ringBuffer;
    }

    /**
     * Сканирует новые депозиты на L1 и публикует их напрямую в execution engine.
     */
    public synchronized final void syncDeposits() {
        List<L1Vault.DepositEvent> events = vault.getDepositEventsSince(lastProcessedDepositId);
        for (L1Vault.DepositEvent event : events) {
            System.out.println("[L2_BRIDGE] Обнаружен депозит #" + event.getDepositId() + ", перенаправляем в engine...");

            ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                    .userId(event.getUserPublicKey())
                    .amount(event.getAmount())
                    .timestamp(System.currentTimeMillis())
                    .build();

            long sequence = ringBuffer.next();
            try {
                ringBuffer.get(sequence).setTransaction(tx);
            } finally {
                ringBuffer.publish(sequence);
            }

            lastProcessedDepositId = event.getDepositId();
        }
    }

    public final long getLastProcessedDepositId() {
        return lastProcessedDepositId;
    }
}
