package com.example.dex.l2.network;

import com.example.dex.l2.models.L2Block;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ConsensusMessage {
    public enum Type { PRE_PREPARE, PREPARE, COMMIT }

    private Type type;
    private String fromValidator;
    private long height;
    private L2Block block;

    public ConsensusMessage() {}

    public ConsensusMessage(Type type, String fromValidator, long height, L2Block block) {
        this.type = type;
        this.fromValidator = fromValidator;
        this.height = height;
        this.block = block;
    }

    @JsonProperty
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    @JsonProperty
    public String getFromValidator() { return fromValidator; }
    public void setFromValidator(String fromValidator) { this.fromValidator = fromValidator; }

    @JsonProperty
    public long getHeight() { return height; }
    public void setHeight(long height) { this.height = height; }

    @JsonProperty
    public L2Block getBlock() { return block; }
    public void setBlock(L2Block block) { this.block = block; }
}
