package com.example.dex.server;

import com.example.dex.bridge.ArbitrumBridge;
import com.example.dex.bridge.L1Vault;
import com.example.dex.bridge.L2Bridge;
import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
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
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dev-only single-node server. NOT for production use.
 * <p>
 * Для production используйте {@link com.example.dex.server.ValidatorNode}
 * с PBFT кластером из 3 валидаторов и Arbitrum Bridge.
 * <p>
 * WARNING: Единый сервер создаёт регуляторный риск (нелицензированная биржевая деятельность).
 * Данный класс предназначен исключительно для локальной разработки и отладки.
 */
@Deprecated(since = "1.0", forRemoval = false)
public final class DexServer {

    private static class ExecutionEngine {
        final MarginManager marginManager;
        final StateExecutionHandler handler;
        final Disruptor<ChainTxEvent> disruptor;
        final RingBuffer<ChainTxEvent> ringBuffer;
        final FlatFileStore flatFileStore;
        final OracleService oracle;
        long txCounter = 0;

        ExecutionEngine(String marketId, MarketSpecification spec, Path dataDir) throws Exception {
            this.oracle = new OracleService();
            oracle.setPrice(marketId, 60000.0);
            this.marginManager = new MarginManager(oracle);
            this.marginManager.registerMarket(spec);
            this.handler = new StateExecutionHandler(
                    marginManager,
                    new LiquidationEngine(marginManager, oracle),
                    new FundingCalculator(marginManager, oracle)
            );
            this.handler.registerMarket(marketId);

            this.flatFileStore = new FlatFileStore(dataDir.resolve("l3"));
            this.flatFileStore.init();

            if (flatFileStore.hasSnapshot()) {
                System.out.println("[PERSIST] Snapshot found, recovering state...");
                FlatFileStore.SnapshotData snapshot = flatFileStore.loadSnapshot();
                if (snapshot != null) {
                    snapshot.restoreInto(handler, marginManager, oracle);
                    txCounter = snapshot.sequence;
                    System.out.println("[PERSIST] State restored from snapshot (seq=" + snapshot.sequence + ")");

                    List<ChainTransaction> walTxs = flatFileStore.replayAllFromWal();
                    for (ChainTransaction tx : walTxs) {
                        publishTxSync(tx);
                        txCounter++;
                    }
                    System.out.println("[PERSIST] Replayed " + walTxs.size() + " WAL transactions after snapshot");
                }
            } else {
                System.out.println("[PERSIST] No snapshot found, starting fresh");
            }

            this.disruptor = new Disruptor<>(
                    new ChainTxEventFactory(),
                    1024,
                    DaemonThreadFactory.INSTANCE,
                    ProducerType.MULTI,
                    new YieldingWaitStrategy()
            );
            this.disruptor.handleEventsWith(handler);
            this.ringBuffer = this.disruptor.start();
        }

        void stop() throws Exception {
            disruptor.shutdown();
            flatFileStore.saveSnapshot(txCounter, handler, marginManager, oracle);
            flatFileStore.truncateWal();
            System.out.println("[PERSIST] Snapshot saved on shutdown (seq=" + txCounter + ")");
        }

        void publishTxSync(ChainTransaction tx) {
            long seq = ringBuffer.next();
            try {
                ringBuffer.get(seq).setTransaction(tx);
            } finally {
                ringBuffer.publish(seq);
            }
        }
    }

    // --- State and Config ---
    private static final String MARKET_ID = "BTC-USD";
    private static final MarketSpecification MARKET_SPEC = new MarketSpecification(MARKET_ID, "feed-btc", 10.0, 0.05, 0.0, 0.0);

    private static final Map<String, KeyPair> clientKeys = new ConcurrentHashMap<>();
    private static final Map<String, String> clientAddresses = new ConcurrentHashMap<>();

    private static final List<KeyPair> validatorKeys = new CopyOnWriteArrayList<>();
    private static final List<String> validatorAddresses = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> validatorJailStatus = new ConcurrentHashMap<>();

