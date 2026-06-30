package com.example.dex.server;

import com.example.dex.bridge.ArbitrumBridge;
import com.example.dex.bridge.ArbitrumBridgePoller;
import com.example.dex.bridge.TonBridge;
import com.example.dex.bridge.TonBridgePoller;
import com.example.dex.bridge.TonWithdrawalFinalizer;
import com.example.dex.bridge.WithdrawalFinalizer;
import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.l2.consensus.LeaderElector;
import com.example.dex.l2.consensus.PbftConsensus;
import com.example.dex.l2.mempool.Mempool;
import com.example.dex.l2.models.L2Block;
import com.example.dex.l2.network.ConsensusMessage;
import com.example.dex.l2.network.ValidatorNetwork;
import com.example.dex.l3.persistence.FlatFileStore;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.models.*;
import com.example.dex.oracle.OracleService;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ValidatorNode {

    private final String myId;
    private final int apiPort;
    private final Path dataDir;

    private MarginManager marginManager;
    private StateExecutionHandler handler;
    private Disruptor<ChainTxEvent> disruptor;
    private RingBuffer<ChainTxEvent> ringBuffer;
    private FlatFileStore flatFileStore;
    private Mempool mempool;
    private PbftConsensus consensus;
    private LeaderElector leaderElector;
    private ValidatorNetwork network;
    private ArbitrumBridge bridge;
    private ArbitrumBridgePoller bridgePoller;
    private WithdrawalFinalizer withdrawalFinalizer;
    private RollupPublisher rollupPublisher;

    // TON bridge (Telegram)
    private TonBridge tonBridge;
    private TonBridgePoller tonBridgePoller;
    private TonWithdrawalFinalizer tonWithdrawalFinalizer;
    private Javalin api;
    private ScheduledExecutorService consensusRunner;
    private final AtomicLong txSeq = new AtomicLong(0);

    public ValidatorNode(String myId, int apiPort, Path dataDir) {
        this.myId = myId;
        this.apiPort = apiPort;
        this.dataDir = dataDir;
    }

    public void start(List<String> allValidatorIds, Map<String, Integer> validatorPorts,
                      MarketSpecification marketSpec) throws Exception {
        System.out.println("[NODE " + myId + "] Initializing...");

        // L3: Execution Engine
        OracleService oracle = new OracleService();
        oracle.setPrice(marketSpec.getMarketId(), 60000.0);
        marginManager = new MarginManager(oracle);
        marginManager.registerMarket(marketSpec);
        handler = new StateExecutionHandler(
                marginManager,
                new LiquidationEngine(marginManager, oracle),
                new FundingCalculator(marginManager, oracle)
        );
        handler.registerMarket(marketSpec.getMarketId());

        // Persistence
        flatFileStore = new FlatFileStore(dataDir.resolve("l3"));
        flatFileStore.init();

        if (flatFileStore.hasSnapshot()) {
            System.out.println("[NODE " + myId + "] Recovering from snapshot...");
            FlatFileStore.SnapshotData snapshot = flatFileStore.loadSnapshot();
            if (snapshot != null) {
                snapshot.restoreInto(handler, marginManager, oracle);
                txSeq.set(snapshot.sequence);
                // Restore processed bridge ticket IDs
                if (snapshot.processedBridgeTicketIds != null) {
                    handler.getProcessedBridgeTxIds().addAll(snapshot.processedBridgeTicketIds);
                }
                for (ChainTransaction tx : flatFileStore.replayAllFromWal()) {
                    publishTxSync(tx);
                    txSeq.incrementAndGet();
                }
            }
        }

        disruptor = new Disruptor<>(
                new ChainTxEventFactory(), 1024,
                DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new YieldingWaitStrategy()
        );
        disruptor.handleEventsWith(handler);
        ringBuffer = disruptor.start();

        // L1: Arbitrum Bridge
        // В production challengeWindowMs = 604800000 (7 дней),
        // для dev оставляем 30000ms для тестирования
        long challengeWindowMs = 30_000;
        bridge = new ArbitrumBridge(challengeWindowMs);
        bridge.start();

        // Bridge Poller: читает outbox bridge → добавляет в mempool
        bridgePoller = new ArbitrumBridgePoller(bridge, mempool, handler.getProcessedBridgeTxIds());
        bridgePoller.start();

        // Withdrawal Finalizer: L2 executed → L1 bridge
        withdrawalFinalizer = new WithdrawalFinalizer(bridge, handler);
        withdrawalFinalizer.start();

        // Rollup Publisher: каждые N секунд публикует state root в L1
        rollupPublisher = new RollupPublisher(handler, bridge);
        rollupPublisher.start();

        // L2: Mempool + Consensus
        mempool = new Mempool();
        List<Double> stakes = allValidatorIds.stream().map(id -> 100.0).toList();
        leaderElector = new LeaderElector(allValidatorIds, stakes);

        consensus = new PbftConsensus(myId, allValidatorIds, leaderElector, mempool, (block, txs) -> {
            for (ChainTransaction tx : txs) {
                publishTxSync(tx);
                txSeq.incrementAndGet();
            }
        });

        // L2: Network
        network = new ValidatorNetwork(myId, validatorPorts.get(myId), validatorPorts);
        network.registerHandler(ConsensusMessage.Type.PRE_PREPARE, msg -> {
            if (msg.getBlock() != null) {
                consensus.receivePrePrepare(msg.getFromValidator(), msg.getBlock());
            }
        });
        network.registerHandler(ConsensusMessage.Type.PREPARE, msg ->
                consensus.receivePrepare(msg.getFromValidator(), msg.getHeight(), msg.getFromValidator()));
        network.registerHandler(ConsensusMessage.Type.COMMIT, msg ->
                consensus.receiveCommit(msg.getFromValidator(), msg.getHeight(), msg.getFromValidator()));
        network.start();

        // TON Bridge (Telegram): без задержек и challenge window
        tonBridge = new TonBridge();
        tonBridge.start();
        // Кредитуем vault для тестов (всегда есть USDT на выдачу)
        tonBridge.creditVault(myId, 1_000_000.0);

        tonBridgePoller = new TonBridgePoller(tonBridge, mempool, handler.getProcessedBridgeTxIds());
        tonBridgePoller.start();

        tonWithdrawalFinalizer = new TonWithdrawalFinalizer(tonBridge, handler);
        tonWithdrawalFinalizer.start();

        // Consensus loop background thread
        consensusRunner = Executors.newSingleThreadScheduledExecutor();
        consensusRunner.scheduleAtFixedRate(() -> {
            try {
                L2Block block = consensus.runConsensusRound();
                if (block != null) {
                    System.out.println("[NODE " + myId + "] Block committed: " + block.getHeight()
                            + " txs=" + block.getTransactions().size()
                            + " proposer=" + block.getProposerId());

                    // Save snapshot periodically
                    if (block.getHeight() % 10 == 0) {
                        flatFileStore.saveSnapshot(txSeq.get(), handler, marginManager, oracle);
                        flatFileStore.truncateWal();
                    }
                }
            } catch (Exception e) {
                System.err.println("[NODE " + myId + "] Consensus error: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Periodic snapshots fallback
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                flatFileStore.saveSnapshot(txSeq.get(), handler, marginManager, oracle);
                flatFileStore.truncateWal();
            } catch (Exception e) {
                System.err.println("[NODE " + myId + "] Snapshot error: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        // REST API
        startApi(marketSpec.getMarketId());

        System.out.println("[NODE " + myId + "] Ready on port " + apiPort);
    }

    public void stop() {
        if (rollupPublisher != null) rollupPublisher.stop();
        if (withdrawalFinalizer != null) withdrawalFinalizer.stop();
        if (tonWithdrawalFinalizer != null) tonWithdrawalFinalizer.stop();
        if (bridgePoller != null) bridgePoller.stop();
        if (tonBridgePoller != null) tonBridgePoller.stop();
        if (bridge != null) bridge.stop();
        if (tonBridge != null) tonBridge.stop();
        if (consensusRunner != null) consensusRunner.shutdown();
        if (network != null) network.stop();
        if (api != null) api.stop();
        if (disruptor != null) disruptor.shutdown();
        try {
            flatFileStore.saveSnapshot(txSeq.get(), handler, marginManager,
                    marginManager.getOracleService());
            flatFileStore.truncateWal();
        } catch (Exception e) {
            System.err.println("[NODE " + myId + "] Shutdown snapshot error: " + e.getMessage());
        }
    }

    // === REST API ===

    private void startApi(String marketId) {
        api = Javalin.create(config -> config.staticFiles.add(staticFilesConfig -> {
            staticFilesConfig.directory = "ui";
            staticFilesConfig.location = io.javalin.http.staticfiles.Location.EXTERNAL;
        })).start(apiPort);

        api.get("/api/state", ctx -> {
            Map<String, Object> state = new HashMap<>();
            state.put("nodeId", myId);
            state.put("height", consensus.getCurrentHeight());

            List<Map<String, Object>> accounts = new ArrayList<>();
            for (String uid : marginManager.getAllRegisteredUsers()) {
                AccountBalance bal = marginManager.getBalance(uid);
                Position pos = marginManager.getPosition(uid, marketId);
                Map<String, Object> acc = new HashMap<>();
                acc.put("userId", uid);
                acc.put("freeBalance", bal != null ? bal.getFreeBalance() : 0.0);
                acc.put("lockedMargin", bal != null ? bal.getLockedMargin() : 0.0);
                acc.put("positionSize", pos != null ? (pos.isLong() ? pos.getSize() : -pos.getSize()) : 0.0);
                acc.put("entryPrice", pos != null ? pos.getEntryPrice() : 0.0);
                acc.put("unrealizedPnL", pos != null ? pos.calculateUnrealizedPnL(
                        marginManager.getOracleService().getPrice(marketId)) : 0.0);
                accounts.add(acc);
            }
            state.put("accounts", accounts);

            List<Map<String, Object>> blocksList = new ArrayList<>();
            for (L2Block block : consensus.getCommittedChain()) {
                Map<String, Object> b = new HashMap<>();
                b.put("height", block.getHeight());
                b.put("proposer", block.getProposerId());
                b.put("hash", block.getBlockHash());
                b.put("stateRoot", block.getStateRoot());
                b.put("txCount", block.getTransactions().size());
                blocksList.add(b);
            }
            state.put("blocks", blocksList);
            state.put("mempoolSize", mempool.size());

            ctx.json(state);
        });

        api.post("/api/deposit", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String userId = (String) body.get("userId");
            double amount = Double.parseDouble(body.get("amount").toString());

            // Создаём RetryableTicket в ArbitrumBridge (симуляция L1→L2 депозита)
            // В production: пользователь отправляет USDC в L1 Arbitrum контракт,
            // валидаторы читают RetryableTicket из L1→L2 inbox.
            bridge.depositL1(userId, amount);
            bridge.createRetryableTicket(userId, amount);

            ctx.json(Map.of("status", "accepted", "message",
                    "Deposit ticket submitted to Arbitrum bridge. Funds will arrive after bridge processing (~2s)"));
        });

        api.post("/api/withdraw", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String userId = (String) body.get("userId");
            double amount = Double.parseDouble(body.get("amount").toString());
            String signature = (String) body.get("signature");
            if (signature == null || signature.isBlank()) {
                ctx.status(HttpStatus.BAD_REQUEST);
                ctx.json(Map.of("status", "error", "message", "Signature required for withdrawal"));
                return;
            }
            boolean isFast = Boolean.parseBoolean(body.getOrDefault("fast", "false").toString());
            int feeBps = Integer.parseInt(body.getOrDefault("feeBps", "30").toString());

            ChainTransaction.Builder builder = new ChainTransaction.Builder(ChainTransaction.TxType.WITHDRAW_SIGNED)
                    .userId(userId).amount(amount).signature(signature)
                    .isFastWithdraw(isFast).fastFeeBps(feeBps)
                    .timestamp(System.currentTimeMillis());

            if (isFast) {
                // marketId carries the LP beneficiary address for fast withdrawals
                builder.marketId(myId);
            }

            ChainTransaction tx = builder.build();
            mempool.add(tx);

            String msg = isFast
                    ? "Fast withdrawal (LP=" + myId + ", fee=" + feeBps + "bps) submitted to mempool"
                    : "Signed withdrawal submitted to mempool";
            ctx.json(Map.of("status", "accepted", "message", msg));
        });

        // TON Bridge endpoints (Telegram)
        api.post("/api/ton/deposit", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String userId = (String) body.get("userId");
            double amount = Double.parseDouble(body.get("amount").toString());

            // Симулируем отправку USDT из TON Wallet на наш vault
            tonBridge.depositUsdt(userId, amount);
            ctx.json(Map.of("status", "accepted", "message",
                    "USDT deposit submitted to TON bridge. Funds arrive in ~3s"));
        });

        api.post("/api/ton/withdraw", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String userId = (String) body.get("userId");
            double amount = Double.parseDouble(body.get("amount").toString());
            String signature = (String) body.get("signature");

            // Signed withdrawal через консенсус (как и обычный WITHDRAW_SIGNED)
            if (signature == null || signature.isBlank()) {
                ctx.status(HttpStatus.BAD_REQUEST);
                ctx.json(Map.of("status", "error", "message", "Signature required"));
                return;
            }
            ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.WITHDRAW_SIGNED)
                    .userId(userId).amount(amount).signature(signature)
                    .timestamp(System.currentTimeMillis()).build();
            mempool.add(tx);
            ctx.json(Map.of("status", "accepted", "message",
                    "TON withdrawal submitted to mempool. USDT sent in ~1s"));
        });

        api.get("/api/ton/balance", ctx -> {
            String userId = ctx.queryParam("userId");
            if (userId == null) {
                ctx.status(HttpStatus.BAD_REQUEST);
                ctx.json(Map.of("status", "error", "message", "userId query parameter required"));
                return;
            }
            double tonBalance = tonBridge.getTonBalance(userId);
            var l2Balance = marginManager.getBalance(userId);
            ctx.json(Map.of(
                    "userId", userId,
                    "tonBalance", tonBalance,
                    "l2FreeBalance", l2Balance != null ? l2Balance.getFreeBalance() : 0.0,
                    "l2LockedMargin", l2Balance != null ? l2Balance.getLockedMargin() : 0.0
            ));
        });

        api.get("/api/bridge/balance", ctx -> {
            String userId = ctx.queryParam("userId");
            if (userId == null) {
                ctx.status(HttpStatus.BAD_REQUEST);
                ctx.json(Map.of("status", "error", "message", "userId query parameter required"));
                return;
            }
            double l1Balance = bridge.getL1Balance(userId);
            var l2Balance = marginManager.getBalance(userId);
            ctx.json(Map.of(
                    "userId", userId,
                    "l1Balance", l1Balance,
                    "l2FreeBalance", l2Balance != null ? l2Balance.getFreeBalance() : 0.0,
                    "l2LockedMargin", l2Balance != null ? l2Balance.getLockedMargin() : 0.0
            ));
        });

        api.post("/api/order", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String userId = (String) body.get("userId");
            boolean isBuy = Boolean.parseBoolean(body.get("isBuy").toString());
            double price = Double.parseDouble(body.get("price").toString());
            double amount = Double.parseDouble(body.get("amount").toString());
            double leverage = Double.parseDouble(body.get("leverage").toString());
            boolean isIsolated = Boolean.parseBoolean(body.getOrDefault("isIsolated", "true").toString());

            ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                    .orderId(UUID.randomUUID().toString())
                    .userId(userId).marketId(marketId).isBuy(isBuy)
                    .price(price).amount(amount).leverage(leverage).isIsolated(isIsolated)
                    .timestamp(System.currentTimeMillis())
                    .build();
            mempool.add(tx);
            ctx.json(Map.of("status", "accepted", "message", "Order submitted to mempool"));
        });

        api.post("/api/cancel", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String orderId = (String) body.get("orderId");
            String userId = (String) body.get("userId");
            ChainTransaction tx = new ChainTransaction.Builder(ChainTransaction.TxType.CANCEL_ORDER)
                    .orderId(orderId).userId(userId).marketId(marketId).build();
            mempool.add(tx);
            ctx.json(Map.of("status", "accepted", "message", "Cancel submitted to mempool"));
        });

        api.get("/api/orderbook", ctx -> {
            var ob = handler.getOrderBook(marketId);
            Map<String, Object> book = new HashMap<>();
            List<Map<String, Object>> bids = new ArrayList<>();
            List<Map<String, Object>> asks = new ArrayList<>();
            if (ob != null) {
                ob.getBids().forEach((price, orders) -> {
                    double total = orders.stream().mapToDouble(Order::getAmount).sum();
                    if (total > 0.001) bids.add(Map.of("price", price, "amount", total));
                });
                ob.getAsks().forEach((price, orders) -> {
                    double total = orders.stream().mapToDouble(Order::getAmount).sum();
                    if (total > 0.001) asks.add(Map.of("price", price, "amount", total));
                });
            }
            book.put("bids", bids);
            book.put("asks", asks);
            ctx.json(book);
        });

        api.get("/api/trades", ctx -> {
            List<Trade> trades = handler.getExecutedTrades();
            List<Map<String, Object>> mapped = new ArrayList<>();
            for (int i = trades.size() - 1; i >= Math.max(0, trades.size() - 50); i--) {
                Trade t = trades.get(i);
                mapped.add(Map.of(
                        "price", t.getPrice(), "amount", t.getAmount(),
                        "buyer", t.getBuyerId(), "seller", t.getSellerId(),
                        "time", new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(t.getTimestamp()))
                ));
            }
            ctx.json(mapped);
        });

        api.get("/api/peers", ctx -> {
            ctx.json(Map.of("nodeId", myId, "height", consensus.getCurrentHeight(),
                    "mempoolSize", mempool.size(), "blockCount", consensus.getCommittedChain().size()));
        });
    }

    private void publishTxSync(ChainTransaction tx) {
        long seq = ringBuffer.next();
        try {
            ringBuffer.get(seq).setTransaction(tx);
        } finally {
            ringBuffer.publish(seq);
        }
    }

    // === Main ===

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ValidatorNode <nodeId> <apiPort> [dataDir]");
            System.out.println("  nodeId: val-1, val-2, or val-3");
            System.out.println("  apiPort: HTTP port for REST API (e.g. 8001)");
            System.out.println("Environment variables:");
            System.out.println("  PEERS=val-1:8001,val-2:8002,val-3:8003");
            System.exit(1);
        }

        String nodeId = args[0];
        int apiPort = Integer.parseInt(args[1]);
        Path dataDir = Path.of(args.length > 2 ? args[2] : "data-" + nodeId);

        // Parse peer list from env or use defaults
        String peersEnv = System.getenv("PEERS");
        Map<String, Integer> validatorPorts = new LinkedHashMap<>();
        if (peersEnv != null && !peersEnv.isBlank()) {
            for (String peer : peersEnv.split(",")) {
                String[] parts = peer.trim().split(":");
                validatorPorts.put(parts[0], Integer.parseInt(parts[1]));
            }
        } else {
            validatorPorts.put("val-1", 8001);
            validatorPorts.put("val-2", 8002);
            validatorPorts.put("val-3", 8003);
        }

        List<String> allValidatorIds = List.copyOf(validatorPorts.keySet());
        MarketSpecification spec = new MarketSpecification("BTC-USD", "feed-btc", 10.0, 0.05, 0.0, 0.0);

        ValidatorNode node = new ValidatorNode(nodeId, apiPort, dataDir);
        Runtime.getRuntime().addShutdownHook(new Thread(node::stop));

        node.start(allValidatorIds, validatorPorts, spec);
    }
}
