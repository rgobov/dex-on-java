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
import com.example.dex.router.RoutingPolicy;
import com.example.dex.router.SmartOrderRouter;
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

    private TradingEngine smrEngine;
    private TradingEngine l2Engine;
    private SmartOrderRouter router;
    private L1Vault vault;
    private L2Bridge bridge;

    private List<KeyPair> validatorKeys;
    private List<String> validatorPublicKeysBase64;

    private final String marketId = "BTC-USD";
    private final MarketSpecification spec = new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0, 0.0);

    @BeforeEach
    public void setUp() throws Exception {
        // Инициализируем 3 валидаторов
        validatorKeys = new ArrayList<>();
        validatorPublicKeysBase64 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            KeyPair kp = DexSignatureUtil.generateKeyPair();
            validatorKeys.add(kp);
            validatorPublicKeysBase64.add(DexSignatureUtil.encodePublicKey(kp.getPublic()));
        }

        // Инициализируем L1Vault с этими валидаторами
        vault = new L1Vault(validatorPublicKeysBase64);

        // Инициализируем торговые движки L2
        smrEngine = new TradingEngine(marketId, spec);
        l2Engine = new TradingEngine(marketId, spec);

        // Инициализируем роутер и мост
        router = new SmartOrderRouter(
                smrEngine.ringBuffer, smrEngine.handler,
                l2Engine.ringBuffer, l2Engine.handler
        );
        bridge = new L2Bridge(vault, router);
    }

    @AfterEach
    public void tearDown() {
        smrEngine.stop();
        l2Engine.stop();
    }

    @Test
    public void testFullEndToEndFlow() throws Exception {
        // Создаем ключи для Алисы (покупатель) и Боба (продавец)
        KeyPair aliceKeys = DexSignatureUtil.generateKeyPair();
        String aliceAddr = DexSignatureUtil.encodePublicKey(aliceKeys.getPublic());

        KeyPair bobKeys = DexSignatureUtil.generateKeyPair();
        String bobAddr = DexSignatureUtil.encodePublicKey(bobKeys.getPublic());

        // ------------------ ШАГ 1: ДЕПОЗИТ НА L1 И СИНХРОНИЗАЦИЯ ------------------
        vault.deposit(aliceAddr, 10000.0);
        vault.deposit(bobAddr, 10000.0);

        // Проверяем баланс смарт-контракта на L1
        assertEquals(20000.0, vault.getTotalVaultBalance(), "Баланс L1Vault должен быть равен 20000");

        // Синхронизируем депозиты в L2 через мост
        bridge.syncDeposits();

        // Ждем асинхронной обработки депозитов в Disruptor
        Thread.sleep(150);

        // Проверяем балансы пользователей на L2 нодах (и на SMR, и на L2)
        AccountBalance aliceBalSMR = smrEngine.marginManager.getBalance(aliceAddr);
        AccountBalance aliceBalL2 = l2Engine.marginManager.getBalance(aliceAddr);
        assertNotNull(aliceBalSMR);
        assertEquals(10000.0, aliceBalSMR.getFreeBalance());
        assertEquals(10000.0, aliceBalL2.getFreeBalance());

        // ------------------ ШАГ 2: ТОРГОВЛЯ НА L2 (SMR/L2) ------------------
        // Боб ставит лимитный ордер на продажу 1.0 BTC по $60,000 на L2
        String bobMsg = "PLACE_ORDER:sell:" + bobAddr + ":" + marketId + ":60000:1.0";
        String bobSig = DexSignatureUtil.sign(bobMsg, bobKeys.getPrivate());

        ChainTransaction bobOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("bob-order-1").userId(bobAddr).marketId(marketId)
                .isBuy(false).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(bobSig).timestamp(System.currentTimeMillis()).build();
        
        router.routeOrder(bobOrder, RoutingPolicy.FORCE_L2);

        // Ждем выставления ордера в L2 стакан
        Thread.sleep(100);

        // Алиса отправляет ордер на покупку с BEST_EXECUTION
        String aliceMsg = "PLACE_ORDER:buy:" + aliceAddr + ":" + marketId + ":60000:1.0";
        String aliceSig = DexSignatureUtil.sign(aliceMsg, aliceKeys.getPrivate());

        ChainTransaction aliceOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("alice-order-1").userId(aliceAddr).marketId(marketId)
                .isBuy(true).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(aliceSig).timestamp(System.currentTimeMillis()).build();

        router.routeOrder(aliceOrder, RoutingPolicy.BEST_EXECUTION);

        // Ждем проведения сделки
        Thread.sleep(150);

        // Проверяем смену балансов в L2 после мэтчинга
        // Свободный баланс должен измениться в соответствии с залогом (маржой) для позиции 1.0 BTC по $60,000 с плечом 10.
        // Залог составляет: 60000 / 10 = 6000 USDC.
        // У Алисы свободный баланс должен уменьшиться на 6000 USDC (стал 4000).
        assertEquals(4000.0, l2Engine.marginManager.getBalance(aliceAddr).getFreeBalance());
        assertEquals(6000.0, l2Engine.marginManager.getBalance(aliceAddr).getLockedMargin());

        // ------------------ ШАГ 3: МУЛЬТИПОДПИСНОЙ ВЫВОД НА L1 ------------------
        // Алиса решает вывести $2,000 обратно на L1
        double withdrawAmount = 2000.0;
        String withdrawMsg = aliceAddr + ":" + withdrawAmount;

        // Алиса подписывает запрос на вывод
        String userWithdrawSig = DexSignatureUtil.sign(withdrawMsg, aliceKeys.getPrivate());

        // Собираем подписи валидаторов (требуется 2/3, т.е. минимум 2 подписи из 3 валидаторов)
        List<String> validatorWithdrawSigs = new ArrayList<>();
        // Валидатор 1 подписывает
        validatorWithdrawSigs.add(DexSignatureUtil.sign(withdrawMsg, validatorKeys.get(0).getPrivate()));
        // Валидатор 2 подписывает
        validatorWithdrawSigs.add(DexSignatureUtil.sign(withdrawMsg, validatorKeys.get(1).getPrivate()));

        // Отправляем вывод в L1Vault
        boolean success = vault.withdraw(aliceAddr, withdrawAmount, userWithdrawSig, validatorWithdrawSigs);
        assertTrue(success, "Вывод средств с L1Vault должен быть одобрен");

        // Проверяем баланс L1Vault после успешного вывода
        assertEquals(18000.0, vault.getTotalVaultBalance(), "Баланс хранилища должен уменьшиться на 2000");

        // Пытаемся сделать вывод с невалидной подписью валидатора
        List<String> invalidValidatorSigs = new ArrayList<>();
        invalidValidatorSigs.add("invalid-signature-1");
        invalidValidatorSigs.add("invalid-signature-2");

        boolean failWithdraw = vault.withdraw(aliceAddr, 1000.0, userWithdrawSig, invalidValidatorSigs);
        assertFalse(failWithdraw, "Вывод с неверными подписями валидаторов должен быть отклонен");

        System.out.println("END TO END INTEGRATION TEST SUCCESSFUL: L1 deposit -> L2 Bridge sync -> L2 trade -> L1 multi-sig withdrawal all passed successfully!");
    }

    @Test
    public void testThreeLayerRollupPublishing() throws Exception {
        // Создаем ключи для Алисы (покупатель) и Боба (продавец)
        KeyPair aliceKeys = DexSignatureUtil.generateKeyPair();
        String aliceAddr = DexSignatureUtil.encodePublicKey(aliceKeys.getPublic());

        KeyPair bobKeys = DexSignatureUtil.generateKeyPair();
        String bobAddr = DexSignatureUtil.encodePublicKey(bobKeys.getPublic());

        // 1. Депозиты на L1 и синхронизация в L2
        vault.deposit(aliceAddr, 10000.0);
        vault.deposit(bobAddr, 10000.0);
        bridge.syncDeposits();
        Thread.sleep(150);

        // 2. Инициализируем и запускаем издатель роллапов
        RollupPublisher publisher = new RollupPublisher(l2Engine.handler, vault);
        
        // 3. Выполняем сделку на L2
        String bobMsg = "PLACE_ORDER:sell:" + bobAddr + ":" + marketId + ":60000:1.0";
        String bobSig = DexSignatureUtil.sign(bobMsg, bobKeys.getPrivate());
        ChainTransaction bobOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("bob-order-r1").userId(bobAddr).marketId(marketId)
                .isBuy(false).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(bobSig).timestamp(System.currentTimeMillis()).build();
        router.routeOrder(bobOrder, RoutingPolicy.FORCE_L2);
        Thread.sleep(100);

        String aliceMsg = "PLACE_ORDER:buy:" + aliceAddr + ":" + marketId + ":60000:1.0";
        String aliceSig = DexSignatureUtil.sign(aliceMsg, aliceKeys.getPrivate());
        ChainTransaction aliceOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("alice-order-r1").userId(aliceAddr).marketId(marketId)
                .isBuy(true).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .signature(aliceSig).timestamp(System.currentTimeMillis()).build();
        router.routeOrder(aliceOrder, RoutingPolicy.BEST_EXECUTION);
        Thread.sleep(150);

        // 4. Проверяем, что сделка совершена и сохранена на L3
        assertTrue(l2Engine.handler.getExecutedTrades().size() > 0, "Сделка должна быть зафиксирована на L3");

        // 5. Запускаем публикацию следующего батча вручную/через метод, чтобы не ждать sleep
        boolean published = publisher.publishNextBatch();
        assertTrue(published, "Батч роллапа должен быть успешно опубликован");

        // 6. Проверяем состояние расчетного слоя L2 (в vault)
        List<RollupBatch> batches = vault.getRollupBatches();
        assertEquals(1, batches.size(), "На расчетном слое L2 должен присутствовать 1 опубликованный батч");
        RollupBatch batch = batches.get(0);
        assertEquals(1, batch.getBatchId());
        assertEquals(1, batch.getTrades().size());
        assertNotNull(batch.getStateRoot());
        assertNotEquals(batch.getPrevStateRoot(), batch.getStateRoot());

        System.out.println("THREE-LAYER ARCHITECTURE TEST SUCCESSFUL: L3 trade successfully rolled up and committed to L2 Settlement!");
    }
}
