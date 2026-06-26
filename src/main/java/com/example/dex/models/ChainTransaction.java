package com.example.dex.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    @JsonCreator
    private ChainTransaction(
            @JsonProperty("type") TxType type,
            @JsonProperty("userId") String userId,
            @JsonProperty("marketId") String marketId,
            @JsonProperty("amount") double amount,
            @JsonProperty("price") double price,
            @JsonProperty("leverage") double leverage,
            @JsonProperty("isIsolated") boolean isIsolated,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("isBuy") boolean isBuy,
            @JsonProperty("isLimit") boolean isLimit,
            @JsonProperty("signature") String signature,
            @JsonProperty("timestamp") long timestamp) {
        this.type = type;
        this.userId = userId;
        this.marketId = marketId;
        this.amount = amount;
        this.price = price;
        this.leverage = leverage;
        this.isIsolated = isIsolated;
        this.orderId = orderId;
        this.isBuy = isBuy;
        this.isLimit = isLimit;
        this.signature = signature;
        this.timestamp = timestamp;
    }

    private ChainTransaction(Builder builder) {
        this(builder.type, builder.userId, builder.marketId, builder.amount, builder.price,
                builder.leverage, builder.isIsolated, builder.orderId, builder.isBuy,
                builder.isLimit, builder.signature, builder.timestamp);
    }

    @JsonProperty("type") public TxType getType() { return type; }
    @JsonProperty("userId") public String getUserId() { return userId; }
    @JsonProperty("marketId") public String getMarketId() { return marketId; }
    @JsonProperty("amount") public double getAmount() { return amount; }
    @JsonProperty("price") public double getPrice() { return price; }
    @JsonProperty("leverage") public double getLeverage() { return leverage; }
    @JsonProperty("isIsolated") public boolean isIsolated() { return isIsolated; }
    @JsonProperty("orderId") public String getOrderId() { return orderId; }
    @JsonProperty("isBuy") public boolean isBuy() { return isBuy; }
    @JsonProperty("isLimit") public boolean isLimit() { return isLimit; }
    @JsonProperty("signature") public String getSignature() { return signature; }
    @JsonProperty("timestamp") public long getTimestamp() { return timestamp; }

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
