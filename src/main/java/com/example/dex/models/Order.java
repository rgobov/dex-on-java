package com.example.dex.models;

/**
 * Класс Order представляет собой торговый ордер, отправленный пользователем на DEX.
 */
public class Order {

    private final String orderId;       // Уникальный идентификатор ордера
    private final String userId;        // Идентификатор пользователя (публичный ключ/адрес)
    private final String marketId;      // Идентификатор рынка (например, "BTC-USD")
    private final boolean isBuy;        // Направление: true = Buy (Long), false = Sell (Short)
    private final double price;         // Цена ордера (0.0 для рыночных ордеров)
    private final double amount;        // Начальный объем/количество контрактов
    private double remainingAmount;     // Оставшийся незаполненный объем ордера
    private final boolean isLimit;      // Тип ордера: true = Limit, false = Market
    private final double leverage;      // Кредитное плечо для этого ордера (например, 10.0)
    private final boolean isIsolated;   // Режим маржи: true = Isolated, false = Cross
    private final long timestamp;       // Время создания ордера
    private final String signature;     // Подпись ордера приватным ключом пользователя

    public Order(String orderId, String userId, String marketId, boolean isBuy, 
                 double price, double amount, boolean isLimit, double leverage, boolean isIsolated, long timestamp, String signature) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Объем ордера должен быть положительным");
        }
        if (isLimit && price <= 0) {
            throw new IllegalArgumentException("Цена лимитного ордера должна быть положительной");
        }
        if (leverage <= 0) {
            throw new IllegalArgumentException("Кредитное плечо должно быть положительным");
        }
        this.orderId = orderId;
        this.userId = userId;
        this.marketId = marketId;
        this.isBuy = isBuy;
        this.price = price;
        this.amount = amount;
        this.remainingAmount = amount;
        this.isLimit = isLimit;
        this.leverage = leverage;
        this.isIsolated = isIsolated;
        this.timestamp = timestamp;
        this.signature = signature;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getMarketId() {
        return marketId;
    }

    public boolean isBuy() {
        return isBuy;
    }

    public double getPrice() {
        return price;
    }

    public double getAmount() {
        return amount;
    }

    public double getRemainingAmount() {
        return remainingAmount;
    }

    public void setRemainingAmount(double remainingAmount) {
        if (remainingAmount < 0) {
            this.remainingAmount = 0;
        } else {
            this.remainingAmount = remainingAmount;
        }
    }

    public boolean isLimit() {
        return isLimit;
    }

    public double getLeverage() {
        return leverage;
    }

    public boolean isIsolated() {
        return isIsolated;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSignature() {
        return signature;
    }

    public String getSigningData() {
        return userId + ":" + marketId + ":" + isBuy + ":" + price + ":" + amount + ":" + isLimit + ":" + timestamp;
    }

    @Override
    public String toString() {
        return "Order{" +
                "orderId='" + orderId + '\'' +
                ", userId='" + userId + '\'' +
                ", marketId='" + marketId + '\'' +
                ", isBuy=" + isBuy +
                ", price=" + price +
                ", amount=" + amount +
                ", isLimit=" + isLimit +
                ", timestamp=" + timestamp +
                '}';
    }
}
