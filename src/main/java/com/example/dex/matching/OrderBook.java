package com.example.dex.matching;

import com.example.dex.models.Order;
import com.example.dex.models.Trade;

import java.util.*;

/**
 * Класс OrderBook представляет собой стакан ордеров для одного рынка.
 * Реализует ценовое-временное приоритетное сведение ордеров (Price-Time Priority).
 */
public class OrderBook {

    private final String marketId;
    
    // Покупки (Bids): сортируются по убыванию цены (наивысшая цена первой)
    private final TreeMap<Double, LinkedList<Order>> bids;
    
    // Продажи (Asks): сортируются по возрастанию цены (наинизшая цена первой)
    private final TreeMap<Double, LinkedList<Order>> asks;

    public OrderBook(String marketId) {
        this.marketId = marketId;
        this.bids = new TreeMap<>(Collections.reverseOrder());
        this.asks = new TreeMap<>();
    }

    public String getMarketId() {
        return marketId;
    }

    public TreeMap<Double, LinkedList<Order>> getBids() {
        return bids;
    }

    public TreeMap<Double, LinkedList<Order>> getAsks() {
        return asks;
    }

    /**
     * Обрабатывает входящий ордер, пытаясь свести его с противоположной стороной стакана.
     * Если ордер заполнен не полностью и является лимитным, остаток добавляется в стакан.
     *
     * @param order Входящий ордер
     * @return Список совершенных сделок
     */
    public List<Trade> processOrder(Order order) {
        List<Trade> trades = new ArrayList<>();

        if (order.isBuy()) {
            matchOrder(order, asks, bids, trades);
        } else {
            matchOrder(order, bids, asks, trades);
        }

        return trades;
    }

    private void matchOrder(Order incomingOrder, 
                            TreeMap<Double, LinkedList<Order>> oppositeSide, 
                            TreeMap<Double, LinkedList<Order>> sameSide, 
                            List<Trade> trades) {
        
        Iterator<Map.Entry<Double, LinkedList<Order>>> priceLevelIterator = oppositeSide.entrySet().iterator();

        while (priceLevelIterator.hasNext() && incomingOrder.getRemainingAmount() > 0) {
            Map.Entry<Double, LinkedList<Order>> entry = priceLevelIterator.next();
            double bestOppositePrice = entry.getKey();

            // Проверка ценового условия для лимитного ордера
            if (incomingOrder.isLimit()) {
                if (incomingOrder.isBuy() && bestOppositePrice > incomingOrder.getPrice()) {
                    break; // Продавец хочет больше, чем покупатель готов заплатить
                }
                if (!incomingOrder.isBuy() && bestOppositePrice < incomingOrder.getPrice()) {
                    break; // Покупатель предлагает меньше, чем продавец хочет получить
                }
            }

            LinkedList<Order> ordersAtLevel = entry.getValue();
            Iterator<Order> orderIterator = ordersAtLevel.iterator();

            while (orderIterator.hasNext() && incomingOrder.getRemainingAmount() > 0) {
                Order makerOrder = orderIterator.next();
                
                double matchVolume = Math.min(incomingOrder.getRemainingAmount(), makerOrder.getRemainingAmount());
                double executionPrice = makerOrder.getPrice(); // Цена сделки определяется ценой лимитного ордера в стакане (мейкера)

                double buyerLeverage = incomingOrder.isBuy() ? incomingOrder.getLeverage() : makerOrder.getLeverage();
                boolean buyerIsolated = incomingOrder.isBuy() ? incomingOrder.isIsolated() : makerOrder.isIsolated();
                double sellerLeverage = incomingOrder.isBuy() ? makerOrder.getLeverage() : incomingOrder.getLeverage();
                boolean sellerIsolated = incomingOrder.isBuy() ? makerOrder.isIsolated() : incomingOrder.isIsolated();

                Trade trade = new Trade(
                        incomingOrder.isBuy() ? incomingOrder.getUserId() : makerOrder.getUserId(),
                        incomingOrder.isBuy() ? makerOrder.getUserId() : incomingOrder.getUserId(),
                        marketId,
                        executionPrice,
                        matchVolume,
                        buyerLeverage,
                        buyerIsolated,
                        sellerLeverage,
                        sellerIsolated,
                        System.currentTimeMillis()
                );
                trades.add(trade);

                // Обновляем оставшиеся объемы ордеров
                incomingOrder.setRemainingAmount(incomingOrder.getRemainingAmount() - matchVolume);
                makerOrder.setRemainingAmount(makerOrder.getRemainingAmount() - matchVolume);

                // Если ордер в стакане полностью исполнен, удаляем его
                if (makerOrder.getRemainingAmount() <= 0) {
                    orderIterator.remove();
                }
            }

            // Если на данном ценовом уровне больше нет ордеров, удаляем ценовой уровень
            if (ordersAtLevel.isEmpty()) {
                priceLevelIterator.remove();
            }
        }

        // Если входящий ордер выполнен не полностью и он лимитный — добавляем остаток в стакан
        if (incomingOrder.isLimit() && incomingOrder.getRemainingAmount() > 0) {
            sameSide.computeIfAbsent(incomingOrder.getPrice(), k -> new LinkedList<>()).add(incomingOrder);
        }
    }

    /**
     * Отменяет ордер по его ID в стакане.
     */
    public boolean cancelOrder(String orderId) {
        // Ищем в покупках
        for (LinkedList<Order> list : bids.values()) {
            Iterator<Order> it = list.iterator();
            while (it.hasNext()) {
                if (it.next().getOrderId().equals(orderId)) {
                    it.remove();
                    return true;
                }
            }
        }
        // Ищем в продажах
        for (LinkedList<Order> list : asks.values()) {
            Iterator<Order> it = list.iterator();
            while (it.hasNext()) {
                if (it.next().getOrderId().equals(orderId)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }
}
