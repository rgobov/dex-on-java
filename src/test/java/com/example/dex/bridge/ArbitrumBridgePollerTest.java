package com.example.dex.bridge;

import com.example.dex.l2.mempool.Mempool;
import com.example.dex.models.ChainTransaction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArbitrumBridgePollerTest {

    @Test
    void testPollerReadsOutboxAndAddsToMempool() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(30000, 50, 50000, 50);
        Mempool mempool = new Mempool();
        Set<String> processed = new HashSet<>();
        ArbitrumBridgePoller poller = new ArbitrumBridgePoller(bridge, mempool, processed);

        bridge.depositL1("user-1", 1000.0);
        bridge.start();
        bridge.createRetryableTicket("user-1", 500.0);

        Thread.sleep(500);

        poller.poll();

        assertEquals(1, mempool.size());
        ChainTransaction tx = mempool.drain(1).get(0);
        assertEquals(ChainTransaction.TxType.DEPOSIT, tx.getType());
        assertEquals("user-1", tx.getUserId());
        assertEquals(500.0, tx.getAmount());
        assertNotNull(tx.getOrderId());
        assertTrue(processed.contains(tx.getOrderId()));

        bridge.stop();
    }

    @Test
    void testPollerSkipsAlreadyProcessedTickets() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(30000, 50, 50000, 50);
        Mempool mempool = new Mempool();
        Set<String> processed = new HashSet<>();
        ArbitrumBridgePoller poller = new ArbitrumBridgePoller(bridge, mempool, processed);

        bridge.depositL1("user-1", 1000.0);
        bridge.start();
        bridge.createRetryableTicket("user-1", 500.0);

        Thread.sleep(500);

        poller.poll();
        assertEquals(1, mempool.size());

        poller.poll();
        assertEquals(1, mempool.size());

        bridge.stop();
    }

    @Test
    void testMultipleDepositsFromOutbox() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(30000, 50, 50000, 50);
        Mempool mempool = new Mempool();
        Set<String> processed = new HashSet<>();
        ArbitrumBridgePoller poller = new ArbitrumBridgePoller(bridge, mempool, processed);

        bridge.depositL1("alice", 2000.0);
        bridge.depositL1("bob", 3000.0);
        bridge.start();
        bridge.createRetryableTicket("alice", 1000.0);
        bridge.createRetryableTicket("bob", 1500.0);

        Thread.sleep(500);

        poller.poll();

        assertEquals(2, mempool.size());

        bridge.stop();
    }
}
