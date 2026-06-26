package com.example.dex.disruptor;

import com.lmax.disruptor.EventFactory;

/**
 * Фабрика для предварительного выделения объектов ChainTxEvent в RingBuffer.
 */
public class ChainTxEventFactory implements EventFactory<ChainTxEvent> {
    @Override
    public ChainTxEvent newInstance() {
        return new ChainTxEvent();
    }
}
