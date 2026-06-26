package com.example.dex.models;

/**
 * Класс Position представляет открытую позицию трейдера по конкретному рынку.
 */
public class Position {

    private final String userId;        // Владелец позиции
    private final String marketId;      // Рынок (например, "BTC-USD")
    private boolean isLong;             // Направление: true = Long, false = Short
    private double size;                // Размер позиции (количество контрактов)
    private double entryPrice;          // Средняя цена входа
    private double margin;              // Выделенный залог (коллатерал)
    private double leverage;            // Кредитное плечо (например, 10.0)
    private boolean isIsolated;         // Режим маржи: true = Isolated, false = Cross

    public Position(String userId, String marketId, boolean isLong, double size, double entryPrice, double margin, double leverage, boolean isIsolated) {
        this.userId = userId;
        this.marketId = marketId;
        this.isLong = isLong;
        this.size = size;
        this.entryPrice = entryPrice;
        this.margin = margin;
        this.leverage = leverage;
        this.isIsolated = isIsolated;
    }

    public String getUserId() {
        return userId;
    }

    public String getMarketId() {
        return marketId;
    }

    public boolean isLong() {
        return isLong;
    }

    public double getSize() {
        return size;
    }

    public double getEntryPrice() {
        return entryPrice;
    }

    public double getMargin() {
        return margin;
    }

    public double getLeverage() {
        return leverage;
    }

    public boolean isIsolated() {
        return isIsolated;
    }

    public void setIsolated(boolean isIsolated) {
        this.isIsolated = isIsolated;
    }

    public void setSize(double size) {
        this.size = size;
    }

    public void setEntryPrice(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public void setMargin(double margin) {
        this.margin = margin;
    }

    public void setLong(boolean isLong) {
        this.isLong = isLong;
    }

    /**
     * Рассчитывает нереализованный PnL (прибыль/убыток) позиции на основе текущей рыночной цены.
     */
    public double calculateUnrealizedPnL(double currentPrice) {
        if (isLong) {
            return (currentPrice - entryPrice) * size;
        } else {
            return (entryPrice - currentPrice) * size;
        }
    }

    /**
     * Рассчитывает цену ликвидации позиции (для Isolated режима или справочно для Cross с учетом переданного свободного баланса).
     */
    public double calculateLiquidationPrice(double maintenanceMarginRate) {
        return calculateLiquidationPrice(maintenanceMarginRate, 0.0);
    }

    public double calculateLiquidationPrice(double maintenanceMarginRate, double freeBalance) {
        if (size == 0) {
            return 0.0;
        }
        double totalCollateral = isIsolated ? margin : (margin + freeBalance);
        if (isLong) {
            return (entryPrice - (totalCollateral / size)) / (1.0 - maintenanceMarginRate);
        } else {
            return (entryPrice + (totalCollateral / size)) / (1.0 + maintenanceMarginRate);
        }
    }

    @Override
    public String toString() {
        return "Position{" +
                "userId='" + userId + '\'' +
                ", marketId='" + marketId + '\'' +
                ", isLong=" + isLong +
                ", size=" + size +
                ", entryPrice=" + entryPrice +
                ", margin=" + margin +
                ", leverage=" + leverage +
                '}';
    }
}
