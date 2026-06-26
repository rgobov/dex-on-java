package com.example.dex.models;

/**
 * Класс AccountBalance представляет баланс пользователя на бирже.
 */
public class AccountBalance {

    private final String userId;        // Идентификатор пользователя (адрес кошелька)
    private double freeBalance;         // Свободный баланс (доступен для вывода или открытия сделок)
    private double lockedMargin;        // Залог, заблокированный в открытых позициях и активных ордерах

    public AccountBalance(String userId, double initialDeposit) {
        this.userId = userId;
        this.freeBalance = initialDeposit;
        this.lockedMargin = 0.0;
    }

    public String getUserId() {
        return userId;
    }

    public double getFreeBalance() {
        return freeBalance;
    }

    public double getLockedMargin() {
        return lockedMargin;
    }

    public double getTotalEquity() {
        return freeBalance + lockedMargin;
    }

    public void deposit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма депозита должна быть больше нуля");
        }
        freeBalance += amount;
    }

    public boolean withdraw(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма вывода должна быть больше нуля");
        }
        if (freeBalance >= amount) {
            freeBalance -= amount;
            return true;
        }
        return false;
    }

    public boolean lockMargin(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма блокируемой маржи должна быть больше нуля");
        }
        if (freeBalance >= amount) {
            freeBalance -= amount;
            lockedMargin += amount;
            return true;
        }
        return false;
    }

    public void unlockMargin(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма разблокируемой маржи должна быть больше нуля");
        }
        if (lockedMargin >= amount) {
            lockedMargin -= amount;
            freeBalance += amount;
        } else {
            freeBalance += lockedMargin;
            lockedMargin = 0.0;
        }
    }

    public void deductLoss(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма списываемого убытка должна быть больше нуля");
        }
        if (lockedMargin >= amount) {
            lockedMargin -= amount;
        } else {
            double remainingLoss = amount - lockedMargin;
            lockedMargin = 0.0;
            freeBalance -= remainingLoss;
        }
    }

    /**
     * Списывает плату за фандинг напрямую со свободного баланса.
     */
    public void payFunding(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма фандинга должна быть больше нуля");
        }
        freeBalance -= amount;
    }

    public void addProfit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма начисляемой прибыли должна быть больше нуля");
        }
        freeBalance += amount;
    }

    @Override
    public String toString() {
        return "AccountBalance{" +
                "userId='" + userId + '\'' +
                ", freeBalance=" + freeBalance +
                ", lockedMargin=" + lockedMargin +
                ", totalEquity=" + getTotalEquity() +
                '}';
    }
}
