package com.example.dex.models;

/**
 * Класс MarketSpecification описывает параметры и спецификацию торгового рынка для бессрочных фьючерсов.
 */
public class MarketSpecification {

    private final String marketId;              // Идентификатор рынка (например, "BTC-USD")
    private final String oracleFeedId;          // Идентификатор оракула для получения цен
    private final double maxLeverage;            // Максимально допустимое кредитное плечо (например, 20x)
    private final double maintenanceMarginRate;  // Ставка поддерживающей маржи (например, 0.05 для 5%)
    private final double makerFeeRate;           // Комиссия мейкера (например, 0.0002 для 0.02%)
    private final double takerFeeRate;           // Комиссия тейкера (например, 0.0005 для 0.05%)
    private double fundingRate;                  // Текущая ставка фандинга (изменяется динамически)

    public MarketSpecification(String marketId, String oracleFeedId, double maxLeverage, 
                               double maintenanceMarginRate, double makerFeeRate, double takerFeeRate) {
        this.marketId = marketId;
        this.oracleFeedId = oracleFeedId;
        this.maxLeverage = maxLeverage;
        this.maintenanceMarginRate = maintenanceMarginRate;
        this.makerFeeRate = makerFeeRate;
        this.takerFeeRate = takerFeeRate;
        this.fundingRate = 0.0;
    }

    public String getMarketId() {
        return marketId;
    }

    public String getOracleFeedId() {
        return oracleFeedId;
    }

    public double getMaxLeverage() {
        return maxLeverage;
    }

    public double getMaintenanceMarginRate() {
        return maintenanceMarginRate;
    }

    public double getMakerFeeRate() {
        return makerFeeRate;
    }

    public double getTakerFeeRate() {
        return takerFeeRate;
    }

    public double getFundingRate() {
        return fundingRate;
    }

    public void setFundingRate(double fundingRate) {
        this.fundingRate = fundingRate;
    }

    @Override
    public String toString() {
        return "MarketSpecification{" +
                "marketId='" + marketId + '\'' +
                ", oracleFeedId='" + oracleFeedId + '\'' +
                ", maxLeverage=" + maxLeverage +
                ", maintenanceMarginRate=" + maintenanceMarginRate +
                ", makerFeeRate=" + makerFeeRate +
                ", takerFeeRate=" + takerFeeRate +
                ", fundingRate=" + fundingRate +
                '}';
    }
}
