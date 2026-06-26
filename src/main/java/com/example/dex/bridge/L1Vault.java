package com.example.dex.bridge;

import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.l1.consensus.L1ConsensusPoS;
import com.example.dex.l1.models.L1Block;
import com.example.dex.l1.models.L1Transaction;
import com.example.dex.l1.state.LedgerState;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище смарт-контракта на L1.
 * Теперь является шлюзом к реальному L1 LedgerState и L1ConsensusPoS.
 */
public final class L1Vault {

    public static final class DepositEvent {
        private final long depositId;
        private final String userPublicKey;
        private final double amount;

        public DepositEvent(long depositId, String userPublicKey, double amount) {
            this.depositId = depositId;
            this.userPublicKey = userPublicKey;
            this.amount = amount;
        }

        public final long getDepositId() { return depositId; }
        public final String getUserPublicKey() { return userPublicKey; }
        public final double getAmount() { return amount; }
    }

    private final LedgerState ledgerState;
    private final L1ConsensusPoS consensus;
    private final KeyPair l1AdminKeys;
    private final String l1AdminAddress;

    public L1Vault(List<String> validatorPublicKeys) {
        this.ledgerState = new LedgerState();
        try {
            this.l1AdminKeys = DexSignatureUtil.generateKeyPair();
            this.l1AdminAddress = DexSignatureUtil.encodePublicKey(l1AdminKeys.getPublic());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate L1 admin keypair", e);
        }
        this.consensus = new L1ConsensusPoS(ledgerState, l1AdminAddress);

        // Регистрируем переданных L2 валидаторов в качестве валидаторов на L1
        for (String valPub : validatorPublicKeys) {
            ledgerState.registerBootstrapStake(valPub, 10000.0);
        }

        // Добавляем генезис-блок для инициализации цепочки L1
        long genTime = System.currentTimeMillis();
        String blockSigningData = "1:0000000000000000000000000000000000000000000000000000000000000000:" + l1AdminAddress + ":" + genTime;
        String blockSig;
        try {
            blockSig = DexSignatureUtil.sign(blockSigningData, l1AdminKeys.getPrivate());
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign genesis block", e);
        }
        L1Block genesisBlock = new L1Block(1, List.of(), "0000000000000000000000000000000000000000000000000000000000000000", l1AdminAddress, blockSig, genTime);
        consensus.processNewBlock(genesisBlock);
    }

    /**
     * Пользователь вносит депозит на L1.
     */
    public synchronized final void deposit(String userPublicKey, double amount) {
        ledgerState.depositDirectly(userPublicKey, amount);
    }

    /**
     * Пользователь запрашивает вывод средств с L1.
     * Требуется: подпись пользователя и подписи не менее 2/3 валидаторов.
     * Создает транзакцию и запаковывает в блок L1 с проверкой консенсуса.
     */
    public synchronized final boolean withdraw(String userPublicKey, double amount, String userSignature, List<String> validatorSignatures) {
        long nextNonce = ledgerState.getNonce(userPublicKey) + 1;
        long timestamp = System.currentTimeMillis();

        L1Transaction.Builder txBuilder = new L1Transaction.Builder(L1Transaction.TxType.WITHDRAW)
                .sender(userPublicKey)
                .recipient(userPublicKey)
                .amount(amount)
                .signature(userSignature)
                .nonce(nextNonce)
                .timestamp(timestamp);

        for (String sig : validatorSignatures) {
            txBuilder.addValidatorSignature(sig);
        }

        L1Transaction tx = txBuilder.build();

        // Формируем блок L1 с этой транзакцией вывода
        long blockNum = consensus.getChainHeight() + 1;
        String prevHash = consensus.getLastBlockHash();
        long blockTimestamp = System.currentTimeMillis();

        String blockSigningData = blockNum + ":" + prevHash + ":" + l1AdminAddress + ":" + blockTimestamp;
        String blockSig;
        try {
            blockSig = DexSignatureUtil.sign(blockSigningData, l1AdminKeys.getPrivate());
        } catch (Exception e) {
            System.out.println("[L1_VAULT] Ошибка подписи блока: " + e.getMessage());
            return false;
        }

        L1Block block = new L1Block(blockNum, List.of(tx), prevHash, l1AdminAddress, blockSig, blockTimestamp);

        // Передаем блок в консенсус L1 для обработки, валидации подписей и применения перехода состояния
        return consensus.processNewBlock(block);
    }

    public synchronized final List<DepositEvent> getDepositEventsSince(long lastEventId) {
        List<LedgerState.DepositRecord> records = ledgerState.getDepositRecordsSince(lastEventId);
        List<DepositEvent> events = new ArrayList<>();
        for (LedgerState.DepositRecord rec : records) {
            events.add(new DepositEvent(rec.getDepositId(), rec.getUserAddress(), rec.getAmount()));
        }
        return events;
    }

    public final double getTotalVaultBalance() {
        return ledgerState.getTotalVaultBalance();
    }

    public final LedgerState getLedgerState() {
        return ledgerState;
    }

    public final L1ConsensusPoS getConsensus() {
        return consensus;
    }

    public synchronized final boolean commitRollup(com.example.dex.models.RollupBatch batch) {
        long timestamp = System.currentTimeMillis();
        L1Transaction.Builder txBuilder = new L1Transaction.Builder(L1Transaction.TxType.ROLLUP_COMMIT)
                .sender(l1AdminAddress)
                .rollupBatch(batch)
                .timestamp(timestamp);

        L1Transaction unsignedTx = txBuilder.build();
        String txSig;
        try {
            txSig = DexSignatureUtil.sign(unsignedTx.getSigningData(), l1AdminKeys.getPrivate());
        } catch (Exception e) {
            System.out.println("[L1_VAULT] Ошибка подписи транзакции Rollup: " + e.getMessage());
            return false;
        }
        L1Transaction tx = txBuilder.signature(txSig).build();

        long blockNum = consensus.getChainHeight() + 1;
        String prevHash = consensus.getLastBlockHash();
        long blockTimestamp = System.currentTimeMillis();

        String blockSigningData = blockNum + ":" + prevHash + ":" + l1AdminAddress + ":" + blockTimestamp;
        String blockSig;
        try {
            blockSig = DexSignatureUtil.sign(blockSigningData, l1AdminKeys.getPrivate());
        } catch (Exception e) {
            System.out.println("[L1_VAULT] Ошибка подписи блока Rollup: " + e.getMessage());
            return false;
        }

        L1Block block = new L1Block(blockNum, List.of(tx), prevHash, l1AdminAddress, blockSig, blockTimestamp);
        return consensus.processNewBlock(block);
    }

    public final List<com.example.dex.models.RollupBatch> getRollupBatches() {
        return ledgerState.getRollupBatches();
    }
}
