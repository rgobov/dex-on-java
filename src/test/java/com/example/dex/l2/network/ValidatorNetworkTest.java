package com.example.dex.l2.network;

import com.example.dex.l2.models.L2Block;
import com.example.dex.models.ChainTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorNetworkTest {

    @Test
    void testBroadcastMessage() throws Exception {
        Map<String, Integer> peers = Map.of("val-1", 9051, "val-2", 9052);
        ValidatorNetwork net1 = new ValidatorNetwork("val-1", 9051, peers);
        ValidatorNetwork net2 = new ValidatorNetwork("val-2", 9052, peers);

        net1.start();
        net2.start();
        Thread.sleep(200);

        try {
            L2Block block = new L2Block(1, "prev", List.of(), "val-1", 1000, "root", "hash");
            ConsensusMessage msg = new ConsensusMessage(ConsensusMessage.Type.PRE_PREPARE, "val-1", 1, block);
            net1.broadcast(msg);

            Thread.sleep(500);

            List<ConsensusMessage> received = net2.getReceivedMessages();
            assertFalse(received.isEmpty());
            assertEquals(ConsensusMessage.Type.PRE_PREPARE, received.get(0).getType());
            assertEquals(1, received.get(0).getHeight());
            assertEquals("val-1", received.get(0).getFromValidator());
            assertNotNull(received.get(0).getBlock());
            assertEquals(1, received.get(0).getBlock().getHeight());
        } finally {
            net1.stop();
            net2.stop();
        }
    }

    @Test
    void testHandlerRegistration() throws Exception {
        Map<String, Integer> peers = Map.of("val-1", 9053, "val-2", 9054);
        ValidatorNetwork net1 = new ValidatorNetwork("val-1", 9053, peers);
        ValidatorNetwork net2 = new ValidatorNetwork("val-2", 9054, peers);

        final boolean[] handlerCalled = {false};
        net2.registerHandler(ConsensusMessage.Type.PREPARE, msg -> handlerCalled[0] = true);

        net1.start();
        net2.start();
        Thread.sleep(200);

        try {
            ConsensusMessage msg = new ConsensusMessage(ConsensusMessage.Type.PREPARE, "val-1", 1, null);
            net1.broadcast(msg);
            Thread.sleep(500);
            assertTrue(handlerCalled[0]);
        } finally {
            net1.stop();
            net2.stop();
        }
    }
}
