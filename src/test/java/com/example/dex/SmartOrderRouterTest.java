package com.example.dex;

import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.MarketSpecification;
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

import static org.junit.jupiter.api.Assertions.*;

public class SmartOrderRouterTest {

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
    private final String marketId = "BTC-USD";
    private final MarketSpecification spec = new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0, 0.0);

    @BeforeEach
    public void setUp() {
        smrEngine = new TradingEngine(marketId, spec);
        l2Engine = new TradingEngine(marketId, spec);

        router = new SmartOrderRouter(
                smrEngine.ringBuffer, smrEngine.handler,
                l2Engine.ringBuffer, l2Engine.handler
        );
    }

    @AfterEach
    public void tearDown() {
        smrEngine.stop();
        l2Engine.stop();
    }

    @Test
    public void testForceRoutingAndBestExecution() throws Exception {
        long now = System.currentTimeMillis();

        // 1. Создаем депозиты для пользователей на обеих площадках
        router.routeOrder(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("alice").amount(20000.0).timestamp(now).build(), RoutingPolicy.BEST_EXECUTION);
        router.routeOrder(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("bob").amount(20000.0).timestamp(now).build(), RoutingPolicy.BEST_EXECUTION);
        router.routeOrder(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("seller-smr").amount(20000.0).timestamp(now).build(), RoutingPolicy.BEST_EXECUTION);
        router.routeOrder(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("seller-l2").amount(20000.0).timestamp(now).build(), RoutingPolicy.BEST_EXECUTION);

        // Ждем зачисления депозитов
        Thread.sleep(100);

        // 2. FORCE_SMR: Отправляем лимитный ордер на продажу 1.0 BTC по $60,000 только на SMR
        ChainTransaction sellSMR = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("sell-smr").userId("seller-smr").marketId(marketId)
                .isBuy(false).price(60000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .timestamp(now + 100).build();
        router.routeOrder(sellSMR, RoutingPolicy.FORCE_SMR);

        // 3. FORCE_L2: Отправляем лимитный ордер на продажу 1.0 BTC по $59,000 только на L2
        ChainTransaction sellL2 = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("sell-l2").userId("seller-l2").marketId(marketId)
                .isBuy(false).price(59000.0).amount(1.0).leverage(10.0).isIsolated(true)
                .timestamp(now + 200).build();
        router.routeOrder(sellL2, RoutingPolicy.FORCE_L2);

        // Ждем размещения ордеров в стаканах
        Thread.sleep(100);

        OrderBook bookSMR = smrEngine.handler.getOrderBook(marketId);
        OrderBook bookL2 = l2Engine.handler.getOrderBook(marketId);

        // Проверяем, что FORCE сработал верно
        assertEquals(1, bookSMR.getAsks().size(), "Ордер должен быть только на SMR");
        assertEquals(0, bookSMR.getBids().size());
        assertEquals(1, bookL2.getAsks().size(), "Ордер должен быть только на L2");
        assertEquals(0, bookL2.getBids().size());

        // Проверяем цены в стаканах
        assertEquals(60000.0, bookSMR.getAsks().firstKey());
        assertEquals(59000.0, bookL2.getAsks().firstKey());

        // 4. BEST_EXECUTION: Alice отправляет ордер на покупку 1.5 BTC по цене $61,000
        // Маршрутизатор должен увидеть, что на L2 цена выгоднее ($59,000 < $60,000),
        // забрать там доступный 1.0 BTC, а оставшиеся 0.5 BTC направить на SMR по $60,000.
        ChainTransaction buyOrder = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("buy-alice").userId("alice").marketId(marketId)
                .isBuy(true).price(61000.0).amount(1.5).leverage(10.0).isIsolated(true)
                .timestamp(now + 300).build();
        router.routeOrder(buyOrder, RoutingPolicy.BEST_EXECUTION);

        // Ждем мэтча
        Thread.sleep(150);

        // Проверяем результаты стаканов:
        // L2 должен быть полностью пуст (все 1.0 BTC забрали)
        assertTrue(bookL2.getAsks().isEmpty(), "На L2 продавец должен быть полностью удовлетворен");
        
        // На SMR остаток продавца должен составить 0.5 BTC (было 1.0, забрали 0.5)
        assertEquals(1, bookSMR.getAsks().size());
        double remainingSellerSMR = bookSMR.getAsks().get(60000.0).getFirst().getRemainingAmount();
        assertEquals(0.5, remainingSellerSMR, "Продавец на SMR должен продать только половину");

        System.out.println("SMART ROUTER TEST SUCCESSFUL: FORCE routing and BEST_EXECUTION splitting worked perfectly!");
    }
}
