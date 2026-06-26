package com.example.dex;

import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.ChainTransaction;
import com.example.dex.oracle.OracleService;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DisruptorMatchingTest {

    private Disruptor<ChainTxEvent> disruptor;
    private RingBuffer<ChainTxEvent> ringBuffer;
    private StateExecutionHandler handler;
    private OracleService oracleService;
    private MarginManager marginManager;
    private LiquidationEngine liquidationEngine;
    private FundingCalculator fundingCalculator;
    private final String marketId = "BTC-USD";

    @BeforeEach
    public void setUp() {
        oracleService = new OracleService();
        oracleService.setPrice(marketId, 60000.0);
        
        marginManager = new MarginManager(oracleService);
        marginManager.registerMarket(new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0, 0.0));
        
        liquidationEngine = new LiquidationEngine(marginManager, oracleService);
        fundingCalculator = new FundingCalculator(marginManager, oracleService);

        // Регистрируем участников торгов
        marginManager.registerUser("seller-1", 10000.0);
        marginManager.registerUser("buyer-1", 10000.0);

        // Создаем фабрику событий
        ChainTxEventFactory factory = new ChainTxEventFactory();

        int bufferSize = 1024;

        disruptor = new Disruptor<>(
                factory,
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );

        // Создаем обработчик и регистрируем рынок
        handler = new StateExecutionHandler(marginManager, liquidationEngine, fundingCalculator);
        handler.registerMarket(marketId);

        disruptor.handleEventsWith(handler);
        ringBuffer = disruptor.start();
    }

    @AfterEach
    public void tearDown() {
        disruptor.shutdown();
    }

    @Test
    public void testDisruptorMatchingFlow() throws Exception {
        // Публикуем лимитный ордер на продажу: 1.0 BTC по $60,000 (изолированная маржа)
        publishOrder("ord-s", "seller-1", false, 60000.0, 1.0, true);

        // Ждем асинхронную обработку и проверяем стакан ордеров через обработчик
        OrderBook book = handler.getOrderBook(marketId);
        
        long start = System.currentTimeMillis();
        while (book.getAsks().isEmpty() && (System.currentTimeMillis() - start < 2000)) {
            Thread.sleep(10);
        }

        assertNotNull(book);
        assertEquals(1, book.getAsks().size(), "В стакане должен быть 1 лимитный ордер на продажу");
        assertTrue(book.getBids().isEmpty(), "В стакане покупок должно быть пусто");

        // Публикуем ордер на покупку: 1.0 BTC по $60,000 (должен произойти мэтч)
        publishOrder("ord-b", "buyer-1", true, 60000.0, 1.0, true);

        // Ждем мэтча и проверяем, что стакан стал чистым (оба ордера выполнены)
        start = System.currentTimeMillis();
        while (!book.getAsks().isEmpty() && (System.currentTimeMillis() - start < 2000)) {
            Thread.sleep(10);
        }

        assertTrue(book.getAsks().isEmpty(), "Стакан продаж должен быть пуст после сведения");
        assertTrue(book.getBids().isEmpty(), "Стакан покупок должен быть пуст после сведения");
    }

    /**
     * Публикация ордера в RingBuffer
     */
    private void publishOrder(String orderId, String userId, boolean isBuy, double price, double amount, boolean isLimit) {
        long sequence = ringBuffer.next();
        try {
            ChainTxEvent event = ringBuffer.get(sequence);
            ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                    .orderId(orderId)
                    .userId(userId)
                    .marketId(marketId)
                    .isBuy(isBuy)
                    .price(price)
                    .amount(amount)
                    .isLimit(isLimit)
                    .leverage(10.0)
                    .isIsolated(true)
                    .signature("signature")
                    .build();
            event.setTransaction(tx);
        } finally {
            ringBuffer.publish(sequence);
        }
    }
}
