package com.example.dex.l2.consensus;

import com.example.dex.l2.models.L2Block;
import com.example.dex.l2.mempool.Mempool;
import com.example.dex.models.ChainTransaction;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class PbftConsensus {

    public interface Executor {
        void executeBlock(L2Block block, List<ChainTransaction> txs);
    }

    private final String myId;
    private final List<String> allValidators;
    private final LeaderElector leaderElector;
    private final Mempool mempool;
    private final Executor executor;
    private final int f;

    private final List<L2Block> committedChain = new CopyOnWriteArrayList<>();
    private final Map<Long, Set<String>> prepareVotes = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> commitVotes = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    public PbftConsensus(String myId, List<String> allValidators,
                         LeaderElector leaderElector, Mempool mempool, Executor executor) {
        this.myId = myId;
        this.allValidators = List.copyOf(allValidators);
        this.leaderElector = leaderElector;
        this.mempool = mempool;
        this.executor = executor;
        this.f = (allValidators.size() - 1) / 3;
    }

    public List<L2Block> getCommittedChain() {
        return List.copyOf(committedChain);
    }

    public long getCurrentHeight() {
        return committedChain.size();
    }

    public String getLastBlockHash() {
        if (committedChain.isEmpty()) {
            return "0".repeat(64);
        }
        return committedChain.get(committedChain.size() - 1).getBlockHash();
    }

    /**
     * Main consensus loop — runs a single round.
     */
    public L2Block runConsensusRound() throws Exception {
        if (!running) return null;

        String leader = leaderElector.getLeader();
        long height = getCurrentHeight() + 1;
        String prevHash = getLastBlockHash();

        List<ChainTransaction> batch = mempool.drain(100);

        L2Block proposedBlock;
        if (myId.equals(leader)) {
            proposedBlock = buildBlock(height, prevHash, batch, leader);
        } else {
            proposedBlock = new L2Block(height, prevHash, List.of(), leader, System.currentTimeMillis(), "", "");
        }

        // Phase 1: PrePrepare — leader proposes
        if (myId.equals(leader)) {
            broadcastPrePrepare(proposedBlock);
        }

        // Phase 2: Prepare — validate and vote
        if (!myId.equals(leader) && !batch.isEmpty()) {
            // Non-leaders build their own version based on their own mempool view
            List<ChainTransaction> myBatch = mempool.drain(100);
            proposedBlock = buildBlock(height, prevHash, myBatch, leader);
        } else if (!myId.equals(leader)) {
            // Empty block — just accept
            proposedBlock = buildBlock(height, prevHash, List.of(), leader);
        }

        // In-process: all validators immediately send Prepare
        broadcastPrepare(height);

        // Wait for 2f+1 Prepare votes
        long deadline = System.currentTimeMillis() + 2000;
        while (prepareVotes.getOrDefault(height, Collections.emptySet()).size() <= 2 * f
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        if (prepareVotes.getOrDefault(height, Collections.emptySet()).size() > 2 * f) {
            // Phase 3: Commit
            broadcastCommit(height);

            // Wait for 2f+1 Commit votes
            deadline = System.currentTimeMillis() + 2000;
            while (commitVotes.getOrDefault(height, Collections.emptySet()).size() <= 2 * f
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            if (commitVotes.getOrDefault(height, Collections.emptySet()).size() > 2 * f) {
                // BLOCK COMMITTED — execute
                executor.executeBlock(proposedBlock, proposedBlock.getTransactions());
                committedChain.add(proposedBlock);
                leaderElector.nextRound();
                return proposedBlock;
            }
        }

        // Timeout — retry with next leader
        leaderElector.nextRound();
        return null;
    }

    public void shutdown() {
        running = false;
    }

    // --- Internal ---

    private L2Block buildBlock(long height, String prevHash, List<ChainTransaction> txs, String proposer) {
        String stateRoot = computeStateRoot(txs);
        String blockHash = computeBlockHash(height, prevHash, txs, proposer, stateRoot);
        return new L2Block(height, prevHash, txs, proposer, System.currentTimeMillis(), stateRoot, blockHash);
    }

    // In-process simulated broadcasts — actually direct local state
    private void broadcastPrePrepare(L2Block block) {
        // All validators receive it by reading the block object (in-process, all share the same JVM)
        for (String vid : allValidators) {
            receivePrePrepare(vid, block);
        }
    }

    public void receivePrePrepare(String validatorId, L2Block block) {
        // Validate block, then implicitly prepare
        if (running && block.getHeight() == getCurrentHeight() + 1) {
            broadcastPrepare(block.getHeight());
        }
    }

    private void broadcastPrepare(long height) {
        prepareVotes.computeIfAbsent(height, k -> ConcurrentHashMap.newKeySet()).add(myId);
        for (String vid : allValidators) {
            receivePrepare(vid, height, myId);
        }
    }

    public void receivePrepare(String validatorId, long height, String from) {
        if (running) {
            prepareVotes.computeIfAbsent(height, k -> ConcurrentHashMap.newKeySet()).add(from);
        }
    }

    private void broadcastCommit(long height) {
        commitVotes.computeIfAbsent(height, k -> ConcurrentHashMap.newKeySet()).add(myId);
        for (String vid : allValidators) {
            receiveCommit(vid, height, myId);
        }
    }

    public void receiveCommit(String validatorId, long height, String from) {
        if (running) {
            commitVotes.computeIfAbsent(height, k -> ConcurrentHashMap.newKeySet()).add(from);
        }
    }

    private String computeStateRoot(List<ChainTransaction> txs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (ChainTransaction tx : txs) {
                md.update(tx.toString().getBytes());
            }
            return bytesToHex(md.digest());
        } catch (Exception e) {
            return "0".repeat(64);
        }
    }

    private String computeBlockHash(long height, String prevHash, List<ChainTransaction> txs,
                                     String proposer, String stateRoot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Long.toString(height).getBytes());
            md.update(prevHash.getBytes());
            md.update(txs.toString().getBytes());
            md.update(proposer.getBytes());
            md.update(stateRoot.getBytes());
            return bytesToHex(md.digest());
        } catch (Exception e) {
            return "0".repeat(64);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
