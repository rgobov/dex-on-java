package com.example.dex.oracle;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис оракула для предоставления текущих индексных цен активов из реального мира.
 * Потокобезопасный.
 */
public class OracleService {

    private final ConcurrentHashMap<String, Double> prices = new ConcurrentHashMap<>();

    /**
     * Обновляет цену для конкретного актива/рынка.
     *
     * @param marketId Идентификатор рынка (например, "BTC-USD")
     * @param price Текущая цена
     */
    public void setPrice(String marketId, double price) {
        if (price <= 0) {
            throw new IllegalArgumentException("Цена оракула должна быть положительной");
        }
        prices.put(marketId, price);
    }

    /**
     * Возвращает цену для конкретного актива/рынка.
     *
     * @param marketId Идентификатор рынка (например, "BTC-USD")
     * @return Текущая индексная цена
     */
    public double getPrice(String marketId) {
        Double price = prices.get(marketId);
        if (price == null) {
            throw new IllegalArgumentException("Цена для рынка " + marketId + " не найдена в оракуле");
        }
        return price;
    }
}
