package com.example.dex.disruptor;

import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.Order;
import com.example.dex.models.Trade;
import com.lmax.disruptor.EventHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Детерминированный обработчик команд (Execution Engine) для репликации состояния.
 * Выполняет все транзакции строго последовательно в один поток.
 */
public class StateExecutionHandler implements EventHandler<ChainTxEvent> {

    private final Map<String, OrderBook> orderBooks = new HashMap<>();
    private final MarginManager marginManager;
    private final LiquidationEngine liquidationEngine;
    private final FundingCalculator fundingCalculator;
    private final java.util.List<Trade> executedTrades = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public StateExecutionHandler(MarginManager marginManager, LiquidationEngine liquidationEngine, FundingCalculator fundingCalculator) {
        this.marginManager = marginManager;
        this.liquidationEngine = liquidationEngine;
        this.fundingCalculator = fundingCalculator;
    }

    public List<Trade> getExecutedTrades() {
        return executedTrades;
    }

    public void registerMarket(String marketId) {
        orderBooks.put(marketId, new OrderBook(marketId));
    }

    public OrderBook getOrderBook(String marketId) {
        return orderBooks.get(marketId);
    }

    @Override
    public void onEvent(ChainTxEvent event, long sequence, boolean endOfBatch) throws Exception {
        ChainTransaction tx = event.getTransaction();
        if (tx == null) {
            return;
        }

        try {
            switch (tx.getType()) {
                case DEPOSIT:
                    marginManager.deposit(tx.getUserId(), tx.getAmount());
                    break;

                case WITHDRAW:
                    // Проверка баланса и вывод
                    marginManager.withdraw(tx.getUserId(), tx.getAmount());
                    break;

                case PLACE_ORDER:
                    Order order = tx.toOrder();
                    if (!marginManager.checkAndLockMarginForOrder(order)) {
                        System.out.println("[REJECT] Ордер " + order.getOrderId() + " отклонен: недостаточно маржи");
                        break;
                    }

                    OrderBook orderBook = orderBooks.get(order.getMarketId());
                    if (orderBook != null) {
                        List<Trade> trades = orderBook.processOrder(order);
                        for (Trade trade : trades) {
                            executedTrades.add(trade);
                            marginManager.processTrade(
                                    trade,
                                    trade.getBuyerLeverage(),
                                    trade.isBuyerIsolated(),
                                    trade.getSellerLeverage(),
                                    trade.isSellerIsolated()
                            );
                        }
                    } else {
                        System.out.println("Market " + order.getMarketId() + " not registered in matching engine.");
                    }
                    break;

                case CANCEL_ORDER:
                    OrderBook ob = orderBooks.get(tx.getMarketId());
                    if (ob != null) {
                        ob.cancelOrder(tx.getOrderId());
                    }
                    break;

                case UPDATE_ORACLE:
                    // Обновляем оракул детерминировано
                    marginManager.getOracleService().setPrice(tx.getMarketId(), tx.getPrice());

                    // Запускаем детерминированную ликвидацию
                    var marketSpec = marginManager.getMarketSpec(tx.getMarketId());
                    if (marketSpec != null) {
                        List<String> activeUsers = List.copyOf(marginManager.getAllRegisteredUsers());
                        liquidationEngine.checkLiquidations(tx.getMarketId(), marketSpec, activeUsers);

                        // Запускаем детерминированный фандинг
                        OrderBook obForFunding = orderBooks.get(tx.getMarketId());
                        if (obForFunding != null) {
                            fundingCalculator.applyFundingDeterministically(
                                    tx.getMarketId(),
                                    obForFunding,
                                    marketSpec,
                                    activeUsers,
                                    tx.getTimestamp()
                            );
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            System.err.println("Ошибка выполнения транзакции " + tx + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            event.clear();
        }
    }
}
