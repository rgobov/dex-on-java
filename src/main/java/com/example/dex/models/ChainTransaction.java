package com.example.dex.models;

/**
 * Класс ChainTransaction представляет собой единую детерминированную транзакцию/команду,
 * обрабатываемую L2-движком (конечным автоматом) в общей последовательности.
 */
public class ChainTransaction {
    public enum TxType {
        DEPOSIT,
        WITHDRAW,
        PLACE_ORDER,
        CANCEL_ORDER,
        UPDATE_ORACLE
    }

    private final TxType type;
    private final String userId;
    private final String marketId;
    private final double amount;
    private final double price;
    private final double leverage;
    private final boolean isIsolated;
    private final String orderId;
    private final boolean isBuy;
    private final boolean isLimit;
    private final String signature;
    private final long timestamp;

    private ChainTransaction(Builder builder) {
        this.type = builder.type;
        this.userId = builder.userId;
        this.marketId = builder.marketId;
        this.amount = builder.amount;
        this.price = builder.price;
        this.leverage = builder.leverage;
        this.isIsolated = builder.isIsolated;
        this.orderId = builder.orderId;
        this.isBuy = builder.isBuy;
        this.isLimit = builder.isLimit;
        this.signature = builder.signature;
        this.timestamp = builder.timestamp;
    }

    public TxType getType() { return type; }
    public String getUserId() { return userId; }
    public String getMarketId() { return marketId; }
    public double getAmount() { return amount; }
    public double getPrice() { return price; }
    public double getLeverage() { return leverage; }
    public boolean isIsolated() { return isIsolated; }
    public String getOrderId() { return orderId; }
    public boolean isBuy() { return isBuy; }
    public boolean isLimit() { return isLimit; }
    public String getSignature() { return signature; }
    public long getTimestamp() { return timestamp; }

    public Order toOrder() {
        if (type != TxType.PLACE_ORDER) {
            throw new IllegalStateException("Транзакция не является ордером");
        }
        return new Order(orderId, userId, marketId, isBuy, price, amount, isLimit, leverage, isIsolated, timestamp, signature);
    }

    public static class Builder {
        private final TxType type;
        private String userId;
        private String marketId;
        private double amount;
        private double price;
        private double leverage = 1.0;
        private boolean isIsolated;
        private String orderId;
        private boolean isBuy;
        private boolean isLimit = true;
        private String signature;
        private long timestamp = System.currentTimeMillis();

        public Builder(TxType type) {
            this.type = type;
        }

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder marketId(String marketId) { this.marketId = marketId; return this; }
        public Builder amount(double amount) { this.amount = amount; return this; }
        public Builder price(double price) { this.price = price; return this; }
        public Builder leverage(double leverage) { this.leverage = leverage; return this; }
        public Builder isIsolated(boolean isIsolated) { this.isIsolated = isIsolated; return this; }
        public Builder orderId(String orderId) { this.orderId = orderId; return this; }
        public Builder isBuy(boolean isBuy) { this.isBuy = isBuy; return this; }
        public Builder isLimit(boolean isLimit) { this.isLimit = isLimit; return this; }
        public Builder signature(String signature) { this.signature = signature; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

        public ChainTransaction build() {
            return new ChainTransaction(this);
        }
    }

    @Override
    public String toString() {
        return "ChainTransaction{" +
                "type=" + type +
                ", userId='" + userId + '\'' +
                ", marketId='" + marketId + '\'' +
                ", amount=" + amount +
                ", price=" + price +
                ", orderId='" + orderId + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
