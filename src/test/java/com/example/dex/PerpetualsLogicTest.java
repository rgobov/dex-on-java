package com.example.dex;

import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.AccountBalance;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.Order;
import com.example.dex.models.Position;
import com.example.dex.models.Trade;
import com.example.dex.oracle.OracleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PerpetualsLogicTest {

    private OracleService oracleService;
    private MarginManager marginManager;
    private FundingCalculator fundingCalculator;
    private LiquidationEngine liquidationEngine;
    private MarketSpecification marketSpec;
    private OrderBook orderBook;

    private final String marketId = "BTC-USD";
    private final String buyerId = "buyer-user";
    private final String sellerId = "seller-user";

    @BeforeEach
    public void setUp() {
        oracleService = new OracleService();
        marginManager = new MarginManager(oracleService);
        fundingCalculator = new FundingCalculator(marginManager, oracleService);
        liquidationEngine = new LiquidationEngine(marginManager, oracleService);

        // Спецификация рынка: плечо 10x, поддерживающая маржа 5% (0.05), комиссия мейкера/тейкера
        marketSpec = new MarketSpecification(marketId, "feed-btc", 10.0, 0.05, 0.0002, 0.0005);
        marginManager.registerMarket(marketSpec);

        orderBook = new OrderBook(marketId);

        // Регистрируем пользователей с депозитом $10,000
        marginManager.registerUser(buyerId, 10000.0);
        marginManager.registerUser(sellerId, 10000.0);
    }

    @Test
    public void testFullMarginAndTradeFlow() {
        // Устанавливаем цену оракула $60,000
        oracleService.setPrice(marketId, 60000.0);

        // 1. Проверяем блокировку маржи под ордер покупки: 1.0 BTC по цене $60,000 с плечом 10.0 и изолированной маржой
        // Требуемая маржа = (1.0 * 60,000) / 10 = $6,000
        Order buyOrder = new Order("b-1", buyerId, marketId, true, 60000.0, 1.0, true, 10.0, true, System.currentTimeMillis(), "sig");
        boolean buyMarginOk = marginManager.checkAndLockMarginForOrder(buyOrder);
        assertTrue(buyMarginOk);

        AccountBalance buyerBalance = marginManager.getBalance(buyerId);
        assertEquals(4000.0, buyerBalance.getFreeBalance(), "Свободный баланс должен уменьшиться на маржу ($6000)");
        assertEquals(6000.0, buyerBalance.getLockedMargin(), "Заблокированная маржа должна быть $6000");

        // Проверяем блокировку маржи продавца
        Order sellOrder = new Order("s-1", sellerId, marketId, false, 60000.0, 1.0, true, 10.0, true, System.currentTimeMillis(), "sig");
        boolean sellMarginOk = marginManager.checkAndLockMarginForOrder(sellOrder);
        assertTrue(sellMarginOk);

        // 2. Имитируем сделку
        Trade trade = new Trade(buyerId, sellerId, marketId, 60000.0, 1.0, 10.0, true, 10.0, true, System.currentTimeMillis());
        marginManager.processTrade(trade, buyOrder.getLeverage(), buyOrder.isIsolated(), sellOrder.getLeverage(), sellOrder.isIsolated());

        // Проверяем создание позиций
        Position buyerPos = marginManager.getPosition(buyerId, marketId);
        assertNotNull(buyerPos);
        assertTrue(buyerPos.isLong());
        assertEquals(1.0, buyerPos.getSize());
        assertEquals(60000.0, buyerPos.getEntryPrice());
        assertEquals(6000.0, buyerPos.getMargin());
        assertTrue(buyerPos.isIsolated());

        Position sellerPos = marginManager.getPosition(sellerId, marketId);
        assertNotNull(sellerPos);
        assertFalse(sellerPos.isLong());
        assertEquals(1.0, sellerPos.getSize());
        assertEquals(60000.0, sellerPos.getEntryPrice());
        assertEquals(6000.0, sellerPos.getMargin());
        assertTrue(sellerPos.isIsolated());

        // Проверяем списание комиссий (Taker fee = 0.05% от объема сделки $60,000 = $30)
        // Изначальный баланс 10,000 - 6,000 (заблокировано) - 30 (комиссия) = 3,970 свободного баланса
        assertEquals(3970.0, buyerBalance.getFreeBalance());
        assertEquals(3970.0, marginManager.getBalance(sellerId).getFreeBalance());
    }

    @Test
    public void testFundingAccrual() {
        testFullMarginAndTradeFlow(); // Сначала открываем позиции

        // Ставим ордера в стакан, чтобы создать отклонение цены от оракула
        // Мэтчим покупку по $60,500 и продажу по $60,700 -> Mid Price = $60,600
        // При цене оракула $60,000, фандинг должен быть положительным (лонги платят шортам)
        Order buy = new Order("b-2", "other-buyer", marketId, true, 60500.0, 0.1, true, 10.0, true, System.currentTimeMillis(), "sig");
        Order sell = new Order("s-2", "other-seller", marketId, false, 60700.0, 0.1, true, 10.0, true, System.currentTimeMillis(), "sig");
        orderBook.processOrder(buy);
        orderBook.processOrder(sell);

        double rate = fundingCalculator.calculateFundingRate(orderBook, marketSpec);
        assertTrue(rate > 0.0, "Ставка фандинга должна быть положительной (Mark Price > Index Price)");

        double buyerFreeBefore = marginManager.getBalance(buyerId).getFreeBalance();
        double sellerFreeBefore = marginManager.getBalance(sellerId).getFreeBalance();

        // Применяем фандинг к нашим двум основным пользователям
        fundingCalculator.applyFunding(marketId, orderBook, marketSpec, List.of(buyerId, sellerId));

        double buyerFreeAfter = marginManager.getBalance(buyerId).getFreeBalance();
        double sellerFreeAfter = marginManager.getBalance(sellerId).getFreeBalance();

        assertTrue(buyerFreeAfter < buyerFreeBefore, "Баланс покупателя (Long) должен уменьшиться");
        assertTrue(sellerFreeAfter > sellerFreeBefore, "Баланс продавца (Short) должен увеличиться");
    }

    @Test
    public void testLiquidation() {
        testFullMarginAndTradeFlow(); // Сначала открываем позиции

        // Проверяем цену ликвидации покупателя (Long)
        // LiqPrice = (60,000 - (6,000 / 1.0)) / (1.0 - 0.05) = 54,000 / 0.95 = ~56,842.10
        Position buyerPos = marginManager.getPosition(buyerId, marketId);
        double liqPrice = buyerPos.calculateLiquidationPrice(marketSpec.getMaintenanceMarginRate());
        assertEquals(56842.105, liqPrice, 0.01);

        // Опускаем цену оракула до $55,000 (ниже цены ликвидации)
        oracleService.setPrice(marketId, 55000.0);

        // Запускаем движок ликвидаций
        liquidationEngine.checkLiquidations(marketId, marketSpec, List.of(buyerId, sellerId));

        // Позиция покупателя должна быть обнулена
        assertEquals(0.0, buyerPos.getSize());
        assertEquals(0.0, buyerPos.getMargin());

        // Проверяем баланс покупателя (его залог $6,000 списан в убыток)
        AccountBalance buyerBalance = marginManager.getBalance(buyerId);
        assertEquals(0.0, buyerBalance.getLockedMargin(), "Заблокированная маржа должна обнулиться");
        // Итоговый баланс = 10,000 - 6,000 (убыток/ликвидация) - 30 (комиссия) = 3,970
        assertEquals(3970.0, buyerBalance.getTotalEquity());
    }

    @Test
    public void testCrossMarginLiquidation() {
        String crossBuyer = "cross-buyer";
        String crossSeller = "cross-seller";

        marginManager.registerUser(crossBuyer, 10000.0);
        marginManager.registerUser(crossSeller, 10000.0);

        oracleService.setPrice(marketId, 60000.0);

        // 1. Открываем Cross-позицию (isIsolated = false)
        Order buyOrder = new Order("cb-1", crossBuyer, marketId, true, 60000.0, 1.0, true, 10.0, false, System.currentTimeMillis(), "sig");
        Order sellOrder = new Order("cs-1", crossSeller, marketId, false, 60000.0, 1.0, true, 10.0, false, System.currentTimeMillis(), "sig");

        assertTrue(marginManager.checkAndLockMarginForOrder(buyOrder));
        assertTrue(marginManager.checkAndLockMarginForOrder(sellOrder));

        Trade trade = new Trade(crossBuyer, crossSeller, marketId, 60000.0, 1.0, 10.0, false, 10.0, false, System.currentTimeMillis());
        marginManager.processTrade(trade, 10.0, false, 10.0, false);

        Position buyerPos = marginManager.getPosition(crossBuyer, marketId);
        assertNotNull(buyerPos);
        assertFalse(buyerPos.isIsolated(), "Позиция должна быть в Cross-режиме");

        // 2. Двигаем цену оракула вниз до $55,000
        // Для изолированной маржи это была бы цена ликвидации (~$56,842).
        // Но для Cross-маржи у пользователя еще есть свободный баланс ($3,970), покрывающий нереализованный убыток в -$5,000.
        oracleService.setPrice(marketId, 55000.0);
        
        // Запускаем движок ликвидаций
        liquidationEngine.checkLiquidations(marketId, marketSpec, List.of(crossBuyer));

        // Покупатель НЕ должен быть ликвидирован!
        assertEquals(1.0, buyerPos.getSize(), "Кросс-позиция не должна быть ликвидирована на $55,000 благодаря свободному балансу");

        // 3. Двигаем цену ниже критической границы, например до $52,000
        // Убыток = -$8,000. Общий капитал = 3,970 + 6,000 - 8,000 = $1,970.
        // Требуемая поддерживающая маржа = 1.0 * 52,000 * 0.05 = $2,600.
        // Эквити ($1,970) < Поддерживающая маржа ($2,600) -> Должна сработать кросс-ликвидация!
        oracleService.setPrice(marketId, 52000.0);

        liquidationEngine.checkLiquidations(marketId, marketSpec, List.of(crossBuyer));

        // Теперь позиция ДОЛЖНА быть ликвидирована
        assertEquals(0.0, buyerPos.getSize(), "Кросс-позиция должна быть ликвидирована на $52,000");
        assertEquals(0.0, marginManager.getBalance(crossBuyer).getFreeBalance(), "Свободный баланс кросс-пользователя должен быть обнулен");
    }

    @Test
    public void testMarginModeSwitchingAndAdjustment() {
        String testUser = "adjust-user";
        marginManager.registerUser(testUser, 10000.0);
        oracleService.setPrice(marketId, 60000.0);

        // 1. Открываем Cross-позицию (isIsolated = false)
        Order buyOrder = new Order("ab-1", testUser, marketId, true, 60000.0, 1.0, true, 10.0, false, System.currentTimeMillis(), "sig");
        assertTrue(marginManager.checkAndLockMarginForOrder(buyOrder));

        Order sellOrder = new Order("as-1", sellerId, marketId, false, 60000.0, 1.0, true, 10.0, false, System.currentTimeMillis(), "sig");
        assertTrue(marginManager.checkAndLockMarginForOrder(sellOrder));

        Trade trade = new Trade(testUser, sellerId, marketId, 60000.0, 1.0, 10.0, false, 10.0, false, System.currentTimeMillis());
        marginManager.processTrade(trade, 10.0, false, 10.0, false);

        Position pos = marginManager.getPosition(testUser, marketId);
        assertNotNull(pos);
        assertFalse(pos.isIsolated(), "Изначально позиция в Cross-режиме");

        // 2. Переключаем на Isolated
        boolean switchOk = marginManager.switchMarginMode(testUser, marketId, true);
        assertTrue(switchOk, "Переключение на Isolated должно пройти успешно");
        assertTrue(pos.isIsolated(), "Позиция должна стать Isolated");

        // Проверяем цену ликвидации при марже $6,000
        // LiqPrice = (60,000 - 6,000) / 0.95 = 56,842.10
        assertEquals(56842.105, pos.calculateLiquidationPrice(marketSpec.getMaintenanceMarginRate()), 0.01);

        // 3. Вручную добавляем $2,000 маржи
        boolean addMarginOk = marginManager.addMarginToIsolatedPosition(testUser, marketId, 2000.0);
        assertTrue(addMarginOk, "Добавление маржи должно пройти успешно");
        assertEquals(8000.0, pos.getMargin(), "Маржа позиции должна увеличиться до $8,000");

        // Проверяем, что цена ликвидации снизилась (стала безопаснее)
        // LiqPrice = (60,000 - 8,000) / 0.95 = 52,000 / 0.95 = 54,736.84
        assertEquals(54736.842, pos.calculateLiquidationPrice(marketSpec.getMaintenanceMarginRate()), 0.01);

        // 4. Вручную выводим $1,000 маржи из позиции
        boolean removeMarginOk = marginManager.removeMarginFromIsolatedPosition(testUser, marketId, 1000.0);
        assertTrue(removeMarginOk, "Вывод маржи должен пройти успешно");
        assertEquals(7000.0, pos.getMargin(), "Маржа позиции должна уменьшиться до $7,000");

        // 5. Попытка вывести слишком много маржи (чтобы осталось меньше начальной маржи $6,000)
        // Начальная маржа: 1.0 BTC * 60,000 / 10 = $6,000. Оставшийся залог должен быть >= $6,000.
        // Пытаемся вывести еще $1,500 (останется $5,500 < $6,000)
        boolean removeExcessMarginOk = marginManager.removeMarginFromIsolatedPosition(testUser, marketId, 1500.0);
        assertFalse(removeExcessMarginOk, "Нельзя вывести маржу ниже начального маржинального требования");
        assertEquals(7000.0, pos.getMargin(), "Маржа позиции должна остаться прежней ($7,000)");

        // 6. Переключаем обратно на Cross
        boolean switchBackOk = marginManager.switchMarginMode(testUser, marketId, false);
        assertTrue(switchBackOk);
        assertFalse(pos.isIsolated(), "Позиция должна вернуться в Cross-режим");
    }
}
