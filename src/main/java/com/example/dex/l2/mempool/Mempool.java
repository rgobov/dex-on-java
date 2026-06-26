package com.example.dex.l2.mempool;

import com.example.dex.models.ChainTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Mempool {
    private final Queue<ChainTransaction> pending = new ConcurrentLinkedQueue<>();

    public void add(ChainTransaction tx) {
        pending.add(tx);
    }

    public List<ChainTransaction> drain(long maxCount) {
        List<ChainTransaction> batch = new ArrayList<>();
        while (!pending.isEmpty() && batch.size() < maxCount) {
            ChainTransaction tx = pending.poll();
            if (tx != null) {
                batch.add(tx);
            }
        }
        return batch;
    }

    public int size() {
        return pending.size();
    }
}
