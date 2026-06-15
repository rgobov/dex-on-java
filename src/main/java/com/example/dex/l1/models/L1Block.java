package com.example.dex.l1.models;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

public final class L1Block implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long blockNumber;
    private final List<L1Transaction> transactions;
    private final String previousBlockHash;
    private final String blockHash;
    private final long timestamp;
    private final String proposer;
    private final String proposerSignature;

    public L1Block(long blockNumber, List<L1Transaction> transactions, String previousBlockHash, 
                   String proposer, String proposerSignature, long timestamp) {
        this.blockNumber = blockNumber;
        this.transactions = Collections.unmodifiableList(new ArrayList<>(transactions));
        this.previousBlockHash = previousBlockHash;
        this.timestamp = timestamp;
        this.proposer = proposer;
        this.proposerSignature = proposerSignature;
        this.blockHash = calculateHash();
    }

    public final long getBlockNumber() { return blockNumber; }
    public final List<L1Transaction> getTransactions() { return transactions; }
    public final String getPreviousBlockHash() { return previousBlockHash; }
    public final String getBlockHash() { return blockHash; }
    public final long getTimestamp() { return timestamp; }
    public final String getProposer() { return proposer; }
    public final String getProposerSignature() { return proposerSignature; }

    public final String getSigningData() {
        return blockNumber + ":" + previousBlockHash + ":" + proposer + ":" + timestamp;
    }

    private final String calculateHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(blockNumber).append(previousBlockHash).append(proposer).append(timestamp);
            for (L1Transaction tx : transactions) {
                sb.append(tx.getTxId());
            }
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
