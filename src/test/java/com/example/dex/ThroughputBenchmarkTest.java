package com.example.dex;

import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
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
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class ThroughputBenchmarkTest {

    private final String marketId = "BTC-USD";
    private final MarketSpecification spec = new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0, 0.0);
    
    private final int WARMUP_TX_COUNT = 50_000;
    private final int MEASURED_TX_COUNT = 200_000;
    private final int ITERATIONS = 5;

    private static class BenchmarkingHandler extends StateExecutionHandler {
        private final CountDownLatch latch;

        BenchmarkingHandler(MarginManager marginManager, LiquidationEngine liquidationEngine, FundingCalculator fundingCalculator, CountDownLatch latch) {
            super(marginManager, liquidationEngine, fundingCalculator);
            this.latch = latch;
        }

        @Override
        public void onEvent(ChainTxEvent event, long sequence, boolean endOfBatch) throws Exception {
            super.onEvent(event, sequence, endOfBatch);
            latch.countDown();
        }
    }

    @Test
    public void runWarmedUpBenchmarks() throws Exception {
        PrintStream originalOut = System.out;
        
        originalOut.println("=== НАЧАЛО ПРОГРЕВА JVM (WARMUP) ===");
        
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            for (int i = 0; i < 3; i++) {
                runBenchmarkIteration(WARMUP_TX_COUNT, false);
                runBenchmarkIteration(WARMUP_TX_COUNT, true);
            }
            System.gc();
            Thread.sleep(200);
        } finally {
            System.setOut(originalOut);
        }
        
        originalOut.println("=== ПРОГРЕВ ЗАВЕРШЕН, ЗАПУСК ДИАГНОСТИЧЕСКИХ ИТЕРАЦИЙ ===");

        long totalL2Tps = 0;
        long totalSmrTps = 0;

        for (int i = 1; i <= ITERATIONS; i++) {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            long l2Tps, smrTps;
            try {
                l2Tps = runBenchmarkIteration(MEASURED_TX_COUNT, false);
                smrTps = runBenchmarkIteration(MEASURED_TX_COUNT, true);
            } finally {
                System.setOut(originalOut);
            }
            
            totalL2Tps += l2Tps;
            totalSmrTps += smrTps;

            System.out.println(String.format("Итерация %d/%d | L2 Sequencer: %,d TPS | SMR Node: %,d TPS", 
                    i, ITERATIONS, l2Tps, smrTps));
        }

        long avgL2Tps = totalL2Tps / ITERATIONS;
        long avgSmrTps = totalSmrTps / ITERATIONS;

        System.out.println("\n=== РЕЗУЛЬТАТЫ СРАВНЕНИЯ НА ПРОГРЕТОЙ JVM ===");
        System.out.println(String.format("Средний L2 Sequencer TPS: %,d транзакций/сек", avgL2Tps));
        System.out.println(String.format("Средний SMR Node TPS    : %,d транзакций/сек", avgSmrTps));
        System.out.println("Разница                 : L2 Секвенсор быстрее в " + String.format("%.2f", (double) avgL2Tps / avgSmrTps) + " раз.");
        
        // Дополнительный тест: замер задержки SOR
        long avgSorLatencyNs = runSorLatencyBenchmark();
        System.out.println(String.format("Средняя задержка Smart Order Router (SOR): %,d наносекунд (%,.3f микросекунд)", 
                avgSorLatencyNs, avgSorLatencyNs / 1000.0));
        System.out.println("=========================================================");
    }

    private long runBenchmarkIteration(int txCount, boolean isSmr) throws Exception {
        OracleService oracle = new OracleService();
        oracle.setPrice(marketId, 60000.0);
        MarginManager marginManager = new MarginManager(oracle);
        marginManager.registerMarket(spec);

        marginManager.registerUser("alice", 1_000_000_000.0);
        marginManager.registerUser("bob", 1_000_000_000.0);

        CountDownLatch latch = new CountDownLatch(txCount);
        BenchmarkingHandler handler = new BenchmarkingHandler(
                marginManager,
                new LiquidationEngine(marginManager, oracle),
                new FundingCalculator(marginManager, oracle),
                latch
        );
        handler.registerMarket(marketId);

        int bufferSize = isSmr ? 32768 : 131072;

        Disruptor<ChainTxEvent> disruptor = new Disruptor<>(
                new ChainTxEventFactory(),
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
        disruptor.handleEventsWith(handler);
        RingBuffer<ChainTxEvent> ringBuffer = disruptor.start();

        List<ChainTransaction> transactions = new ArrayList<>(txCount);
        for (int i = 0; i < txCount; i++) {
            boolean isBuy = (i % 2 == 0);
            if (isSmr && (i % 500 == 0)) {
                transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.UPDATE_ORACLE)
                        .marketId(marketId)
                        .price(60000.0 + (i % 100))
                        .timestamp(System.currentTimeMillis() + i)
                        .build());
            } else {
                transactions.add(new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                        .orderId("ord-" + i)
                        .userId(isBuy ? "alice" : "bob")
                        .marketId(marketId)
                        .isBuy(isBuy)
                        .price(isBuy ? 60010.0 : 60000.0)
                        .amount(1.0)
                        .isLimit(true)
                        .leverage(10.0)
                        .isIsolated(true)
                        .build());
            }
        }

        long startTime = System.nanoTime();

        for (ChainTransaction tx : transactions) {
            long seq = ringBuffer.next();
            ringBuffer.get(seq).setTransaction(tx);
            ringBuffer.publish(seq);
        }

        latch.await();
        long endTime = System.nanoTime();
        disruptor.shutdown();

        double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        return (long) (txCount / durationSeconds);
    }

    private long runSorLatencyBenchmark() throws Exception {
        OracleService oracle = new OracleService();
        oracle.setPrice(marketId, 60000.0);
        MarginManager marginManager = new MarginManager(oracle);
        marginManager.registerMarket(spec);

        CountDownLatch latch = new CountDownLatch(1);
        StateExecutionHandler handlerSMR = new StateExecutionHandler(
                marginManager,
                new LiquidationEngine(marginManager, oracle),
                new FundingCalculator(marginManager, oracle)
        );
        handlerSMR.registerMarket(marketId);

        Disruptor<ChainTxEvent> disruptorSMR = new Disruptor<>(
                new ChainTxEventFactory(),
                1024,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
        disruptorSMR.handleEventsWith(handlerSMR);
        RingBuffer<ChainTxEvent> bufferSMR = disruptorSMR.start();

        StateExecutionHandler handlerL2 = new StateExecutionHandler(
                marginManager,
                new LiquidationEngine(marginManager, oracle),
                new FundingCalculator(marginManager, oracle)
        );
        handlerL2.registerMarket(marketId);

        Disruptor<ChainTxEvent> disruptorL2 = new Disruptor<>(
                new ChainTxEventFactory(),
                1024,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
        disruptorL2.handleEventsWith(handlerL2);
        RingBuffer<ChainTxEvent> bufferL2 = disruptorL2.start();

        SmartOrderRouter router = new SmartOrderRouter(bufferSMR, handlerSMR, bufferL2, handlerL2);

        ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                .orderId("bench-sor-ord")
                .userId("alice")
                .marketId(marketId)
                .isBuy(true)
                .price(60000.0)
                .amount(1.0)
                .isLimit(true)
                .build();

        // Прогреваем SOR
        for (int i = 0; i < 5000; i++) {
            router.routeOrder(tx, RoutingPolicy.BEST_EXECUTION);
        }

        // Замеряем 10 000 вызовов
        int tests = 10_000;
        long startTime = System.nanoTime();
        for (int i = 0; i < tests; i++) {
            router.routeOrder(tx, RoutingPolicy.BEST_EXECUTION);
        }
        long endTime = System.nanoTime();

        disruptorSMR.shutdown();
        disruptorL2.shutdown();

        return (endTime - startTime) / tests;
    }
}
