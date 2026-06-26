package com.example.dex.l2.mempool;

import com.example.dex.models.ChainTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MempoolTest {

    @Test
    void testDrainLimits() {
        Mempool mempool = new Mempool();
        for (int i = 0; i < 10; i++) {
            mempool.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                    .userId("u" + i).amount(i).build());
        }
        assertEquals(10, mempool.size());

        List<ChainTransaction> batch = mempool.drain(3);
        assertEquals(3, batch.size());
        assertEquals(7, mempool.size());

        batch = mempool.drain(100);
        assertEquals(7, batch.size());
        assertEquals(0, mempool.size());
    }
}
