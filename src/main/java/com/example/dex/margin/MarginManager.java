package com.example.dex.margin;

import com.example.dex.models.AccountBalance;
import com.example.dex.models.MarketSpecification;
import com.example.dex.models.Order;
import com.example.dex.models.Position;
import com.example.dex.models.Trade;
import com.example.dex.oracle.OracleService;

import java.util.HashMap;
import java.util.Map;

/**
 * Класс MarginManager управляет балансами пользователей, открытыми позициями,
 * проверкой маржинальных требований перед сведением ордеров и клирингом сделок.
 */
public class MarginManager {

    private final Map<String, AccountBalance> balances = new HashMap<>();
    private final Map<String, Map<String, Position>> positions = new HashMap<>();
    private final Map<String, MarketSpecification> markets = new HashMap<>();
    private final OracleService oracleService;

    public MarginManager(OracleService oracleService) {
        this.oracleService = oracleService;
    }

    /**
     * Регистрирует нового пользователя на бирже с начальным депозитом.
     */
    public void registerUser(String userId, double initialDeposit) {
        balances.put(userId, new AccountBalance(userId, initialDeposit));
        positions.put(userId, new HashMap<>());
    }

    /**
     * Регистрирует спецификацию рынка.
     */
    public void registerMarket(MarketSpecification spec) {
        markets.put(spec.getMarketId(), spec);
    }

    public AccountBalance getBalance(String userId) {
        return balances.get(userId);
    }

    public Position getPosition(String userId, String marketId) {
        Map<String, Position> userPositions = positions.get(userId);
        if (userPositions != null) {
            return userPositions.get(marketId);
        }
        return null;
    }

    public double getPositionSize(String userId, String marketId) {
        Position pos = getPosition(userId, marketId);
        if (pos == null) {
            return 0.0;
        }
        return pos.isLong() ? pos.getSize() : -pos.getSize();
    }

    /**
     * Проверяет и блокирует маржу на балансе пользователя перед отправкой ордера в стакан.
     * Если это ордер на сокращение текущей позиции (например, продажа при открытом Long),
     * маржа не блокируется (или блокируется только на избыточный объем).
     *
     * @param order Входящий ордер
     * @return true, если маржа успешно заблокирована или не требуется, иначе false
     */
    public boolean checkAndLockMarginForOrder(Order order) {
        AccountBalance balance = balances.get(order.getUserId());
        if (balance == null) {
            return false;
        }

        double currentSize = getPositionSize(order.getUserId(), order.getMarketId());
        boolean isReducing = (order.isBuy() && currentSize < 0.0) || (!order.isBuy() && currentSize > 0.0);

        double marginRequired = 0.0;
        if (isReducing) {
            double absoluteCurrentSize = Math.abs(currentSize);
            if (order.getAmount() > absoluteCurrentSize) {
                // Ордер закрывает текущую позицию и открывает противоположную на избыточный объем
                double excessAmount = order.getAmount() - absoluteCurrentSize;
                double price = order.isLimit() ? order.getPrice() : oracleService.getPrice(order.getMarketId());
                marginRequired = (excessAmount * price) / order.getLeverage();
            }
            // Если объем ордера меньше или равен текущей позиции, маржа не требуется (позиция уменьшается)
        } else {
            // Ордер открывает новую или увеличивает существующую позицию
            double price = order.isLimit() ? order.getPrice() : oracleService.getPrice(order.getMarketId());
            marginRequired = (order.getAmount() * price) / order.getLeverage();
        }

        if (marginRequired > 0.0) {
            return balance.lockMargin(marginRequired);
        }
        return true;
    }

