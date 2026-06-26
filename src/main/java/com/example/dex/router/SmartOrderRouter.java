package com.example.dex.router;

import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.Order;
import com.lmax.disruptor.RingBuffer;

import java.util.LinkedList;
import java.util.TreeMap;

/**
 * Умный маршрутизатор ордеров (Smart Order Router - SOR).
 * Анализирует состояние стаканов обеих систем (SMR и L2 Sequencer) и маршрутизирует
 * ордера согласно выбранной политике RoutingPolicy.
 */
public final class SmartOrderRouter {

    private final RingBuffer<ChainTxEvent> smrRingBuffer;
    private final StateExecutionHandler smrHandler;
    private final RingBuffer<ChainTxEvent> l2RingBuffer;
    private final StateExecutionHandler l2Handler;

    public SmartOrderRouter(RingBuffer<ChainTxEvent> smrRingBuffer, StateExecutionHandler smrHandler,
                            RingBuffer<ChainTxEvent> l2RingBuffer, StateExecutionHandler l2Handler) {
        this.smrRingBuffer = smrRingBuffer;
        this.smrHandler = smrHandler;
        this.l2RingBuffer = l2RingBuffer;
        this.l2Handler = l2Handler;
    }

    /**
     * Маршрутизирует транзакцию/ордер.
     */
    public final void routeOrder(ChainTransaction tx, RoutingPolicy policy) {
        if (tx.getType() != ChainTransaction.TxType.PLACE_ORDER) {
            // Системные события (депозиты, оракулы, выводы) синхронизируются на обеих площадках
            sendToBuffer(smrRingBuffer, tx);
            sendToBuffer(l2RingBuffer, tx);
            return;
        }

        switch (policy) {
            case FORCE_SMR:
                sendToBuffer(smrRingBuffer, tx);
                break;
            case FORCE_L2:
                sendToBuffer(l2RingBuffer, tx);
                break;
            case BEST_EXECUTION:
                routeBestExecution(tx);
                break;
        }
    }

    private final void sendToBuffer(RingBuffer<ChainTxEvent> ringBuffer, ChainTransaction tx) {
        long sequence = ringBuffer.next();
        try {
            ChainTxEvent event = ringBuffer.get(sequence);
            event.setTransaction(tx);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    private final void routeBestExecution(ChainTransaction tx) {
        OrderBook smrBook = smrHandler.getOrderBook(tx.getMarketId());
        OrderBook l2Book = l2Handler.getOrderBook(tx.getMarketId());

        // Если одна из книг недоступна, отправляем на SMR
        if (smrBook == null || l2Book == null) {
            sendToBuffer(smrRingBuffer, tx);
            return;
        }

        double remainingAmount = tx.getAmount();
        boolean isBuy = tx.isBuy();

        // Получаем противоположную сторону стакана
        TreeMap<Double, LinkedList<Order>> smrOpposite = isBuy ? smrBook.getAsks() : smrBook.getBids();
        TreeMap<Double, LinkedList<Order>> l2Opposite = isBuy ? l2Book.getAsks() : l2Book.getBids();

        Double bestSmrPrice = smrOpposite.isEmpty() ? null : smrOpposite.firstKey();
        Double bestL2Price = l2Opposite.isEmpty() ? null : l2Opposite.firstKey();

        // Если ликвидности нет на обеих площадках
        if (bestSmrPrice == null && bestL2Price == null) {
            sendToBuffer(smrRingBuffer, tx);
            return;
        }

        // Если ликвидность есть только на одной площадке
        if (bestSmrPrice == null) {
            sendToBuffer(l2RingBuffer, tx);
            return;
        }
        if (bestL2Price == null) {
            sendToBuffer(smrRingBuffer, tx);
            return;
        }

        // Для покупки: L2 лучше, если цена L2 ниже, чем на SMR.
        // Для продажи: L2 лучше, если цена L2 выше, чем на SMR.
        boolean l2IsBetter = isBuy ? (bestL2Price < bestSmrPrice) : (bestL2Price > bestSmrPrice);

        if (l2IsBetter) {
            double volumeAtLevel = getVolumeAtBestLevel(l2Opposite);
            if (volumeAtLevel >= remainingAmount) {
                // Вся ликвидность забирается на L2
                sendToBuffer(l2RingBuffer, tx);
            } else {
                // Дробим ордер
                ChainTransaction txL2 = splitTransaction(tx, volumeAtLevel, tx.getOrderId() + "-r1");
                ChainTransaction txSMR = splitTransaction(tx, remainingAmount - volumeAtLevel, tx.getOrderId() + "-r2");
                sendToBuffer(l2RingBuffer, txL2);
                sendToBuffer(smrRingBuffer, txSMR);
            }
        } else {
            // SMR лучше
            double volumeAtLevel = getVolumeAtBestLevel(smrOpposite);
            if (volumeAtLevel >= remainingAmount) {
                sendToBuffer(smrRingBuffer, tx);
            } else {
                // Дробим ордер
                ChainTransaction txSMR = splitTransaction(tx, volumeAtLevel, tx.getOrderId() + "-r1");
                ChainTransaction txL2 = splitTransaction(tx, remainingAmount - volumeAtLevel, tx.getOrderId() + "-r2");
                sendToBuffer(smrRingBuffer, txSMR);
                sendToBuffer(l2RingBuffer, txL2);
            }
        }
    }

    private final double getVolumeAtBestLevel(TreeMap<Double, LinkedList<Order>> oppositeSide) {
        if (oppositeSide.isEmpty()) return 0.0;
        double bestPrice = oppositeSide.firstKey();
        LinkedList<Order> orders = oppositeSide.get(bestPrice);
        double totalVolume = 0.0;
        for (Order o : orders) {
            totalVolume += o.getRemainingAmount();
        }
        return totalVolume;
    }

    private final ChainTransaction splitTransaction(ChainTransaction original, double splitAmount, String newOrderId) {
        return new ChainTransaction.Builder(original.getType())
                .orderId(newOrderId)
                .userId(original.getUserId())
                .marketId(original.getMarketId())
                .isBuy(original.isBuy())
                .isLimit(original.isLimit())
                .price(original.getPrice())
                .amount(splitAmount)
                .leverage(original.getLeverage())
                .isIsolated(original.isIsolated())
                .signature(original.getSignature())
                .timestamp(original.getTimestamp())
                .build();
    }
}
