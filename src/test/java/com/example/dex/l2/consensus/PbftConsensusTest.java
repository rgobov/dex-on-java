package com.example.dex.l2.consensus;

import com.example.dex.l2.mempool.Mempool;
import com.example.dex.l2.models.L2Block;
import com.example.dex.models.ChainTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class PbftConsensusTest {

    @Test
    void testConsensusCommitsBlock() throws Exception {
        List<String> validators = List.of("val-1", "val-2", "val-3");
        List<Double> stakes = List.of(100.0, 100.0, 100.0);
        LeaderElector elector = new LeaderElector(validators, stakes);
        Mempool mempool = new Mempool();

        ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("alice").amount(100).build();
        mempool.add(tx);

        List<L2Block> committed = new CopyOnWriteArrayList<>();
        PbftConsensus consensus = new PbftConsensus("val-1", validators, elector, mempool, (block, txs) -> {
            committed.add(block);
        });

        L2Block result = consensus.runConsensusRound();

        assertNotNull(result);
        assertEquals(1, result.getHeight());
        assertEquals(1, committed.size());
        assertEquals("val-1", result.getProposerId());
        assertNotNull(result.getBlockHash());
        assertFalse(result.getTransactions().isEmpty());
        assertEquals("alice", result.getTransactions().get(0).getUserId());
    }

    @Test
    void testMultipleRoundsRotateLeader() throws Exception {
        List<String> validators = List.of("val-1", "val-2", "val-3");
        List<Double> stakes = List.of(100.0, 100.0, 100.0);
        LeaderElector elector = new LeaderElector(validators, stakes);
        Mempool mempool = new Mempool();
        List<L2Block> committed = new CopyOnWriteArrayList<>();

        PbftConsensus consensus = new PbftConsensus("val-1", validators, elector, mempool, (block, txs) -> {
            committed.add(block);
        });

        // First round
        mempool.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT).userId("a").amount(1).build());
        L2Block block1 = consensus.runConsensusRound();
        assertNotNull(block1);
        assertEquals(1, block1.getHeight());
        assertEquals("val-1", block1.getProposerId());

        // Second round — leader rotates
        mempool.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT).userId("b").amount(2).build());
        L2Block block2 = consensus.runConsensusRound();
        assertNotNull(block2);
        assertEquals(2, block2.getHeight());
        assertEquals("val-2", block2.getProposerId());

        // Third round
        mempool.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT).userId("c").amount(3).build());
        L2Block block3 = consensus.runConsensusRound();
        assertNotNull(block3);
        assertEquals(3, block3.getHeight());
        assertEquals("val-3", block3.getProposerId());

        // Fourth round — back to val-1
        mempool.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT).userId("d").amount(4).build());
        L2Block block4 = consensus.runConsensusRound();
        assertNotNull(block4);
        assertEquals(4, block4.getHeight());
        assertEquals("val-1", block4.getProposerId());

        assertEquals(4, committed.size());
    }

    @Test
    void testEmptyBlockWithNoTransactions() throws Exception {
        List<String> validators = List.of("val-1", "val-2", "val-3");
        List<Double> stakes = List.of(100.0, 100.0, 100.0);
        LeaderElector elector = new LeaderElector(validators, stakes);
        Mempool mempool = new Mempool();
        List<L2Block> committed = new CopyOnWriteArrayList<>();

        PbftConsensus consensus = new PbftConsensus("val-1", validators, elector, mempool, (block, txs) -> {
            committed.add(block);
        });

        L2Block result = consensus.runConsensusRound();
        assertNotNull(result);
        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getHeight());
    }
}