    /**
     * Проводит клиринг совершенной сделки: обновляет позиции покупателя и продавца,
     * начисляет прибыль/убыток при закрытии, списывает торговые комиссии.
     *
     * @param trade Сделка
     * @param buyerLeverage Плечо покупателя
     * @param buyerIsolated Режим маржи покупателя
     * @param sellerLeverage Плечо продавца
     * @param sellerIsolated Режим маржи продавца
     */
    public void processTrade(Trade trade, double buyerLeverage, boolean buyerIsolated, double sellerLeverage, boolean sellerIsolated) {
        String buyerId = trade.getBuyerId();
        String sellerId = trade.getSellerId();
        String marketId = trade.getMarketId();
        double price = trade.getPrice();
        double amount = trade.getAmount();
        double tradeValue = amount * price;

        MarketSpecification marketSpec = markets.get(marketId);
        if (marketSpec == null) {
            throw new IllegalArgumentException("Рынок не зарегистрирован: " + marketId);
        }

        AccountBalance buyerBal = balances.get(buyerId);
        AccountBalance sellerBal = balances.get(sellerId);

        if (buyerBal == null || sellerBal == null) {
            throw new IllegalArgumentException("Баланс одного из участников сделки не найден");
        }

        // 1. Списание комиссий (для простоты списываем Taker Fee с обоих)
        double buyerFee = tradeValue * marketSpec.getTakerFeeRate();
        double sellerFee = tradeValue * marketSpec.getTakerFeeRate();
        if (buyerFee > 0.0) {
            buyerBal.withdraw(buyerFee);
        }
        if (sellerFee > 0.0) {
            sellerBal.withdraw(sellerFee);
        }

        // 2. Обновление позиции покупателя (Long)
        updatePositionAfterTrade(buyerId, marketId, true, amount, price, buyerLeverage, buyerIsolated, buyerBal);

        // 3. Обновление позиции продавца (Short)
        updatePositionAfterTrade(sellerId, marketId, false, amount, price, sellerLeverage, sellerIsolated, sellerBal);
    }

    private void updatePositionAfterTrade(String userId, String marketId, boolean tradeIsBuy, 
                                          double tradeAmount, double tradePrice, double leverage, boolean isIsolated, AccountBalance balance) {
        Map<String, Position> userPositions = positions.get(userId);
        Position pos = userPositions.get(marketId);

        if (pos == null || pos.getSize() == 0.0) {
            // Открытие новой позиции с нуля
            double requiredMargin = (tradeAmount * tradePrice) / leverage;
            // Маржа уже заблокирована методом checkAndLockMarginForOrder.
            // Создаем новый объект позиции.
            Position newPos = new Position(userId, marketId, tradeIsBuy, tradeAmount, tradePrice, requiredMargin, leverage, isIsolated);
            userPositions.put(marketId, newPos);
            return;
        }

        // Позиция уже существует
        if (pos.isLong() == tradeIsBuy) {
            // Направление совпадает: Увеличение существующей позиции (Доливка)
            double currentVal = pos.getSize() * pos.getEntryPrice();
            double newVal = tradeAmount * tradePrice;
            double newSize = pos.getSize() + tradeAmount;
            double newEntryPrice = (currentVal + newVal) / newSize;
            
            pos.setEntryPrice(newEntryPrice);
            pos.setSize(newSize);
            
            double additionalMargin = newVal / leverage;
            pos.setMargin(pos.getMargin() + additionalMargin);
        } else {
            // Направление противоположное: Сокращение или разворот позиции
            if (pos.getSize() > tradeAmount) {
                // Частичное закрытие позиции
                double pnl = calculateTradePnL(pos.isLong(), pos.getEntryPrice(), tradePrice, tradeAmount);
                
                // Пропорционально разблокируем маржу
                double releasedMargin = (tradeAmount / pos.getSize()) * pos.getMargin();
                pos.setMargin(pos.getMargin() - releasedMargin);
                balance.unlockMargin(releasedMargin);
                
                pos.setSize(pos.getSize() - tradeAmount);
                
                // Начисляем прибыль или списываем убыток
                if (pnl >= 0.0) {
                    balance.addProfit(pnl);
                } else {
                    balance.deductLoss(Math.abs(pnl));
                }
            } else {
                // Полное закрытие текущей позиции + возможный разворот в противоположную сторону
                double excessAmount = tradeAmount - pos.getSize();
                
                // Фиксируем PnL по всей старой позиции
                double pnl = calculateTradePnL(pos.isLong(), pos.getEntryPrice(), tradePrice, pos.getSize());
                
                // Полностью высвобождаем маржу старой позиции
                double releasedMargin = pos.getMargin();
                balance.unlockMargin(releasedMargin);
                pos.setSize(0.0);
                pos.setMargin(0.0);

                if (pnl >= 0.0) {
                    balance.addProfit(pnl);
                } else {
                    balance.deductLoss(Math.abs(pnl));
                }

                if (excessAmount > 0.0) {
                    // Разворот: Открываем противоположную позицию на избыточный объем
                    double newMargin = (excessAmount * tradePrice) / leverage;
                    pos.setLong(tradeIsBuy);
                    pos.setSize(excessAmount);
                    pos.setEntryPrice(tradePrice);
                    pos.setMargin(newMargin);
                    pos.setIsolated(isIsolated);
                }
            }
        }
    }

    private double calculateTradePnL(boolean isLong, double entryPrice, double exitPrice, double size) {
        if (isLong) {
            return (exitPrice - entryPrice) * size;
        } else {
            return (entryPrice - exitPrice) * size;
        }
    }

