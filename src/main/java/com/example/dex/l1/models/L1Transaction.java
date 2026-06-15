package com.example.dex.l1.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class L1Transaction implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum TxType {
        DEPOSIT,
        WITHDRAW,
        STAKE,
        UNSTAKE,
        SLASH,
        ROLLUP_COMMIT
    }

    private final String txId;
    private final TxType type;
    private final String sender;
    private final String recipient;
    private final double amount;
    private final String signature;
    private final List<String> validatorsMultisig;
    private final long timestamp;
    private final long nonce;
    private final com.example.dex.models.RollupBatch rollupBatch;

    private L1Transaction(Builder builder) {
        this.txId = builder.txId;
        this.type = builder.type;
        this.sender = builder.sender;
        this.recipient = builder.recipient;
        this.amount = builder.amount;
        this.signature = builder.signature;
        this.validatorsMultisig = Collections.unmodifiableList(new ArrayList<>(builder.validatorsMultisig));
        this.timestamp = builder.timestamp;
        this.nonce = builder.nonce;
        this.rollupBatch = builder.rollupBatch;
    }

    public final String getTxId() { return txId; }
    public final TxType getType() { return type; }
    public final String getSender() { return sender; }
    public final String getRecipient() { return recipient; }
    public final double getAmount() { return amount; }
    public final String getSignature() { return signature; }
    public final List<String> getValidatorsMultisig() { return validatorsMultisig; }
    public final long getTimestamp() { return timestamp; }
    public final long getNonce() { return nonce; }
    public final com.example.dex.models.RollupBatch getRollupBatch() { return rollupBatch; }

    public final String getSigningData() {
        return type.name() + ":" + sender + ":" + recipient + ":" + amount + ":" + nonce + ":" + timestamp;
    }

    public static final class Builder {
        private String txId = "";
        private final TxType type;
        private String sender = "";
        private String recipient = "";
        private double amount = 0.0;
        private String signature = "";
        private final List<String> validatorsMultisig = new ArrayList<>();
        private long timestamp = System.currentTimeMillis();
        private long nonce = 0L;
        private com.example.dex.models.RollupBatch rollupBatch = null;

        public Builder(TxType type) {
            this.type = type;
        }

        public Builder txId(String txId) { this.txId = txId; return this; }
        public Builder sender(String sender) { this.sender = sender; return this; }
        public Builder recipient(String recipient) { this.recipient = recipient; return this; }
        public Builder amount(double amount) { this.amount = amount; return this; }
        public Builder signature(String signature) { this.signature = signature; return this; }
        public Builder addValidatorSignature(String sig) { this.validatorsMultisig.add(sig); return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder nonce(long nonce) { this.nonce = nonce; return this; }
        public Builder rollupBatch(com.example.dex.models.RollupBatch rollupBatch) { this.rollupBatch = rollupBatch; return this; }

        public final L1Transaction build() {
            if (txId == null || txId.isEmpty()) {
                // Generate a simple unique ID
                this.txId = java.util.UUID.randomUUID().toString();
            }
            return new L1Transaction(this);
        }
    }
}
