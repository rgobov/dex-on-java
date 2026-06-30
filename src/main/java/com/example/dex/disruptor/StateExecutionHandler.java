package com.example.dex.disruptor;

import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.ChainTransaction;
import com.example.dex.models.Order;
import com.example.dex.models.Trade;
import com.lmax.disruptor.EventHandler;

import java.security.PublicKey;
import java.util.*;

/**
 * Детерминированный обработчик команд (Execution Engine) для репликации состояния.
 * Выполняет все транзакции строго последовательно в один поток.
 */
public class StateExecutionHandler implements EventHandler<ChainTxEvent> {

    public static final class PendingWithdrawal {
        public final String requestId;
        public final String userId;
        public final double amount;
        public final long timestamp;
        public final boolean isFastWithdraw;
        public final int fastFeeBps;
        public final String beneficiary;  // LP адрес для fast withdrawal

        public PendingWithdrawal(String requestId, String userId, double amount, long timestamp) {
            this(requestId, userId, amount, timestamp, false, 0, null);
        }

        public PendingWithdrawal(String requestId, String userId, double amount, long timestamp,
                                 boolean isFastWithdraw, int fastFeeBps, String beneficiary) {
            this.requestId = requestId;
            this.userId = userId;
            this.amount = amount;
            this.timestamp = timestamp;
            this.isFastWithdraw = isFastWithdraw;
            this.fastFeeBps = fastFeeBps;
            this.beneficiary = beneficiary;
        }
    }

    private final Map<String, OrderBook> orderBooks = new HashMap<>();
    private final MarginManager marginManager;
    private final LiquidationEngine liquidationEngine;
    private final FundingCalculator fundingCalculator;
    private final java.util.List<Trade> executedTrades = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.Set<String> processedBridgeTxIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.List<PendingWithdrawal> pendingWithdrawals = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public StateExecutionHandler(MarginManager marginManager, LiquidationEngine liquidationEngine, FundingCalculator fundingCalculator) {
        this.marginManager = marginManager;
        this.liquidationEngine = liquidationEngine;
        this.fundingCalculator = fundingCalculator;
    }

    public List<Trade> getExecutedTrades() {
        return executedTrades;
    }

    public java.util.Set<String> getProcessedBridgeTxIds() {
        return processedBridgeTxIds;
    }

    public List<PendingWithdrawal> getPendingWithdrawals() {
        return List.copyOf(pendingWithdrawals);
    }

    public void removePendingWithdrawal(PendingWithdrawal pw) {
        pendingWithdrawals.remove(pw);
    }

    public void registerMarket(String marketId) {
        orderBooks.put(marketId, new OrderBook(marketId));
    }

    public OrderBook getOrderBook(String marketId) {
        return orderBooks.get(marketId);
    }

    public java.util.Set<String> getRegisteredMarkets() {
        return orderBooks.keySet();
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
                    // Dedup для bridge-депозитов (orderId = ticketId)
                    if (tx.getOrderId() != null && !tx.getOrderId().isBlank()) {
                        if (!processedBridgeTxIds.add(tx.getOrderId())) {
                            break;
                        }
                    }
                    marginManager.deposit(tx.getUserId(), tx.getAmount());
                    break;

                case WITHDRAW:
                    marginManager.withdraw(tx.getUserId(), tx.getAmount());
                    break;

                case WITHDRAW_SIGNED:
                    handleWithdrawSigned(tx);
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
                    marginManager.getOracleService().setPrice(tx.getMarketId(), tx.getPrice());

                    var marketSpec = marginManager.getMarketSpec(tx.getMarketId());
                    if (marketSpec != null) {
                        List<String> activeUsers = List.copyOf(marginManager.getAllRegisteredUsers());
                        liquidationEngine.checkLiquidations(tx.getMarketId(), marketSpec, activeUsers);

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

    private void handleWithdrawSigned(ChainTransaction tx) {
        String userId = tx.getUserId();
        double amount = tx.getAmount();
        long timestamp = tx.getTimestamp();
        boolean isFast = tx.isFastWithdraw();
        int feeBps = tx.getFastFeeBps();

        // Верифицируем RSA-подпись пользователя
        // Telegram-пользователи (tg-*) аутентифицированы через Telegram WebApp initData,
        // RSA не требуется. Для остальных — проверяем подпись.
        if (!userId.startsWith("tg-")) {
            String message = userId + ":" + amount + ":" + timestamp + ":" + isFast + ":" + feeBps;
            try {
                PublicKey pubKey = DexSignatureUtil.decodePublicKey(userId);
                if (!DexSignatureUtil.verify(message, tx.getSignature(), pubKey)) {
                    System.out.println("[REJECT] WITHDRAW_SIGNED: неверная подпись для " + userId);
                    return;
                }
            } catch (Exception e) {
                System.out.println("[REJECT] WITHDRAW_SIGNED: ошибка верификации для " + userId + ": " + e.getMessage());
                return;
            }
        }

        boolean ok = marginManager.withdraw(userId, amount);
        if (ok) {
            String requestId = "l2-withdraw-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            if (isFast) {
                double fee = amount * feeBps / 10000.0;
                String beneficiary = tx.getMarketId() != null ? tx.getMarketId() : "lp-default";
                pendingWithdrawals.add(new PendingWithdrawal(requestId, userId, amount, timestamp,
                        true, feeBps, beneficiary));
                System.out.println("[ACCEPT] FAST WITHDRAW " + userId + " amount=" + amount
                        + " fee=" + String.format("%.2f", fee) + " → L1 immediately");
            } else {
                pendingWithdrawals.add(new PendingWithdrawal(requestId, userId, amount, timestamp));
                System.out.println("[ACCEPT] WITHDRAW_SIGNED " + userId + " amount=" + amount + " → pending L1 finalization");
            }
        } else {
            System.out.println("[REJECT] WITHDRAW_SIGNED: недостаточно средств для " + userId);
        }
    }
}
