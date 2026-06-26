package com.example.dex.models;

/**
 * Класс Trade представляет совершенную сделку (мэтч) между покупателем и продавцом.
 */
public class Trade {

    private final String buyerId;
    private final String sellerId;
    private final String marketId;
    private final double price;
    private final double amount;
    private final double buyerLeverage;
    private final boolean buyerIsolated;
    private final double sellerLeverage;
    private final boolean sellerIsolated;
    private final long timestamp;

    public Trade(String buyerId, String sellerId, String marketId, double price, double amount, 
                 double buyerLeverage, boolean buyerIsolated, double sellerLeverage, boolean sellerIsolated, long timestamp) {
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.marketId = marketId;
        this.price = price;
        this.amount = amount;
        this.buyerLeverage = buyerLeverage;
        this.buyerIsolated = buyerIsolated;
        this.sellerLeverage = sellerLeverage;
        this.sellerIsolated = sellerIsolated;
        this.timestamp = timestamp;
    }

    public String getBuyerId() {
        return buyerId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public String getMarketId() {
        return marketId;
    }

    public double getPrice() {
        return price;
    }

    public double getAmount() {
        return amount;
    }

    public double getBuyerLeverage() {
        return buyerLeverage;
    }

    public boolean isBuyerIsolated() {
        return buyerIsolated;
    }

    public double getSellerLeverage() {
        return sellerLeverage;
    }

    public boolean isSellerIsolated() {
        return sellerIsolated;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Trade{" +
                "buyerId='" + buyerId + '\'' +
                ", sellerId='" + sellerId + '\'' +
                ", marketId='" + marketId + '\'' +
                ", price=" + price +
                ", amount=" + amount +
                ", buyerLeverage=" + buyerLeverage +
                ", buyerIsolated=" + buyerIsolated +
                ", sellerLeverage=" + sellerLeverage +
                ", sellerIsolated=" + sellerIsolated +
                ", timestamp=" + timestamp +
                '}';
    }
}
