package com.example.dex.bridge;

import com.example.dex.models.ChainTransaction;
import com.example.dex.router.RoutingPolicy;
import com.example.dex.router.SmartOrderRouter;

import java.util.List;

/**
 * Блокчейн-мост L2 (L2 Bridge Listener).
 * Сканирует депозитные события в L1Vault и транслирует их как транзакции в SmartOrderRouter.
 */
public final class L2Bridge {

    private final L1Vault vault;
    private final SmartOrderRouter router;
    private long lastProcessedDepositId = 0;

    public L2Bridge(L1Vault vault, SmartOrderRouter router) {
        this.vault = vault;
        this.router = router;
    }

    /**
     * Сканирует новые депозиты на L1 и синхронизирует их с L2.
     */
    public synchronized final void syncDeposits() {
        List<L1Vault.DepositEvent> events = vault.getDepositEventsSince(lastProcessedDepositId);
        for (L1Vault.DepositEvent event : events) {
            System.out.println("[L2_BRIDGE] Обнаружен депозит #" + event.getDepositId() + ", перенаправляем на L2...");

            // Создаем транзакцию депозита для SMR/L2 систем
            ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.DEPOSIT)
                    .userId(event.getUserPublicKey())
                    .amount(event.getAmount())
                    .timestamp(System.currentTimeMillis())
                    .build();

            // Отправляем транзакцию через маршрутизатор во все подключенные книги
            router.routeOrder(tx, RoutingPolicy.BEST_EXECUTION);

            // Обновляем указатель обработанных событий
            lastProcessedDepositId = event.getDepositId();
        }
    }

    public final long getLastProcessedDepositId() {
        return lastProcessedDepositId;
    }
}
