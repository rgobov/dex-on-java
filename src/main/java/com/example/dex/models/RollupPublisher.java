package com.example.dex.models;

import com.example.dex.bridge.L1Vault;
import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.disruptor.StateExecutionHandler;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Класс RollupPublisher отвечает за периодический сбор совершенных на L3 сделок,
 * их упаковку в RollupBatch и отправку (публикацию) в расчетную сеть L2 (через L1Vault).
 */
public final class RollupPublisher {

    private final StateExecutionHandler stateExecutionHandler;
    private final L1Vault vault;
    private final KeyPair publisherKeys;
    private final Thread workerThread;
    
    private volatile boolean running = false;
    private int lastTradeIndex = 0;
    private long nextBatchId = 1;
    private String prevStateRoot = "0000000000000000000000000000000000000000000000000000000000000000";

    public RollupPublisher(StateExecutionHandler stateExecutionHandler, L1Vault vault) {
        this.stateExecutionHandler = stateExecutionHandler;
        this.vault = vault;
        try {
            this.publisherKeys = DexSignatureUtil.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate rollup publisher keypair", e);
        }
        
        this.workerThread = new Thread(this::runLoop, "RollupPublisher-Thread");
        this.workerThread.setDaemon(true);
    }

    public synchronized final void start() {
        if (running) return;
        running = true;
        workerThread.start();
        System.out.println("[ROLLUP_PUBLISHER] Сервис публикации роллапов запущен.");
    }

    public synchronized final void stop() {
        if (!running) return;
        running = false;
        workerThread.interrupt();
        try {
            workerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[ROLLUP_PUBLISHER] Сервис публикации роллапов остановлен.");
    }

    private final void runLoop() {
        while (running) {
            try {
                Thread.sleep(3000); // Публикуем батч каждые 3 секунды
                publishNextBatch();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                System.out.println("[ROLLUP_PUBLISHER] Ошибка в цикле публикации батча: " + e.getMessage());
            }
        }
    }

    public synchronized final boolean publishNextBatch() {
        List<Trade> allTrades = stateExecutionHandler.getExecutedTrades();
        int currentSize;
        List<Trade> batchTrades;
        synchronized (allTrades) {
            currentSize = allTrades.size();
            if (currentSize <= lastTradeIndex) {
                return false;
            }
            batchTrades = new ArrayList<>(allTrades.subList(lastTradeIndex, currentSize));
        }
        long timestamp = System.currentTimeMillis();

        // Подписываем переход состояния приватным ключом издателя
        String sigData = nextBatchId + ":" + prevStateRoot + ":" + timestamp;
        String signature;
        try {
            signature = DexSignatureUtil.sign(sigData, publisherKeys.getPrivate());
        } catch (Exception e) {
            System.out.println("[ROLLUP_PUBLISHER] Ошибка подписи батча: " + e.getMessage());
            return false;
        }

        RollupBatch batch = new RollupBatch(
                nextBatchId,
                batchTrades,
                prevStateRoot,
                signature,
                timestamp
        );

        // Отправляем батч в L2 Settlement
        boolean success = vault.commitRollup(batch);
        if (success) {
            System.out.println(String.format("[ROLLUP_PUBLISHER] Успешно отправлен батч роллапа #%d. Сделок: %d, StateRoot: %s", 
                    batch.getBatchId(), batch.getTrades().size(), batch.getStateRoot().substring(0, 12)));
            
            this.lastTradeIndex = currentSize;
            this.prevStateRoot = batch.getStateRoot();
            this.nextBatchId++;
            return true;
        } else {
            System.out.println("[ROLLUP_PUBLISHER] Не удалось зафиксировать батч роллапа #" + nextBatchId + " в консенсусе L2!");
            return false;
        }
    }
}
