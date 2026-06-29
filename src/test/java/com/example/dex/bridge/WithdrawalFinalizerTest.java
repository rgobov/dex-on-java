package com.example.dex.bridge;

import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.MarketSpecification;
import com.example.dex.oracle.OracleService;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class WithdrawalFinalizerTest {

    @Test
    void testFinalizerInitiatesWithdrawalsOnBridge() throws Exception {
        ArbitrumBridge bridge = new ArbitrumBridge(50000, 50000, 200, 50000);
        bridge.depositL1("user-1", 1000.0);
        bridge.start();

        OracleService oracle = new OracleService();
        oracle.setPrice("BTC-USD", 60000.0);
        MarginManager mm = new MarginManager(oracle);
        mm.registerMarket(new MarketSpecification("BTC-USD", "feed", 10.0, 0.05, 0.0, 0.0));
        mm.registerUser("user-1", 500.0);

        StateExecutionHandler handler = new StateExecutionHandler(
                mm, new LiquidationEngine(mm, oracle), new FundingCalculator(mm, oracle));

        Disruptor<ChainTxEvent> disruptor = new Disruptor<>(
                new ChainTxEventFactory(), 512,
                DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new YieldingWaitStrategy()
        );
        disruptor.handleEventsWith(handler);
        RingBuffer<ChainTxEvent> ringBuffer = disruptor.start();

        // Генерируем ключи и подписываем withdrawal
        KeyPair keys = DexSignatureUtil.generateKeyPair();
        String userId = DexSignatureUtil.encodePublicKey(keys.getPublic());

        mm.registerUser(userId, 500.0);

        long ts = System.currentTimeMillis();
        String msg = userId + ":200.0:" + ts;
        String sig = DexSignatureUtil.sign(msg, keys.getPrivate());

        ChainTransaction withdrawTx = new ChainTransaction.Builder(ChainTransaction.TxType.WITHDRAW_SIGNED)
                .userId(userId).amount(200.0).signature(sig).timestamp(ts).build();

        long seq = ringBuffer.next();
        ringBuffer.get(seq).setTransaction(withdrawTx);
        ringBuffer.publish(seq);
        Thread.sleep(100);

        assertEquals(300.0, mm.getBalance(userId).getFreeBalance(), 1e-6);
        assertEquals(1, handler.getPendingWithdrawals().size());

        WithdrawalFinalizer finalizer = new WithdrawalFinalizer(bridge, handler);
        finalizer.start();
        Thread.sleep(100);
        // call directly to avoid 5s initial delay of background thread
        finalizer.finalizePending();

        assertFalse(bridge.getWithdrawals().isEmpty(), "Withdrawal must appear on bridge");
        var wr = bridge.getWithdrawals().get(0);
        assertEquals(userId, wr.user);
        assertEquals(200.0, wr.amount, 1e-6);

        assertTrue(handler.getPendingWithdrawals().isEmpty(),
                "Pending withdrawal must be removed after finalization");

        finalizer.stop();
        disruptor.shutdown();
        bridge.stop();
    }
}
