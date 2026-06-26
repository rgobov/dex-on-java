package com.example.dex.margin;

import com.example.dex.models.AccountBalance;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.Position;
import com.example.dex.oracle.OracleService;

import java.util.Map;

/**
 * Класс LiquidationEngine фоном или перед сведением ордеров проверяет позиции пользователей
 * и ликвидирует те, чей залог больше не покрывает убытки при движении цены.
 */
public class LiquidationEngine {

    private final MarginManager marginManager;
    private final OracleService oracleService;

    public LiquidationEngine(MarginManager marginManager, OracleService oracleService) {
        this.marginManager = marginManager;
        this.oracleService = oracleService;
    }

    /**
     * Проверяет позиции пользователей по указанному рынку и ликвидирует при необходимости.
     *
     * @param marketId ID рынка
     * @param marketSpec Спецификация рынка
     * @param userIds Список ID пользователей для проверки
     */
    public void checkLiquidations(String marketId, MarketSpecification marketSpec, Iterable<String> userIds) {
        double currentIndexPrice = oracleService.getPrice(marketId);
        double mmRate = marketSpec.getMaintenanceMarginRate();

        for (String userId : userIds) {
            Position pos = marginManager.getPosition(userId, marketId);
            if (pos == null || pos.getSize() == 0.0) {
                continue;
            }

            if (pos.isIsolated()) {
                // Изолированный режим: проверяем конкретную позицию
                double liqPrice = pos.calculateLiquidationPrice(mmRate);
                boolean shouldLiquidate = false;

                if (pos.isLong() && currentIndexPrice <= liqPrice) {
                    shouldLiquidate = true;
                } else if (!pos.isLong() && currentIndexPrice >= liqPrice) {
                    shouldLiquidate = true;
                }

                if (shouldLiquidate) {
                    liquidateIsolatedPosition(pos, userId, marketId, currentIndexPrice, liqPrice);
                }
            } else {
                // Кросс-режим: проверяем суммарное состояние кросс-позиций пользователя
                checkCrossLiquidationForUser(userId);
            }
        }
    }

    private void liquidateIsolatedPosition(Position pos, String userId, String marketId, double currentPrice, double liqPrice) {
        System.out.printf("[LIQUIDATION][ISOLATED] Пользователь %s был ликвидирован на рынке %s! Цена оракула: %.2f, Цена ликвидации: %.2f, Объем позиции: %.4f%n",
                userId, marketId, currentPrice, liqPrice, pos.getSize());

        AccountBalance balance = marginManager.getBalance(userId);
        if (balance != null) {
            // При ликвидации изолированный залог полностью списывается
            double penalty = pos.getMargin();
            balance.deductLoss(penalty);
        }

        // Обнуляем позицию
        pos.setSize(0.0);
        pos.setMargin(0.0);
        pos.setEntryPrice(0.0);
    }

    private void checkCrossLiquidationForUser(String userId) {
        AccountBalance balance = marginManager.getBalance(userId);
        Map<String, Position> userPositions = marginManager.getUserPositions(userId);
        if (balance == null || userPositions == null || userPositions.isEmpty()) {
            return;
        }

        // Общая стоимость кросс-активов (начинается со свободного баланса)
        double crossEquity = balance.getFreeBalance();
        double totalCrossMaintenanceMargin = 0.0;
        boolean hasCrossPositions = false;

        for (Position p : userPositions.values()) {
            if (p.getSize() == 0.0 || p.isIsolated()) {
                continue;
            }
            hasCrossPositions = true;

            double currentPrice = oracleService.getPrice(p.getMarketId());
            double upnl = p.calculateUnrealizedPnL(currentPrice);
            // Кросс-эквити включает залог позиции и ее нереализованный PnL
            crossEquity += p.getMargin() + upnl;

            MarketSpecification spec = marginManager.getMarketSpec(p.getMarketId());
            double mmRate = (spec != null) ? spec.getMaintenanceMarginRate() : 0.05;
            totalCrossMaintenanceMargin += p.getSize() * currentPrice * mmRate;
        }

        if (hasCrossPositions && crossEquity < totalCrossMaintenanceMargin) {
            // Ликвидируем ВСЕ кросс-позиции пользователя
            System.out.printf("[LIQUIDATION][CROSS] Пользователь %s подлежит кросс-ликвидации! Эквити: %.2f, Требуемая маржа поддержания: %.2f%n",
                    userId, crossEquity, totalCrossMaintenanceMargin);

            for (Position p : userPositions.values()) {
                if (p.getSize() == 0.0 || p.isIsolated()) {
                    continue;
                }
                
                System.out.printf("  -> Принудительное закрытие кросс-позиции на рынке %s, объем: %.4f%n", p.getMarketId(), p.getSize());
                // Списываем заблокированную маржу позиции из AccountBalance
                balance.unlockMargin(p.getMargin()); // Сначала разблокируем, чтобы вычесть её
                balance.deductLoss(p.getMargin());  // Теряется залог
                
                p.setSize(0.0);
                p.setMargin(0.0);
                p.setEntryPrice(0.0);
            }

            // В кросс-ликвидации свободный баланс также может обнулиться или уйти в минус из-за потерь
            if (balance.getFreeBalance() > 0) {
                balance.deductLoss(balance.getFreeBalance()); // Обнуляем свободный баланс
            }
        }
    }
}
