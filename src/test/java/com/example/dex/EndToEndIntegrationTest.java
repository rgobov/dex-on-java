package com.example.dex;

import com.example.dex.bridge.L1Vault;
import com.example.dex.bridge.L2Bridge;
import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.models.AccountBalance;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.RollupBatch;
import com.example.dex.models.RollupPublisher;
import com.example.dex.oracle.OracleService;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EndToEndIntegrationTest {

    private static class TradingEngine {
        final MarginManager marginManager;
        final StateExecutionHandler handler;
        final Disruptor<ChainTxEvent> disruptor;
        final RingBuffer<ChainTxEvent> ringBuffer;

        TradingEngine(String marketId, MarketSpecification spec) {
            OracleService oracle = new OracleService();
            oracle.setPrice(marketId, 60000.0);
            this.marginManager = new MarginManager(oracle);
            this.marginManager.registerMarket(spec);
            this.handler = new StateExecutionHandler(
                    marginManager,
                    new LiquidationEngine(marginManager, oracle),
                    new FundingCalculator(marginManager, oracle)
            );
            this.handler.registerMarket(marketId);

            this.disruptor = new Disruptor<>(
                    new ChainTxEventFactory(),
                    512,
                    DaemonThreadFactory.INSTANCE,
                    ProducerType.SINGLE,
                    new YieldingWaitStrategy()
            );
            this.disruptor.handleEventsWith(handler);
            this.ringBuffer = this.disruptor.start();
        }

        void stop() {
            disruptor.shutdown();
        }
    }

    private TradingEngine engine;
    private L1Vault vault;
    private L2Bridge bridge;

    private List<KeyPair> validatorKeys;
    private List<String> validatorPublicKeysBase64;

    private final String marketId = "BTC-USD";
    private final MarketSpecification spec = new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0, 0.0);

    @BeforeEach
    public void setUp() throws Exception {
        validatorKeys = new ArrayList<>();
        validatorPublicKeysBase64 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            KeyPair kp = DexSignatureUtil.generateKeyPair();
            validatorKeys.add(kp);
            validatorPublicKeysBase64.add(DexSignatureUtil.encodePublicKey(kp.getPublic()));
        }

        vault = new L1Vault(validatorPublicKeysBase64);
        engine = new TradingEngine(marketId, spec);
        bridge = new L2Bridge(vault, engine.ringBuffer);
    }

    @AfterEach
    public void tearDown() {
        engine.stop();
    }

    @Test
    public void testFullEndToEndFlow() throws Exception {
        KeyPair aliceKeys = DexSignatureUtil.generateKeyPair();
        String aliceAddr = DexSignatureUtil.encodePublicKey(aliceKeys.getPublic());

        KeyPair bobKeys = DexSignatureUtil.generateKeyPair();
        String bobAddr = DexSignatureUtil.encodePublicKey(bobKeys.getPublic());

        vault.deposit(aliceAddr, 10000.0);
        vault.deposit(bobAddr, 10000.0);

        assertEquals(20000.0, vault.getTotalVaultBalance(), "Баланс L1Vault должен быть равен 20000");

        bridge.syncDeposits();
        Thread.sleep(150);

        AccountBalance aliceBal = engine.marginManager.getBalance(aliceAddr);
        assertNotNull(aliceBal);
        assertEquals(10000.0, aliceBal.getFreeBalance());

        String bobMsg = "PLACE_ORDER:sell:" + bobAddr + ":" + marketId + ":60000:1.0";
        String bobSig = DexSignatureUtil.sign(bobMsg, bobKeys.getPrivate());

        ChainTransaction bobOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("bob-order-1").userId(bobAddr).marketId(marketId)
                .isBuy(false).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(bobSig).timestamp(System.currentTimeMillis()).build();

        sendTransaction(bobOrder);
        Thread.sleep(100);

        String aliceMsg = "PLACE_ORDER:buy:" + aliceAddr + ":" + marketId + ":60000:1.0";
        String aliceSig = DexSignatureUtil.sign(aliceMsg, aliceKeys.getPrivate());

        ChainTransaction aliceOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("alice-order-1").userId(aliceAddr).marketId(marketId)
                .isBuy(true).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(aliceSig).timestamp(System.currentTimeMillis()).build();

        sendTransaction(aliceOrder);
        Thread.sleep(150);

        assertEquals(4000.0, engine.marginManager.getBalance(aliceAddr).getFreeBalance());
        assertEquals(6000.0, engine.marginManager.getBalance(aliceAddr).getLockedMargin());

        double withdrawAmount = 2000.0;
        String withdrawMsg = aliceAddr + ":" + withdrawAmount;

        String userWithdrawSig = DexSignatureUtil.sign(withdrawMsg, aliceKeys.getPrivate());

        List<String> validatorWithdrawSigs = new ArrayList<>();
        validatorWithdrawSigs.add(DexSignatureUtil.sign(withdrawMsg, validatorKeys.get(0).getPrivate()));
        validatorWithdrawSigs.add(DexSignatureUtil.sign(withdrawMsg, validatorKeys.get(1).getPrivate()));

        boolean success = vault.withdraw(aliceAddr, withdrawAmount, userWithdrawSig, validatorWithdrawSigs);
        assertTrue(success, "Вывод средств с L1Vault должен быть одобрен");

        assertEquals(18000.0, vault.getTotalVaultBalance(), "Баланс хранилища должен уменьшиться на 2000");

        List<String> invalidValidatorSigs = new ArrayList<>();
        invalidValidatorSigs.add("invalid-signature-1");
        invalidValidatorSigs.add("invalid-signature-2");

        boolean failWithdraw = vault.withdraw(aliceAddr, 1000.0, userWithdrawSig, invalidValidatorSigs);
        assertFalse(failWithdraw, "Вывод с неверными подписями валидаторов должен быть отклонен");
    }

    @Test
    public void testThreeLayerRollupPublishing() throws Exception {
        KeyPair aliceKeys = DexSignatureUtil.generateKeyPair();
        String aliceAddr = DexSignatureUtil.encodePublicKey(aliceKeys.getPublic());

        KeyPair bobKeys = DexSignatureUtil.generateKeyPair();
        String bobAddr = DexSignatureUtil.encodePublicKey(bobKeys.getPublic());

        vault.deposit(aliceAddr, 10000.0);
        vault.deposit(bobAddr, 10000.0);
        bridge.syncDeposits();
        Thread.sleep(150);

        RollupPublisher publisher = new RollupPublisher(engine.handler, vault);

        String bobMsg = "PLACE_ORDER:sell:" + bobAddr + ":" + marketId + ":60000:1.0";
        String bobSig = DexSignatureUtil.sign(bobMsg, bobKeys.getPrivate());
        ChainTransaction bobOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("bob-order-r1").userId(bobAddr).marketId(marketId)
                .isBuy(false).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(bobSig).timestamp(System.currentTimeMillis()).build();
        sendTransaction(bobOrder);
        Thread.sleep(100);

        String aliceMsg = "PLACE_ORDER:buy:" + aliceAddr + ":" + marketId + ":60000:1.0";
        String aliceSig = DexSignatureUtil.sign(aliceMsg, aliceKeys.getPrivate());
        ChainTransaction aliceOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("alice-order-r1").userId(aliceAddr).marketId(marketId)
                .isBuy(true).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(aliceSig).timestamp(System.currentTimeMillis()).build();
        sendTransaction(aliceOrder);
        Thread.sleep(150);

        assertTrue(engine.handler.getExecutedTrades().size() > 0, "Сделка должна быть зафиксирована на L3");

        boolean published = publisher.publishNextBatch();
        assertTrue(published, "Батч роллапа должен быть успешно опубликован");

        List<RollupBatch> batches = vault.getRollupBatches();
        assertEquals(1, batches.size(), "На расчетном слое должен присутствовать 1 опубликованный батч");
        RollupBatch batch = batches.get(0);
        assertEquals(1, batch.getBatchId());
        assertEquals(1, batch.getTrades().size());
        assertNotNull(batch.getStateRoot());
        assertNotEquals(batch.getPrevStateRoot(), batch.getStateRoot());
    }

    private void sendTransaction(ChainTransaction tx) {
        long sequence = engine.ringBuffer.next();
        try {
            engine.ringBuffer.get(sequence).setTransaction(tx);
        } finally {
            engine.ringBuffer.publish(sequence);
        }
    }
}
