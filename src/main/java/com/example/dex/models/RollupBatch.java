package com.example.dex.models;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Класс RollupBatch представляет собой сгруппированный пакет транзакций/сделок,
 * совершенных на L3 и отправляемых для фиксации состояния в расчетную сеть L2.
 */
public final class RollupBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long batchId;
    private final List<Trade> trades;
    private final String prevStateRoot;
    private final String stateRoot;
    private final String sequencerSignature;
    private final long timestamp;

    public RollupBatch(long batchId, List<Trade> trades, String prevStateRoot, String sequencerSignature, long timestamp) {
        this.batchId = batchId;
        this.trades = Collections.unmodifiableList(new ArrayList<>(trades));
        this.prevStateRoot = prevStateRoot;
        this.sequencerSignature = sequencerSignature;
        this.timestamp = timestamp;
        this.stateRoot = calculateStateRoot();
    }

    public final long getBatchId() {
        return batchId;
    }

    public final List<Trade> getTrades() {
        return trades;
    }

    public final String getPrevStateRoot() {
        return prevStateRoot;
    }

    public final String getStateRoot() {
        return stateRoot;
    }

    public final String getSequencerSignature() {
        return sequencerSignature;
    }

    public final long getTimestamp() {
        return timestamp;
    }

    private final String calculateStateRoot() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(batchId).append(prevStateRoot).append(timestamp);
            for (Trade t : trades) {
                sb.append(t.getBuyerId())
                  .append(t.getSellerId())
                  .append(t.getPrice())
                  .append(t.getAmount());
            }
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
