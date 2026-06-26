package com.example.dex.l2.models;

import com.example.dex.models.ChainTransaction;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class L2Block {
    private final long height;
    private final String previousHash;
    private final List<ChainTransaction> transactions;
    private final String proposerId;
    private final long timestamp;
    private final String stateRoot;
    private final String blockHash;

    @JsonCreator
    public L2Block(
            @JsonProperty("height") long height,
            @JsonProperty("previousHash") String previousHash,
            @JsonProperty("transactions") List<ChainTransaction> transactions,
            @JsonProperty("proposerId") String proposerId,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("stateRoot") String stateRoot,
            @JsonProperty("blockHash") String blockHash) {
        this.height = height;
        this.previousHash = previousHash;
        this.transactions = transactions == null ? List.of() : List.copyOf(transactions);
        this.proposerId = proposerId;
        this.timestamp = timestamp;
        this.stateRoot = stateRoot;
        this.blockHash = blockHash;
    }

    @JsonProperty("height") public long getHeight() { return height; }
    @JsonProperty("previousHash") public String getPreviousHash() { return previousHash; }
    @JsonProperty("transactions") public List<ChainTransaction> getTransactions() { return transactions; }
    @JsonProperty("proposerId") public String getProposerId() { return proposerId; }
    @JsonProperty("timestamp") public long getTimestamp() { return timestamp; }
    @JsonProperty("stateRoot") public String getStateRoot() { return stateRoot; }
    @JsonProperty("blockHash") public String getBlockHash() { return blockHash; }

    @Override
    public String toString() {
        return "L2Block{height=" + height + ", proposer='" + proposerId + "', hash='" + blockHash + "'}";
    }
}
