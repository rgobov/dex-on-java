package com.example.dex.l1.state;

import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.l1.models.L1Transaction;

import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LedgerState {

    public static final class DepositRecord {
        private final long depositId;
        private final String userAddress;
        private final double amount;
        private final long timestamp;

        public DepositRecord(long depositId, String userAddress, double amount) {
            this.depositId = depositId;
            this.userAddress = userAddress;
            this.amount = amount;
            this.timestamp = System.currentTimeMillis();
        }

        public final long getDepositId() { return depositId; }
        public final String getUserAddress() { return userAddress; }
        public final double getAmount() { return amount; }
        public final long getTimestamp() { return timestamp; }
    }

    private final Map<String, Double> balances = new ConcurrentHashMap<>();
    private final Map<String, Double> stakes = new ConcurrentHashMap<>();
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final Set<String> processedTxIds = ConcurrentHashMap.newKeySet();
    
    // Депозитные события для L2 Bridge
    private final List<DepositRecord> depositRecords = Collections.synchronizedList(new ArrayList<>());
    private long depositCounter = 0;
    private double totalVaultBalance = 0.0;
    private final List<com.example.dex.models.RollupBatch> rollupBatches = Collections.synchronizedList(new ArrayList<>());

    public LedgerState() {}

    /**
     * Инициализирует баланс пользователя (например, при раздаче/генезисе).
     */
    public final void mint(String address, double amount) {
        balances.merge(address, amount, Double::sum);
    }

    /**
     * Инициализирует стейк валидатора (для генезис-блока).
     */
    public final void registerBootstrapStake(String address, double amount) {
        stakes.merge(address, amount, Double::sum);
    }

    public final void slashStake(String address, double percentage) {
        double currentStake = stakes.getOrDefault(address, 0.0);
        double slashed = currentStake * (1.0 - percentage);
        stakes.put(address, slashed);
    }

    public final double getBalance(String address) {
        return balances.getOrDefault(address, 0.0);
    }

    public final double getStake(String address) {
        return stakes.getOrDefault(address, 0.0);
    }

    public final long getNonce(String address) {
        return nonces.getOrDefault(address, 0L);
    }

    public final double getTotalVaultBalance() {
        return totalVaultBalance;
    }

    /**
     * Возвращает список активных валидаторов (тех, у кого стейк > 0).
     */
    public final Map<String, Double> getActiveValidators() {
        Map<String, Double> active = new HashMap<>();
        for (Map.Entry<String, Double> entry : stakes.entrySet()) {
            if (entry.getValue() > 0.0) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }

    /**
     * Получает список депозитных событий начиная с определенного ID.
     */
    public final List<DepositRecord> getDepositRecordsSince(long lastId) {
        List<DepositRecord> result = new ArrayList<>();
        synchronized (depositRecords) {
            for (DepositRecord rec : depositRecords) {
                if (rec.getDepositId() > lastId) {
                    result.add(rec);
                }
            }
        }
        return result;
    }

    /**
     * Применяет транзакцию к текущему стейту L1.
     */
    public synchronized final boolean applyTransaction(L1Transaction tx) {
        // 1. Защита от дублирования транзакций (Replay Attack)
        if (processedTxIds.contains(tx.getTxId())) {
            System.out.println("[L1_LEDGER] Отклонено: транзакция " + tx.getTxId() + " уже была обработана!");
            return false;
        }

        // 2. Проверка подписи отправителя
        try {
            PublicKey senderKey = DexSignatureUtil.decodePublicKey(tx.getSender());
            String messageToVerify = tx.getSigningData();
            if (tx.getType() == L1Transaction.TxType.WITHDRAW) {
                messageToVerify = tx.getRecipient() + ":" + tx.getAmount();
            }
            if (!DexSignatureUtil.verify(messageToVerify, tx.getSignature(), senderKey)) {
                System.out.println("[L1_LEDGER] Отклонено: неверная подпись отправителя!");
                return false;
            }
        } catch (Exception e) {
            System.out.println("[L1_LEDGER] Ошибка при декодировании ключа: " + e.getMessage());
            return false;
        }

        // 3. Проверка nonce
        if (tx.getType() != L1Transaction.TxType.ROLLUP_COMMIT) {
            long currentNonce = nonces.getOrDefault(tx.getSender(), 0L);
            if (tx.getNonce() != currentNonce + 1) {
                System.out.println("[L1_LEDGER] Отклонено: неверный nonce. Ожидался " + (currentNonce + 1) + ", получен " + tx.getNonce());
                return false;
            }
        }

        // 4. Выполнение логики в зависимости от типа
        boolean success = false;
        switch (tx.getType()) {
            case DEPOSIT:
                success = executeDeposit(tx);
                break;
            case WITHDRAW:
                success = executeWithdraw(tx);
                break;
            case STAKE:
                success = executeStake(tx);
                break;
            case UNSTAKE:
                success = executeUnstake(tx);
                break;
            case SLASH:
                success = executeSlash(tx);
                break;
            case ROLLUP_COMMIT:
                success = executeRollupCommit(tx);
                break;
        }

        if (success) {
            nonces.put(tx.getSender(), tx.getNonce());
            processedTxIds.add(tx.getTxId());
        }

        return success;
    }

    private final boolean executeDeposit(L1Transaction tx) {
        double balance = getBalance(tx.getSender());
        if (balance < tx.getAmount()) {
            System.out.println("[L1_LEDGER] Отклонено: недостаточно баланса для депозита!");
            return false;
        }

        balances.put(tx.getSender(), balance - tx.getAmount());
        totalVaultBalance += tx.getAmount();

        depositCounter++;
        depositRecords.add(new DepositRecord(depositCounter, tx.getSender(), tx.getAmount()));

        System.out.println(String.format("[L1_LEDGER] Успешный депозит #%d от %s на сумму %.2f", 
                depositCounter, tx.getSender().substring(0, 15), tx.getAmount()));
        return true;
    }

    /**
     * Позволяет L1 смарт-контракту (L1Vault) напрямую проводить депозит после подтверждения на L1.
     */
    public synchronized final void depositDirectly(String userAddress, double amount) {
        double balance = getBalance(userAddress);
        if (balance < amount) {
            mint(userAddress, amount - balance);
            balance = amount;
        }
        balances.put(userAddress, balance - amount);
        totalVaultBalance += amount;

        depositCounter++;
        depositRecords.add(new DepositRecord(depositCounter, userAddress, amount));
        System.out.println(String.format("[L1_LEDGER] Смарт-контракт зафиксировал депозит #%d от %s на сумму %.2f", 
                depositCounter, userAddress.substring(0, 15), amount));
    }

    private final boolean executeWithdraw(L1Transaction tx) {
        // Проверяем наличие средств в сейфе моста
        if (totalVaultBalance < tx.getAmount()) {
            System.out.println("[L1_LEDGER] Отклонено: в сейфе L1 моста недостаточно ликвидности!");
            return false;
        }

        // Проверяем мультиподпись 2/3 валидаторов L2
        Map<String, Double> validators = getActiveValidators();
        if (validators.isEmpty()) {
            System.out.println("[L1_LEDGER] Отклонено: нет активных валидаторов на L1!");
            return false;
        }

        double totalStake = validators.values().stream().mapToDouble(Double::doubleValue).sum();
        double signedStake = 0.0;

        String message = tx.getRecipient() + ":" + tx.getAmount();
        Set<String> verifiedValidators = new HashSet<>();

        for (String sig : tx.getValidatorsMultisig()) {
            for (Map.Entry<String, Double> val : validators.entrySet()) {
                String valPub = val.getKey();
                if (verifiedValidators.contains(valPub)) continue;
                try {
                    PublicKey valKey = DexSignatureUtil.decodePublicKey(valPub);
                    if (DexSignatureUtil.verify(message, sig, valKey)) {
                        verifiedValidators.add(valPub);
                        signedStake += val.getValue();
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        double signedFraction = signedStake / totalStake;
        if (signedFraction < 2.0 / 3.0) {
            System.out.println(String.format("[L1_LEDGER] Отклонено: недостаточно голосов валидаторов! Получено %.2f%%, требуется >= 66.6%%", 
                    signedFraction * 100));
            return false;
        }

        // Выплачиваем
        totalVaultBalance -= tx.getAmount();
        balances.merge(tx.getRecipient(), tx.getAmount(), Double::sum);

        System.out.println(String.format("[L1_LEDGER] Успешный вывод средств на адрес %s на сумму %.2f", 
                tx.getRecipient().substring(0, 15), tx.getAmount()));
        return true;
    }

    private final boolean executeStake(L1Transaction tx) {
        double balance = getBalance(tx.getSender());
        if (balance < tx.getAmount()) {
            System.out.println("[L1_LEDGER] Отклонено: недостаточно баланса для стейкинга!");
            return false;
        }

        balances.put(tx.getSender(), balance - tx.getAmount());
        stakes.merge(tx.getSender(), tx.getAmount(), Double::sum);

        System.out.println(String.format("[L1_LEDGER] Валидатор %s добавил в стейк %.2f. Текущий стейк: %.2f", 
                tx.getSender().substring(0, 15), tx.getAmount(), getStake(tx.getSender())));
        return true;
    }

    private final boolean executeUnstake(L1Transaction tx) {
        double stake = getStake(tx.getSender());
        if (stake < tx.getAmount()) {
            System.out.println("[L1_LEDGER] Отклонено: запрашиваемая сумма анстейкинга больше текущего стейка!");
            return false;
        }

        stakes.put(tx.getSender(), stake - tx.getAmount());
        balances.merge(tx.getSender(), tx.getAmount(), Double::sum);

        System.out.println(String.format("[L1_LEDGER] Валидатор %s вывел из стейка %.2f. Текущий стейк: %.2f", 
                tx.getSender().substring(0, 15), tx.getAmount(), getStake(tx.getSender())));
        return true;
    }

    private final boolean executeSlash(L1Transaction tx) {
        // Транзакция слешинга инициализируется честным валидатором и содержит доказательство нарушения.
        // Для простоты, в качестве получателя/адресата указываем нарушителя (recipient).
        String violator = tx.getRecipient();
        double stake = getStake(violator);
        if (stake <= 0.0) {
            System.out.println("[L1_LEDGER] Отклонено: у нарушителя нет активного стейка для списания!");
            return false;
        }

        double slashAmount = Math.min(stake, tx.getAmount());
        stakes.put(violator, stake - slashAmount);

        // Сжигаем оштрафованные средства (не возвращаем в баланс, они просто исчезают из системы)
        System.out.println(String.format("[L1_LEDGER] !!! СЛЕШИНГ !!! Списано %.2f со стейка нарушителя %s", 
                slashAmount, violator.substring(0, 15)));
        return true;
    }

    private final boolean executeRollupCommit(L1Transaction tx) {
        if (tx.getRollupBatch() == null) {
            System.out.println("[L1_LEDGER] Отклонено: транзакция ROLLUP_COMMIT не содержит RollupBatch!");
            return false;
        }
        rollupBatches.add(tx.getRollupBatch());
        System.out.println(String.format("[L1_LEDGER] Успешно зафиксирован Rollup Batch #%d с L3. Сделок: %d, State Root: %s", 
                tx.getRollupBatch().getBatchId(), tx.getRollupBatch().getTrades().size(), tx.getRollupBatch().getStateRoot().substring(0, 10)));
        return true;
    }

    public final List<com.example.dex.models.RollupBatch> getRollupBatches() {
        return rollupBatches;
    }
}
