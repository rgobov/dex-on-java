package com.example.dex.disruptor;

import com.example.dex.models.ChainTransaction;

/**
 * Контейнер для данных транзакции в RingBuffer Disruptor.
 */
public class ChainTxEvent {
    private ChainTransaction transaction;

    public ChainTransaction getTransaction() {
        return transaction;
    }

    public void setTransaction(ChainTransaction transaction) {
        this.transaction = transaction;
    }

    public void clear() {
        this.transaction = null;
    }
}
