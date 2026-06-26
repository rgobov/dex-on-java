package com.example.dex.bridge;

import com.example.dex.models.ChainTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArbitrumBridgeTest {

    private void waitForCondition(String label, Runnable check) throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (i == 29) throw e;
                Thread.sleep(100);
            }
        }
    }

    @Test
    void testDepositFlow() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(50000, 100, 50000, 100);
        bridge.depositL1("user-1", 1000.0);
        bridge.start();

        bridge.createRetryableTicket("user-1", 500.0);
        assertEquals(500.0, bridge.getL1Balance("user-1"), 1e-6);

        waitForCondition("ticket redemption", () -> {
            List<ChainTransaction> outbox = bridge.drainOutbox();
            assertFalse(outbox.isEmpty());
            assertEquals(ChainTransaction.TxType.DEPOSIT, outbox.get(0).getType());
            assertEquals("user-1", outbox.get(0).getUserId());
            assertEquals(500.0, outbox.get(0).getAmount(), 1e-6);
        });

        bridge.stop();
    }

    @Test
    void testWithdrawalWithChallengeWindow() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(300, 50000, 200, 50000);
        bridge.depositL1("user-1", 200.0);
        bridge.start();

        bridge.initiateWithdrawal("user-1", 100.0);
        assertEquals(200.0, bridge.getL1Balance("user-1"), 1e-6);

        assertFalse(bridge.getWithdrawals().isEmpty());
        assertFalse(bridge.getWithdrawals().get(0).finalized);

        waitForCondition("withdrawal finalization", () -> {
            assertTrue(bridge.getWithdrawals().get(0).finalized);
        });
        assertEquals(300.0, bridge.getL1Balance("user-1"), 1e-6);

        bridge.stop();
    }

    @Test
    void testChallengePreventsFinalization() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(300, 50000, 200, 50000);
        bridge.depositL1("user-1", 200.0);
        bridge.start();

        String reqId = bridge.initiateWithdrawal("user-1", 100.0);
        bridge.challengeWithdrawal(reqId);

        assertTrue(bridge.getWithdrawals().get(0).challenged);

        // Wait long enough for finalize to check past the window
        waitForCondition("challenge prevented finalization", () -> {
            assertTrue(bridge.getWithdrawals().get(0).challenged);
            assertFalse(bridge.getWithdrawals().get(0).finalized);
        });
        assertEquals(200.0, bridge.getL1Balance("user-1"), 1e-6);

        bridge.stop();
    }
}
