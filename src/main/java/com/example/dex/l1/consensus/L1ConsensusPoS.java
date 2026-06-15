package com.example.dex.l1.consensus;

import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.l1.models.L1Block;
import com.example.dex.l1.models.L1Transaction;
import com.example.dex.l1.state.LedgerState;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class L1ConsensusPoS {

    private final LedgerState ledgerState;
    private final List<L1Block> blockChain = new ArrayList<>();
    private String lastBlockHash = "0000000000000000000000000000000000000000000000000000000000000000";
    private final String authorizedProposer;

    public L1ConsensusPoS(LedgerState ledgerState, String authorizedProposer) {
        this.ledgerState = ledgerState;
        this.authorizedProposer = authorizedProposer;
    }

    /**
     * Возвращает текущую высоту блокчейна L1.
     */
    public final long getChainHeight() {
        return blockChain.size();
    }

    public final String getLastBlockHash() {
        return lastBlockHash;
    }

    public final List<L1Block> getBlocks() {
        return List.copyOf(blockChain);
    }

    /**
     * Валидирует и добавляет новый блок в блокчейн L1, применяя содержащиеся в нем транзакции.
     */
    public synchronized final boolean processNewBlock(L1Block block) {
        // 1. Проверяем высоту/номер блока
        if (block.getBlockNumber() != getChainHeight() + 1) {
            System.out.println("[L1_CONSENSUS] Отклонено: неверная высота блока. Ожидалась " + (getChainHeight() + 1));
            return false;
        }

        // 2. Проверяем связь с предыдущим блоком
        if (!block.getPreviousBlockHash().equals(lastBlockHash)) {
            System.out.println("[L1_CONSENSUS] Отклонено: неверная ссылка на предыдущий хэш блока!");
            return false;
        }

        // 3. Проверяем, является ли автор блока (proposer) уполномоченным лицом или активным валидатором
        Map<String, Double> activeValidators = ledgerState.getActiveValidators();
        boolean isAuthorized = block.getProposer().equals(authorizedProposer) || activeValidators.containsKey(block.getProposer());
        if (!isAuthorized) {
            System.out.println("[L1_CONSENSUS] Отклонено: создатель блока не авторизован на L1!");
            return false;
        }

        // 4. Проверяем цифровую подпись создателя блока
        try {
            PublicKey proposerKey = DexSignatureUtil.decodePublicKey(block.getProposer());
            if (!DexSignatureUtil.verify(block.getSigningData(), block.getProposerSignature(), proposerKey)) {
                System.out.println("[L1_CONSENSUS] Отклонено: неверная подпись создателя блока!");
                return false;
            }
        } catch (Exception e) {
            System.out.println("[L1_CONSENSUS] Ошибка верификации подписи блока: " + e.getMessage());
            return false;
        }

        // 5. Симулируем транзакционный переход состояния (State Transition)
        // Сначала накапливаем изменения во временном стейте, чтобы откатить в случае ошибки
        List<L1Transaction> appliedTxs = new ArrayList<>();
        boolean txFailure = false;

        for (L1Transaction tx : block.getTransactions()) {
            if (!ledgerState.applyTransaction(tx)) {
                System.out.println("[L1_CONSENSUS] Ошибка: транзакция " + tx.getTxId() + " не смогла примениться к стейту!");
                txFailure = true;
                break;
            }
            appliedTxs.add(tx);
        }

        if (txFailure) {
            // В реальной системе здесь бы происходил откат состояния (rollback).
            // В нашей реализации мы возвращаем false и не добавляем блок в цепочку.
            System.out.println("[L1_CONSENSUS] Отклонено: блок содержит некорректные транзакции!");
            return false;
        }

        // 6. Блок валиден, обновляем хэш цепочки и сохраняем его
        blockChain.add(block);
        lastBlockHash = block.getBlockHash();
        System.out.println(String.format("[L1_CONSENSUS] Успешно добавлен блок #%d [Hash: %s...]", 
                block.getBlockNumber(), block.getBlockHash().substring(0, 15)));
        return true;
    }
}