    public Map<String, Position> getUserPositions(String userId) {
        return positions.get(userId);
    }

    public MarketSpecification getMarketSpec(String marketId) {
        return markets.get(marketId);
    }

    /**
     * Переключает режим маржи для открытой позиции.
     */
    public boolean switchMarginMode(String userId, String marketId, boolean toIsolated) {
        Position pos = getPosition(userId, marketId);
        if (pos == null || pos.getSize() == 0.0) {
            // Если позиции нет, то новый режим применится при открытии следующей
            return true;
        }

        if (pos.isIsolated() == toIsolated) {
            return true;
        }

        AccountBalance balance = getBalance(userId);
        if (balance == null) {
            return false;
        }

        if (toIsolated) {
            // Переключаем с Cross на Isolated
            // Требуется, чтобы свободный баланс не был отрицательным (иначе позиция сразу под угрозой ликвидации)
            if (balance.getFreeBalance() < 0.0) {
                System.out.println("[REJECT] Невозможно переключить в Isolated: свободный баланс отрицательный");
                return false;
            }
            pos.setIsolated(true);
            System.out.printf("[MARGIN_MODE] Пользователь %s переключил позицию по %s в режим ISOLATED%n", userId, marketId);
            return true;
        } else {
            // Переключаем с Isolated на Cross
            pos.setIsolated(false);
            System.out.printf("[MARGIN_MODE] Пользователь %s переключил позицию по %s в режим CROSS%n", userId, marketId);
            return true;
        }
    }

    /**
     * Позволяет вручную добавить обеспечение (маржу) в изолированную позицию для снижения риска ликвидации.
     */
    public boolean addMarginToIsolatedPosition(String userId, String marketId, double amount) {
        Position pos = getPosition(userId, marketId);
        if (pos == null || pos.getSize() == 0.0 || !pos.isIsolated()) {
            System.out.println("[REJECT] Позиция не найдена или не находится в режиме ISOLATED");
            return false;
        }

        AccountBalance balance = getBalance(userId);
        if (balance == null || amount <= 0.0) {
            return false;
        }

        // Пытаемся заблокировать средства на балансе
        if (balance.lockMargin(amount)) {
            pos.setMargin(pos.getMargin() + amount);
            System.out.printf("[MARGIN_ADJUST] Добавлено %.2f маржи к изолированной позиции пользователя %s по %s. Новый залог: %.2f%n",
                    amount, userId, marketId, pos.getMargin());
            return true;
        } else {
            System.out.println("[REJECT] Недостаточно свободного баланса для добавления маржи");
            return false;
        }
    }

    /**
     * Позволяет вывести избыточное обеспечение (маржу) из изолированной позиции обратно на свободный баланс.
     */
    public boolean removeMarginFromIsolatedPosition(String userId, String marketId, double amount) {
        Position pos = getPosition(userId, marketId);
        if (pos == null || pos.getSize() == 0.0 || !pos.isIsolated()) {
            System.out.println("[REJECT] Позиция не найдена или не находится в режиме ISOLATED");
            return false;
        }

        AccountBalance balance = getBalance(userId);
        if (balance == null || amount <= 0.0 || pos.getMargin() < amount) {
            return false;
        }

        // Рассчитываем минимально требуемую начальную маржу (Initial Margin) для позиции
        double currentPrice = oracleService.getPrice(marketId);
        double minInitialMargin = (pos.getSize() * currentPrice) / pos.getLeverage();

        // Нельзя выводить маржу, если оставшийся залог меньше минимального начального
        if (pos.getMargin() - amount < minInitialMargin) {
            System.out.println("[REJECT] Нельзя вывести маржу: оставшийся залог ниже минимального начального залога");
            return false;
        }

        // Разблокируем средства
        balance.unlockMargin(amount);
        pos.setMargin(pos.getMargin() - amount);
        System.out.printf("[MARGIN_ADJUST] Выведено %.2f маржи из изолированной позиции пользователя %s по %s. Новый залог: %.2f%n",
                amount, userId, marketId, pos.getMargin());
        return true;
    }

    public OracleService getOracleService() {
        return oracleService;
    }

    public java.util.Set<String> getAllRegisteredUsers() {
        return balances.keySet();
    }

    public void deposit(String userId, double amount) {
        AccountBalance balance = balances.get(userId);
        if (balance == null) {
            registerUser(userId, amount);
        } else {
            balance.deposit(amount);
        }
    }

    public boolean withdraw(String userId, double amount) {
        AccountBalance balance = balances.get(userId);
        if (balance != null) {
            return balance.withdraw(amount);
        }
        return false;
    }
}
