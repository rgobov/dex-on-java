package com.example.dex;

import com.example.dex.matching.OrderBook;
import com.example.dex.models.Order;
import com.example.dex.models.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OrderBookTest {

    private OrderBook orderBook;
    private final String marketId = "BTC-USD";

    @BeforeEach
    public void setUp() {
        orderBook = new OrderBook(marketId);
    }

    @Test
    public void testLimitOrderMatchingAndPartialFill() {
        // Создаем лимитный ордер на продажу: 2.0 BTC по $60,000 с плечом 10.0 и изолированной маржой
        Order sellOrder = new Order("ord-1", "user-seller", marketId, false, 60000.0, 2.0, true, 10.0, true, System.currentTimeMillis(), "sig1");
        
        // Помещаем его в стакан
        List<Trade> trades1 = orderBook.processOrder(sellOrder);
        assertTrue(trades1.isEmpty(), "Сделок быть не должно, так как стакан пуст");
        assertEquals(1, orderBook.getAsks().size());
        assertEquals(2.0, orderBook.getAsks().get(60000.0).peek().getRemainingAmount());

        // Создаем лимитный ордер на покупку: 1.5 BTC по $60,100 с плечом 10.0 и изолированной маржой
        Order buyOrder = new Order("ord-2", "user-buyer", marketId, true, 60100.0, 1.5, true, 10.0, true, System.currentTimeMillis(), "sig2");
        
        List<Trade> trades2 = orderBook.processOrder(buyOrder);
        assertEquals(1, trades2.size(), "Должна произойти одна сделка");

        Trade trade = trades2.get(0);
        assertEquals("user-buyer", trade.getBuyerId());
        assertEquals("user-seller", trade.getSellerId());
        assertEquals(60000.0, trade.getPrice(), "Цена сделки должна быть равна цене мейкера ($60,000)");
        assertEquals(1.5, trade.getAmount(), "Объем сделки должен быть 1.5 BTC");

        // Проверяем остатки в стакане
        assertEquals(0.0, buyOrder.getRemainingAmount(), "Ордер на покупку должен быть заполнен полностью");
        assertEquals(0.5, sellOrder.getRemainingAmount(), "В ордере на продажу должно остаться 0.5 BTC");
        
        // В стакане bids пусто, а в asks остался лимитный ордер на 0.5 BTC
        assertTrue(orderBook.getBids().isEmpty());
        assertEquals(1, orderBook.getAsks().get(60000.0).size());
        assertEquals(0.5, orderBook.getAsks().get(60000.0).peek().getRemainingAmount());
    }

    @Test
    public void testMarketOrderMatching() {
        // Размещаем два лимитных ордера на продажу на разных ценовых уровнях:
        // 1.0 BTC по $60,000
        // 2.0 BTC по $61,000
        Order sell1 = new Order("s-1", "seller-1", marketId, false, 60000.0, 1.0, true, 10.0, true, System.currentTimeMillis(), "sig");
        Order sell2 = new Order("s-2", "seller-2", marketId, false, 61000.0, 2.0, true, 10.0, true, System.currentTimeMillis(), "sig");
        
        orderBook.processOrder(sell1);
        orderBook.processOrder(sell2);

        // Отправляем рыночный ордер на покупку 2.5 BTC
        Order marketBuy = new Order("b-1", "buyer-1", marketId, true, 0.0, 2.5, false, 10.0, true, System.currentTimeMillis(), "sig");
        
        List<Trade> trades = orderBook.processOrder(marketBuy);
        assertEquals(2, trades.size(), "Должно произойти 2 сделки (поглощение уровней)");

        // Первая сделка: 1.0 BTC по $60,000 у seller-1
        Trade t1 = trades.get(0);
        assertEquals("seller-1", t1.getSellerId());
        assertEquals(60000.0, t1.getPrice());
        assertEquals(1.0, t1.getAmount());

        // Вторая сделка: 1.5 BTC по $61,000 у seller-2
        Trade t2 = trades.get(1);
        assertEquals("seller-2", t2.getSellerId());
        assertEquals(61000.0, t2.getPrice());
        assertEquals(1.5, t2.getAmount());

        // Проверяем стакан
        assertTrue(orderBook.getAsks().containsKey(61000.0), "Уровень $61,000 должен остаться в стакане");
        assertFalse(orderBook.getAsks().containsKey(60000.0), "Уровень $60,000 должен быть полностью выкуплен");
        assertEquals(0.5, orderBook.getAsks().get(61000.0).peek().getRemainingAmount(), "У seller-2 должно остаться 0.5 BTC");
    }
}