    private static L1Vault vault;
    private static L2Bridge bridge;
    private static ExecutionEngine engine;
    private static RollupPublisher rollupPublisher;
    private static ScheduledExecutorService snapshotTimer;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Инициализация DEX ===");
        System.out.println("███████████████████████████████████████████████████████████████████████████████████");
        System.out.println("█  WARNING: DexServer — SINGLE-NODE DEV SERVER.                                  █");
        System.out.println("█  Не используйте в production. Единый сервер создаёт регуляторный риск.         █");
        System.out.println("█  Для production используйте ValidatorNode с PBFT кластером из 3 валидаторов.   █");
        System.out.println("███████████████████████████████████████████████████████████████████████████████████");

        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");

        for (String user : new String[]{"Alice", "Bob", "Charlie"}) {
            KeyPair kp = DexSignatureUtil.generateKeyPair();
            clientKeys.put(user, kp);
            clientAddresses.put(user, DexSignatureUtil.encodePublicKey(kp.getPublic()));
        }

        for (int i = 0; i < 3; i++) {
            KeyPair kp = DexSignatureUtil.generateKeyPair();
            validatorKeys.add(kp);
            String valAddr = DexSignatureUtil.encodePublicKey(kp.getPublic());
            validatorAddresses.add(valAddr);
            validatorJailStatus.put("Validator " + (char) ('A' + i), false);
        }

        vault = new L1Vault(validatorAddresses);
        vault.deposit(clientAddresses.get("Alice"), 20000.0);
        vault.deposit(clientAddresses.get("Bob"), 15000.0);
        vault.deposit(clientAddresses.get("Charlie"), 10000.0);

        engine = new ExecutionEngine(MARKET_ID, MARKET_SPEC, dataDir);
        for (String addr : clientAddresses.values()) {
            engine.marginManager.registerUser(addr, 0.0);
        }

        bridge = new L2Bridge(vault, engine.ringBuffer);
        bridge.syncDeposits();

        // Use ArbitrumBridge for rollup publishing (DexServer also gets one for testing)
        ArbitrumBridge arbitrumBridge = new ArbitrumBridge(30_000);
        arbitrumBridge.start();
        rollupPublisher = new RollupPublisher(engine.handler, arbitrumBridge);
        rollupPublisher.start();

        snapshotTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-timer");
            t.setDaemon(true);
            return t;
        });
        snapshotTimer.scheduleAtFixedRate(() -> {
            try {
                engine.flatFileStore.saveSnapshot(
                        engine.txCounter, engine.handler, engine.marginManager, engine.oracle);
                engine.flatFileStore.truncateWal();
                System.out.println("[PERSIST] Periodic snapshot saved (seq=" + engine.txCounter + ")");
            } catch (Exception e) {
                System.err.println("[PERSIST] Snapshot error: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (rollupPublisher != null) rollupPublisher.stop();
            if (snapshotTimer != null) snapshotTimer.shutdown();
            if (engine != null) {
                try { engine.stop(); } catch (Exception e) {
                    System.err.println("[PERSIST] Shutdown error: " + e.getMessage());
                }
            }
        }));

        Javalin app = Javalin.create(config -> {
            config.router.apiBuilder(() -> {});
            config.staticFiles.add(staticFilesConfig -> {
                staticFilesConfig.directory = "ui";
                staticFilesConfig.location = io.javalin.http.staticfiles.Location.EXTERNAL;
            });
        }).start(8000);

        app.get("/api/state", ctx -> {
            Map<String, Object> state = new HashMap<>();
            state.put("vaultL1Balance", vault.getTotalVaultBalance());

            List<Map<String, Object>> accounts = new ArrayList<>();
            for (String name : new String[]{"Alice", "Bob", "Charlie"}) {
                String addr = clientAddresses.get(name);
                Map<String, Object> acc = new HashMap<>();
                acc.put("name", name);
                acc.put("address", addr);
                acc.put("l1Balance", vault.getLedgerState().getBalance(addr));

                AccountBalance bal = engine.marginManager.getBalance(addr);
                Position pos = engine.marginManager.getPosition(addr, MARKET_ID);
                acc.put("l2FreeBalance", bal != null ? bal.getFreeBalance() : 0.0);
                acc.put("l2LockedMargin", bal != null ? bal.getLockedMargin() : 0.0);
                acc.put("l2PositionSize", pos != null ? (pos.isLong() ? pos.getSize() : -pos.getSize()) : 0.0);
                acc.put("l2EntryPrice", pos != null ? pos.getEntryPrice() : 0.0);

                accounts.add(acc);
            }
            state.put("accounts", accounts);

            List<Map<String, Object>> validators = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String name = "Validator " + (char) ('A' + i);
                String addr = validatorAddresses.get(i);
                boolean jailed = validatorJailStatus.get(name);
                Map<String, Object> v = new HashMap<>();
                v.put("name", name);
                v.put("address", addr);
                v.put("stake", vault.getLedgerState().getStake(addr));
                v.put("status", jailed ? "Jailed" : "Active");
                validators.add(v);
            }
            state.put("validators", validators);

            List<Map<String, Object>> blocks = new ArrayList<>();
            List<com.example.dex.l1.models.L1Block> chain = vault.getConsensus().getBlocks();
            for (int i = chain.size() - 1; i >= Math.max(0, chain.size() - 5); i--) {
                com.example.dex.l1.models.L1Block block = chain.get(i);
                Map<String, Object> b = new HashMap<>();
                b.put("height", block.getBlockNumber());
                b.put("proposer", validatorAddresses.contains(block.getProposer()) ? "Validator" : "Admin");
                b.put("hash", block.getBlockHash());
                String typeStr = "GENESIS";
                if (!block.getTransactions().isEmpty()) {
                    typeStr = block.getTransactions().get(0).getType().name();
                }
                b.put("txType", typeStr);
                blocks.add(b);
            }
            state.put("l1Blocks", blocks);

            List<Map<String, Object>> rollupsList = new ArrayList<>();
            for (RollupBatch batch : vault.getRollupBatches()) {
                Map<String, Object> rb = new HashMap<>();
                rb.put("batchId", batch.getBatchId());
                rb.put("tradesCount", batch.getTrades().size());
                rb.put("prevStateRoot", batch.getPrevStateRoot());
                rb.put("stateRoot", batch.getStateRoot());
                rb.put("timestamp", batch.getTimestamp());
                rollupsList.add(rb);
            }
            state.put("rollupBatches", rollupsList);

            ctx.json(state);
        });

        app.get("/api/rollups", ctx -> {
            ctx.json(vault.getRollupBatches());
        });

        app.post("/api/deposit", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String user = (String) body.get("user");
            double amount = Double.parseDouble(body.get("amount").toString());

            String addr = clientAddresses.get(user);
            if (addr == null) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Неизвестный пользователь");
                return;
            }

            vault.deposit(addr, amount);
            bridge.syncDeposits();
            Thread.sleep(50);

            ctx.json(Map.of("status", "success", "message", "Депозит успешно зачислен"));
        });

        app.post("/api/withdraw", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String user = (String) body.get("user");
            double amount = Double.parseDouble(body.get("amount").toString());

            long activeCount = validatorJailStatus.values().stream().filter(j -> !j).count();
            if (activeCount < 2) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                        "status", "failed",
                        "message", "BFT Консенсус недоступен! Активно " + activeCount + " из 3 валидаторов (требуется >= 2/3)."
                ));
                return;
            }

            String addr = clientAddresses.get(user);
            KeyPair keys = clientKeys.get(user);

            ChainTransaction txWithdraw = new ChainTransaction.Builder(ChainTransaction.TxType.WITHDRAW)
                    .userId(addr)
                    .amount(amount)
                    .timestamp(System.currentTimeMillis())
                    .build();
            publishTransaction(txWithdraw);
            Thread.sleep(50);

            List<String> signatures = new ArrayList<>();
            String withdrawMsg = "WITHDRAW:" + addr + ":" + amount;
            for (int i = 0; i < 3; i++) {
                String name = "Validator " + (char) ('A' + i);
                if (!validatorJailStatus.get(name)) {
                    signatures.add(DexSignatureUtil.sign(withdrawMsg, validatorKeys.get(i).getPrivate()));
                }
            }

            String userSig = DexSignatureUtil.sign(withdrawMsg, keys.getPrivate());

            boolean success = vault.withdraw(addr, amount, userSig, signatures);
            if (success) {
                ctx.json(Map.of("status", "success", "message", "Вывод средств выполнен успешно"));
            } else {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("status", "failed", "message", "Сбой транзакции консенсуса L1"));
            }
        });

        app.post("/api/order", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String user = body.get("user").toString();
            double price = Double.parseDouble(body.get("price").toString());
            double amount = Double.parseDouble(body.get("amount").toString());
            double leverage = Double.parseDouble(body.get("leverage").toString());
            boolean isBuy = Boolean.parseBoolean(body.get("isBuy").toString());

            String addr = clientAddresses.get(user);
            KeyPair keys = clientKeys.get(user);

            String orderMsg = "PLACE_ORDER:" + (isBuy ? "buy" : "sell") + ":" + addr + ":" + MARKET_ID + ":" + price + ":" + amount;
            String signature = DexSignatureUtil.sign(orderMsg, keys.getPrivate());

            ChainTransaction orderTx = new ChainTransaction.Builder(ChainTransaction.TxType.PLACE_ORDER)
                    .orderId(UUID.randomUUID().toString())
                    .userId(addr)
                    .marketId(MARKET_ID)
                    .isBuy(isBuy)
                    .price(price)
                    .amount(amount)
                    .leverage(leverage)
                    .isIsolated(true)
                    .signature(signature)
                    .timestamp(System.currentTimeMillis())
                    .build();

            publishTransaction(orderTx);
            Thread.sleep(50);

            ctx.json(Map.of("status", "success", "message", "Ордер отправлен в обработку"));
        });

        app.post("/api/jail", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            String action = (String) body.get("action");

            boolean jail = "jail".equals(action);
            validatorJailStatus.put(name, jail);

            int index = name.charAt(name.length() - 1) - 'A';
            String addr = validatorAddresses.get(index);

            if (jail) {
                vault.getLedgerState().slashStake(addr, 0.20);
                System.out.println("[L1_SLASH] Валидатор " + name + " оштрафован на 20% своего стейка на L1!");
            } else {
                vault.getLedgerState().registerBootstrapStake(addr, 10000.0);
            }

            ctx.json(Map.of("status", "success", "message", "Статус валидатора " + name + " обновлен"));
        });

        app.get("/api/orderbook", ctx -> {
            var ob = engine.handler.getOrderBook(MARKET_ID);
            Map<String, Object> book = new HashMap<>();

            List<Map<String, Object>> bids = new ArrayList<>();
            List<Map<String, Object>> asks = new ArrayList<>();

            if (ob != null) {
                ob.getBids().forEach((priceLevel, orders) -> {
                    double totalAmount = orders.stream().mapToDouble(Order::getAmount).sum();
                    if (totalAmount > 0.001) {
                        bids.add(Map.of("price", priceLevel, "amount", totalAmount, "type", "L2"));
                    }
                });

                ob.getAsks().forEach((priceLevel, orders) -> {
                    double totalAmount = orders.stream().mapToDouble(Order::getAmount).sum();
                    if (totalAmount > 0.001) {
                        asks.add(Map.of("price", priceLevel, "amount", totalAmount, "type", "L2"));
                    }
                });
            }

            book.put("bids", bids);
            book.put("asks", asks);
            ctx.json(book);
        });

        app.get("/api/trades", ctx -> {
            List<Trade> trades = engine.handler.getExecutedTrades();
            List<Map<String, Object>> mapped = new ArrayList<>();

            for (int i = trades.size() - 1; i >= Math.max(0, trades.size() - 50); i--) {
                Trade t = trades.get(i);
                Map<String, Object> item = new HashMap<>();
                item.put("time", new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(t.getTimestamp())));
                item.put("price", t.getPrice());
                item.put("amount", t.getAmount());
                item.put("side", t.isBuyerIsolated() ? "buy" : "sell");
                item.put("type", "L3");
                item.put("maker", getNameByAddress(t.getSellerId()));
                item.put("taker", getNameByAddress(t.getBuyerId()));
                mapped.add(item);
            }

            ctx.json(mapped);
        });

        System.out.println("Javalin-сервер успешно запущен на порту 8000!");
        System.out.println("Откройте браузер на: http://localhost:8000");
    }

    private static void publishTransaction(ChainTransaction tx) throws Exception {
        engine.flatFileStore.appendToWal(tx);
        long sequence = engine.ringBuffer.next();
        try {
            engine.ringBuffer.get(sequence).setTransaction(tx);
        } finally {
            engine.ringBuffer.publish(sequence);
        }
        engine.txCounter++;
    }

    private static String getNameByAddress(String address) {
        for (Map.Entry<String, String> entry : clientAddresses.entrySet()) {
            if (entry.getValue().equals(address)) {
                return entry.getKey();
            }
        }
        return "Market Maker";
    }
}
