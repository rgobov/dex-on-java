package com.example.dex.l2.consensus;

import java.util.List;

public class LeaderElector {
    private final List<String> validators;
    private final List<Double> stakes;
    private long round = 0;

    public LeaderElector(List<String> validators, List<Double> stakes) {
        if (validators.isEmpty()) throw new IllegalArgumentException("No validators");
        this.validators = List.copyOf(validators);
        this.stakes = List.copyOf(stakes);
    }

    public synchronized String getLeader() {
        double totalStake = stakes.stream().mapToDouble(Double::doubleValue).sum();
        if (totalStake <= 0) return validators.get((int) (round % validators.size()));

        double target = (round * 1000.0) % totalStake;
        double cumulative = 0;
        for (int i = 0; i < validators.size(); i++) {
            cumulative += stakes.get(i);
            if (target < cumulative) return validators.get(i);
        }
        return validators.get(validators.size() - 1);
    }

    public synchronized void nextRound() {
        round++;
    }

    public synchronized long getRound() { return round; }
}
