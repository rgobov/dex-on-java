package com.example.dex.funding;

import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.AccountBalance;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.Position;
import com.example.dex.oracle.OracleService;

/**
 * Класс FundingCalculator рассчитывает текущую ставку фандинга (Funding Rate)
 * и списывает/начисляет средства между покупателями и продавцами для привязки цены фьючерса к споту.
 */
public class FundingCalculator {

    private final MarginManager marginManager;
    private final OracleService oracleService;

    public FundingCalculator(MarginManager marginManager, OracleService oracleService) {
        this.marginManager = marginManager;
        this.oracleService = oracleService;
    }

    /**
     * Рассчитывает текущую ставку фандинга на основе разницы цен стакана (Mark Price) и оракула (Index Price).
     *
     * @param orderBook Стакан ордеров для расчета Mark Price
     * @param marketSpec Спецификация рынка
     * @return Рассчитанная ставка фандинга
     */
    public double calculateFundingRate(OrderBook orderBook, MarketSpecification marketSpec) {
        double indexPrice = oracleService.getPrice(marketSpec.getMarketId());
        
        Double bestBid = orderBook.getBids().isEmpty() ? null : orderBook.getBids().firstKey();
        Double bestAsk = orderBook.getAsks().isEmpty() ? null : orderBook.getAsks().firstKey();
        
        double markPrice;
        if (bestBid != null && bestAsk != null) {
            markPrice = (bestBid + bestAsk) / 2.0;
        } else {
            markPrice = indexPrice; // Если ликвидности нет, цена равна оракулу
        }

        // Ставка фандинга = (Mark Price - Index Price) / Index Price
        double rate = (markPrice - indexPrice) / indexPrice;

        // Ограничиваем фандинг разумными лимитами (например, от -0.5% до +0.5% за интервал)
        double maxRate = 0.005;
        if (rate > maxRate) {
            rate = maxRate;
        }
        if (rate < -maxRate) {
            rate = -maxRate;
        }

        marketSpec.setFundingRate(rate);
        return rate;
    }

    /**
     * Производит списание и начисление фандинга по всем открытым позициям для указанных пользователей и рынка.
     *
     * @param marketId ID рынка
     * @param orderBook Стакан рынка
     * @param marketSpec Спецификация рынка
     * @param userIds Список ID пользователей на рынке
     */
    public void applyFunding(String marketId, OrderBook orderBook, MarketSpecification marketSpec, Iterable<String> userIds) {
        double rate = calculateFundingRate(orderBook, marketSpec);
        if (rate == 0.0) {
            return;
        }

        double indexPrice = oracleService.getPrice(marketId);

        for (String userId : userIds) {
            Position pos = marginManager.getPosition(userId, marketId);
            if (pos == null || pos.getSize() == 0.0) {
                continue;
            }

            AccountBalance balance = marginManager.getBalance(userId);
            if (balance == null) {
                continue;
            }

            // Сумма фандинга = объем позиции * цена оракула * ставка фандинга
            double fundingPayment = pos.getSize() * indexPrice * rate;

            if (pos.isLong()) {
                // Лонги платят, если ставка положительная
                if (fundingPayment > 0.0) {
                    balance.payFunding(fundingPayment);
                } else {
                    balance.addProfit(-fundingPayment);
                }
            } else {
                // Шорты получают, если ставка положительная (то есть им платят лонги)
                if (fundingPayment > 0.0) {
                    balance.addProfit(fundingPayment);
                } else {
                    balance.payFunding(-fundingPayment);
                }
            }
        }
    }

    private long lastFundingTime = 0;
    private long fundingIntervalMs = 60 * 60 * 1000; // 1 час по умолчанию

    public void setFundingIntervalMs(long intervalMs) {
        this.fundingIntervalMs = intervalMs;
    }

    public void applyFundingDeterministically(String marketId, OrderBook orderBook, MarketSpecification marketSpec, Iterable<String> userIds, long currentTimestamp) {
        if (lastFundingTime == 0) {
            lastFundingTime = currentTimestamp;
            return;
        }
        if (currentTimestamp - lastFundingTime >= fundingIntervalMs) {
            applyFunding(marketId, orderBook, marketSpec, userIds);
            lastFundingTime = currentTimestamp;
            System.out.printf("[FUNDING] Начислен фандинг для %s на метке времени %d%n", marketId, currentTimestamp);
        }
    }
}
