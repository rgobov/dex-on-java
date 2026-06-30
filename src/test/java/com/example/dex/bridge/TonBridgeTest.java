package com.example.dex.bridge;

import com.example.dex.l2.mempool.Mempool;
import com.example.dex.models.ChainTransaction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TonBridgeTest {

    @Test
    void testTonBridgeDepositFlow() throws Exception {
        TonBridge bridge = new TonBridge(100);
        bridge.start();

        bridge.depositUsdt("alice", 500.0);
        Thread.sleep(200);

        var outbox = bridge.drainOutbox();
        assertEquals(1, outbox.size());
        ChainTransaction tx = outbox.get(0);
        assertEquals(ChainTransaction.TxType.DEPOSIT, tx.getType());
        assertEquals("alice", tx.getUserId());
        assertEquals(500.0, tx.getAmount(), 1e-6);
        assertNotNull(tx.getOrderId());

        bridge.stop();
    }

    @Test
    void testTonBridgeImmediateWithdrawal() {
        TonBridge bridge = new TonBridge();
        bridge.creditVault("vault-1", 10000.0);

        bridge.sendToWallet("bob", 300.0);
        assertEquals(300.0, bridge.getTonBalance("bob"), 1e-6);
    }

    @Test
    void testTonBridgePollerIntegration() throws Exception {
        TonBridge bridge = new TonBridge(100);
        bridge.start();

        Mempool mempool = new Mempool();
        Set<String> processed = new HashSet<>();
        TonBridgePoller poller = new TonBridgePoller(bridge, mempool, processed);

        bridge.depositUsdt("charlie", 1000.0);
        Thread.sleep(200);

        poller.poll();

        assertEquals(1, mempool.size());
        ChainTransaction tx = mempool.drain(1).get(0);
        assertEquals("charlie", tx.getUserId());
        assertEquals(1000.0, tx.getAmount(), 1e-6);

        bridge.stop();
    }

    @Test
    void testTonBridgeNoChallengeWindow() {
        TonBridge bridge = new TonBridge();

        bridge.sendToWallet("user", 100.0);
        // TON withdrawals are immediate — no pending requests, no challenge window
        assertTrue(bridge.getPendingDeposits().isEmpty());
        // Balance credited instantly
        assertEquals(100.0, bridge.getTonBalance("user"), 1e-6);
    }
}
