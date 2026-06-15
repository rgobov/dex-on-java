package com.example.dex;

import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.AccountBalance;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.Position;
import com.example.dex.oracle.OracleService;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class StateMachineReplicationTest {

    // Класс, группирующий все компоненты одной ноды-валидатора
    private static class ValidatorNode {
        final String name;
        final OracleService oracleService;
        final MarginManager marginManager;
        final LiquidationEngine liquidationEngine;
        final FundingCalculator fundingCalculator;
        final StateExecutionHandler handler;
        final Disruptor<ChainTxEvent> disruptor;
        final RingBuffer<ChainTxEvent> ringBuffer;

        ValidatorNode(String name, String marketId, MarketSpecification marketSpec) {
            this.name = name;
            this.oracleService = new OracleService();
            this.oracleService.setPrice(marketId, 60000.0);
            
            this.marginManager = new MarginManager(oracleService);
            this.marginManager.registerMarket(marketSpec);
            
            this.liquidationEngine = new LiquidationEngine(marginManager, oracleService);
            
            this.fundingCalculator = new FundingCalculator(marginManager, oracleService);
            // Ставим интервал фандинга 1 минуту для легкого тестирования в детерминированном режиме
            this.fundingCalculator.setFundingIntervalMs(60 * 1000);

            this.handler = new StateExecutionHandler(marginManager, liquidationEngine, fundingCalculator);
            this.handler.registerMarket(marketId);

            this.disruptor = new Disruptor<>(
                    new ChainTxEventFactory(),
                    2048,
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

        void sendTransaction(ChainTransaction tx) {
            long sequence = ringBuffer.next();
            try {
                ChainTxEvent event = ringBuffer.get(sequence);
                event.setTransaction(tx);
            } finally {
                ringBuffer.publish(sequence);
            }
        }
    }

    private ValidatorNode nodeA;
    private ValidatorNode nodeB;
    private ValidatorNode nodeC;
    private final String marketId = "BTC-USD";
    private final MarketSpecification marketSpec = new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0, 0.0);

    @BeforeEach
    public void setUp() {
        nodeA = new ValidatorNode("Validator-A", marketId, marketSpec);
        nodeB = new ValidatorNode("Validator-B", marketId, marketSpec);
        nodeC = new ValidatorNode("Validator-C", marketId, marketSpec);
    }

    @AfterEach
    public void tearDown() {
        nodeA.stop();
        nodeB.stop();
        nodeC.stop();
    }

    @Test
    public void testDeterministicReplication() throws Exception {
        List<ChainTransaction> transactions = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // 1. Создаем депозиты для пользователей
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("alice").amount(10000.0).timestamp(startTime).build());
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("bob").amount(10000.0).timestamp(startTime).build());
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                .userId("charlie").amount(10000.0).timestamp(startTime).build());

        // 2. Отправляем ордера в стакан ордеров
        // Alice ставит ордер на покупку: 1.0 BTC по $60,000 (Cross)
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("ord-alice-1").userId("alice").marketId(marketId)
                .isBuy(true).price(60000.0).amount(1.0).leverage(10.0).isIsolated(false)
                .timestamp(startTime + 100).build());

        // Bob ставит ордер на продажу: 0.5 BTC по $60,000 (Isolated)
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("ord-bob-1").userId("bob").marketId(marketId)
                .isBuy(false).price(60000.0).amount(0.5).leverage(10.0).isIsolated(true)
                .timestamp(startTime + 200).build());

        // Charlie ставит ордер на продажу: 0.5 BTC по $60,000 (Cross)
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("ord-charlie-1").userId("charlie").marketId(marketId)
                .isBuy(false).price(60000.0).amount(0.5).leverage(10.0).isIsolated(false)
                .timestamp(startTime + 300).build());

        // 3. Отправляем изменение оракула (цена падает до $58,000)
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.UPDATE_ORACLE)
                .marketId(marketId).price(58000.0)
                .timestamp(startTime + 400).build());

        // 4. Отправляем изменение оракула + время фандинга (прошло 2 минуты = 120 000 мс)
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.UPDATE_ORACLE)
                .marketId(marketId).price(57000.0)
                .timestamp(startTime + 125000).build());

        // 5. Alice пытается вывести средства
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.WITHDRAW)
                .userId("alice").amount(1000.0)
                .timestamp(startTime + 130000).build());

        // 6. Отмена несуществующего ордера (для проверки детерминированности сбоя)
        transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.CANCEL_ORDER)
                .orderId("non-existent-id").marketId(marketId)
                .timestamp(startTime + 140000).build());

        // Публикуем все транзакции на всех трех нодах в одном и том же порядке
        for (ChainTransaction tx : transactions) {
            nodeA.sendTransaction(tx);
            nodeB.sendTransaction(tx);
            nodeC.sendTransaction(tx);
        }

        // Ждем завершения обработки всех событий в Disruptor (до 3 секунд)
        long waitStart = System.currentTimeMillis();
        while (nodeA.ringBuffer.getCursor() < transactions.size() - 1 && (System.currentTimeMillis() - waitStart < 3000)) {
            Thread.sleep(10);
        }
        Thread.sleep(100); // Дополнительное время на завершение хендлеров

        // Сравниваем состояние трех нод
        List<String> users = List.of("alice", "bob", "charlie");

        for (String user : users) {
            AccountBalance balA = nodeA.marginManager.getBalance(user);
            AccountBalance balB = nodeB.marginManager.getBalance(user);
            AccountBalance balC = nodeC.marginManager.getBalance(user);

            assertNotNull(balA);
            assertNotNull(balB);
            assertNotNull(balC);

            // Проверяем полное совпадение свободных и заблокированных балансов
            assertEquals(balA.getFreeBalance(), balB.getFreeBalance(), "Несовпадение свободного баланса у " + user + " между A и B");
            assertEquals(balA.getFreeBalance(), balC.getFreeBalance(), "Несовпадение свободного баланса у " + user + " между A и C");

            assertEquals(balA.getLockedMargin(), balB.getLockedMargin(), "Несовпадение заблокированного залога у " + user + " между A and B");
            assertEquals(balA.getLockedMargin(), balC.getLockedMargin(), "Несовпадение заблокированного залога у " + user + " между A and C");

            // Проверяем открытые позиции
            Position posA = nodeA.marginManager.getPosition(user, marketId);
            Position posB = nodeB.marginManager.getPosition(user, marketId);
            Position posC = nodeC.marginManager.getPosition(user, marketId);

            if (posA != null || posB != null || posC != null) {
                assertNotNull(posA);
                assertNotNull(posB);
                assertNotNull(posC);

                assertEquals(posA.getSize(), posB.getSize(), "Несовпадение размера позиции у " + user + " между A и B");
                assertEquals(posA.getSize(), posC.getSize(), "Несовпадение размера позиции у " + user + " между A и C");

                assertEquals(posA.getEntryPrice(), posB.getEntryPrice(), "Несовпадение цены входа у " + user + " между A и B");
                assertEquals(posA.getEntryPrice(), posC.getEntryPrice(), "Несовпадение цены входа у " + user + " между A и C");

                assertEquals(posA.getMargin(), posB.getMargin(), "Несовпадение маржи позиции у " + user + " между A и B");
                assertEquals(posA.getMargin(), posC.getMargin(), "Несовпадение маржи позиции у " + user + " между A и C");
            }
        }

        // Сравниваем состояние стаканов ордеров
        OrderBook bookA = nodeA.handler.getOrderBook(marketId);
        OrderBook bookB = nodeB.handler.getOrderBook(marketId);
        OrderBook bookC = nodeC.handler.getOrderBook(marketId);

        assertEquals(bookA.getBids().size(), bookB.getBids().size());
        assertEquals(bookA.getBids().size(), bookC.getBids().size());

        assertEquals(bookA.getAsks().size(), bookB.getAsks().size());
        assertEquals(bookA.getAsks().size(), bookC.getAsks().size());
        
        System.out.println("SMR DETECTED SUCCESS: All replicas have exactly identical end state!");
    }
}
