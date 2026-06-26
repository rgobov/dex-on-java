package com.example.dex.server;

import com.example.dex.bridge.L1Vault;
import com.example.dex.bridge.L2Bridge;
import com.example.dex.cryptography.DexSignatureUtil;
import com.example.dex.disruptor.ChainTxEvent;
import com.example.dex.disruptor.ChainTxEventFactory;
import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.funding.FundingCalculator;
import com.example.dex.margin.LiquidationEngine;
import com.example.dex.margin.MarginManager;
import com.example.dex.models.*;
import com.example.dex.oracle.OracleService;
import com.example.dex.router.RoutingPolicy;
import com.example.dex.router.SmartOrderRouter;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DexServer {

    private static class TradingEngine {
        final MarginManager marginManager;
        final StateExecutionHandler handler;
        final Disruptor<ChainTxEvent> disruptor;
        final RingBuffer<ChainTxEvent> ringBuffer;

        TradingEngine(String marketId, MarketSpecification spec) {
            OracleService oracle = new OracleService();
            oracle.setPrice(marketId, 60000.0);
            this.marginManager = new MarginManager(oracle);
            this.marginManager.registerMarket(spec);
            this.handler = new StateExecutionHandler(
                    marginManager,
                    new LiquidationEngine(marginManager, oracle),
                    new FundingCalculator(marginManager, oracle)
            );
            this.handler.registerMarket(marketId);

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

        void stop() {
            disruptor.shutdown();
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
    private static TradingEngine l2Engine;
    private static TradingEngine smrEngine;
    private static SmartOrderRouter router;
    private static com.example.dex.models.RollupPublisher rollupPublisher;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Инициализация L1/L2 DEX бэкенда ===");

        // 1. Инициализация ключей клиентов (Alice, Bob, Charlie)
        for (String user : new String[]{"Alice", "Bob", "Charlie"}) {
            KeyPair kp = DexSignatureUtil.generateKeyPair();
            clientKeys.put(user, kp);
            clientAddresses.put(user, DexSignatureUtil.encodePublicKey(kp.getPublic()));
        }

        // 2. Инициализация ключей валидаторов (Validator A, B, C)
        for (int i = 0; i < 3; i++) {
            KeyPair kp = DexSignatureUtil.generateKeyPair();
            validatorKeys.add(kp);
            String valAddr = DexSignatureUtil.encodePublicKey(kp.getPublic());
            validatorAddresses.add(valAddr);
            validatorJailStatus.put("Validator " + (char) ('A' + i), false);
        }

        // 3. Создание L1Vault
        vault = new L1Vault(validatorAddresses);

        // Даем начальный баланс на L1 для Алисы, Боба и Чарли
        vault.deposit(clientAddresses.get("Alice"), 20000.0);
        vault.deposit(clientAddresses.get("Bob"), 15000.0);
        vault.deposit(clientAddresses.get("Charlie"), 10000.0);

        // 4. Запуск торговых движков L2
        l2Engine = new TradingEngine(MARKET_ID, MARKET_SPEC);
        smrEngine = new TradingEngine(MARKET_ID, MARKET_SPEC);

        // Регистрируем пользователей на L2 движках
        for (String addr : clientAddresses.values()) {
            l2Engine.marginManager.registerUser(addr, 0.0);
            smrEngine.marginManager.registerUser(addr, 0.0);
        }

        // 5. Запуск роутера и моста
        router = new SmartOrderRouter(
                smrEngine.ringBuffer, smrEngine.handler,
                l2Engine.ringBuffer, l2Engine.handler
        );
        bridge = new L2Bridge(vault, router);

        // Синхронизируем начальные депозиты в L2
        bridge.syncDeposits();

        // 6. Инициализация и запуск RollupPublisher
        rollupPublisher = new com.example.dex.models.RollupPublisher(l2Engine.handler, vault);
        rollupPublisher.start();

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (rollupPublisher != null) rollupPublisher.stop();
            if (l2Engine != null) l2Engine.stop();
            if (smrEngine != null) smrEngine.stop();
        }));

        // Запуск Javalin
        Javalin app = Javalin.create(config -> {
            config.router.apiBuilder(() -> {});
            // Раздаем статику из ui/ внешнего каталога
            config.staticFiles.add(staticFilesConfig -> {
                staticFilesConfig.directory = "ui";
                staticFilesConfig.location = io.javalin.http.staticfiles.Location.EXTERNAL;
            });
        }).start(8000);

        // --- HTTP REST API Endpoints ---

        // GET /api/state: Получить полное состояние системы
        app.get("/api/state", ctx -> {
            Map<String, Object> state = new HashMap<>();

            // Общий сейф L1
            state.put("vaultL1Balance", vault.getTotalVaultBalance());

            // Состояние счетов
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (String name : new String[]{"Alice", "Bob", "Charlie"}) {
                String addr = clientAddresses.get(name);
                Map<String, Object> acc = new HashMap<>();
                acc.put("name", name);
                acc.put("address", addr);
                acc.put("l1Balance", vault.getLedgerState().getBalance(addr));

                // Секвенсор L2 баланс и позиции
                AccountBalance balL2 = l2Engine.marginManager.getBalance(addr);
                Position posL2 = l2Engine.marginManager.getPosition(addr, MARKET_ID);
                acc.put("l2FreeBalance", balL2 != null ? balL2.getFreeBalance() : 0.0);
                acc.put("l2LockedMargin", balL2 != null ? balL2.getLockedMargin() : 0.0);
                acc.put("l2PositionSize", posL2 != null ? (posL2.isLong() ? posL2.getSize() : -posL2.getSize()) : 0.0);
                acc.put("l2EntryPrice", posL2 != null ? posL2.getEntryPrice() : 0.0);

                // SMR L2 баланс и позиции
                AccountBalance balSMR = smrEngine.marginManager.getBalance(addr);
                Position posSMR = smrEngine.marginManager.getPosition(addr, MARKET_ID);
                acc.put("smrFreeBalance", balSMR != null ? balSMR.getFreeBalance() : 0.0);
                acc.put("smrLockedMargin", balSMR != null ? balSMR.getLockedMargin() : 0.0);
                acc.put("smrPositionSize", posSMR != null ? (posSMR.isLong() ? posSMR.getSize() : -posSMR.getSize()) : 0.0);
                acc.put("smrEntryPrice", posSMR != null ? posSMR.getEntryPrice() : 0.0);

                accounts.add(acc);
            }
            state.put("accounts", accounts);

            // Состояние валидаторов
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

            // Блоки L1
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

            // Добавляем список опубликованных батчей роллапа
            List<Map<String, Object>> rollupsList = new ArrayList<>();
            for (com.example.dex.models.RollupBatch batch : vault.getRollupBatches()) {
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

        // GET /api/rollups: Получить все опубликованные батчи роллапа
        app.get("/api/rollups", ctx -> {
            ctx.json(vault.getRollupBatches());
        });

        // POST /api/deposit: Депозит на L2 через мост
        app.post("/api/deposit", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String user = (String) body.get("user");
            double amount = Double.parseDouble(body.get("amount").toString());

            String addr = clientAddresses.get(user);
            if (addr == null) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Неизвестный пользователь");
                return;
            }

            // Вызываем депозит на L1
            vault.deposit(addr, amount);

            // Синхронизируем на L2
            bridge.syncDeposits();

            // Даем немного времени Disruptor обработать транзакции
            Thread.sleep(50);

            ctx.json(Map.of("status", "success", "message", "Депозит успешно зачислен"));
        });

        // POST /api/withdraw: BFT вывод средств на L1
        app.post("/api/withdraw", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String user = (String) body.get("user");
            double amount = Double.parseDouble(body.get("amount").toString());

            // 1. Проверяем кворум валидаторов (надо >= 2 активных)
            long activeCount = validatorJailStatus.values().stream().filter(j -> !j).count();
            if (activeCount < 2) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                        "status", "failed",
                        "message", "BFT Консенсус недоступен! Активно " + activeCount + " из 3 валидаторов SMR (требуется >= 2/3)."
                ));
                return;
            }

            String addr = clientAddresses.get(user);
            KeyPair keys = clientKeys.get(user);

            // 2. Списываем с L2 (мы спишем как на Секвенсоре, так и на SMR)
            ChainTransaction txWithdraw = new ChainTransaction.Builder(ChainTransaction.TxType.WITHDRAW)
                    .userId(addr)
                    .amount(amount)
                    .timestamp(System.currentTimeMillis())
                    .build();

            router.routeOrder(txWithdraw, RoutingPolicy.BEST_EXECUTION);
            Thread.sleep(50); // Даем обработаться

            // 3. Собираем подписи валидаторов L2 для L1 моста
            List<String> signatures = new ArrayList<>();
            String withdrawMsg = "WITHDRAW:" + addr + ":" + amount;
            for (int i = 0; i < 3; i++) {
                String name = "Validator " + (char) ('A' + i);
                if (!validatorJailStatus.get(name)) {
                    // Подписываем приватным ключом валидатора
                    signatures.add(DexSignatureUtil.sign(withdrawMsg, validatorKeys.get(i).getPrivate()));
                }
            }

            // 4. Подпись самого пользователя
            String userSig = DexSignatureUtil.sign(withdrawMsg, keys.getPrivate());

            // 5. Отправляем в L1 мост
            boolean success = vault.withdraw(addr, amount, userSig, signatures);
            if (success) {
                ctx.json(Map.of("status", "success", "message", "Вывод средств выполнен успешно"));
            } else {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("status", "failed", "message", "Сбой транзакции консенсуса L1"));
            }
        });

        // POST /api/order: Выставить торговый ордер (мэтчинг через Disruptor)
        app.post("/api/order", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String user = body.get("user").toString();
            double price = Double.parseDouble(body.get("price").toString());
            double amount = Double.parseDouble(body.get("amount").toString());
            double leverage = Double.parseDouble(body.get("leverage").toString());
            boolean isBuy = Boolean.parseBoolean(body.get("isBuy").toString());
            String path = body.get("routingPolicy").toString(); // "L2" or "SMR" or "SOR"

            String addr = clientAddresses.get(user);
            KeyPair keys = clientKeys.get(user);

            // Проверим кворум SMR если маршрут принудительно SMR
            if ("SMR".equals(path)) {
                long activeCount = validatorJailStatus.values().stream().filter(j -> !j).count();
                if (activeCount < 2) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                            "status", "failed",
                            "message", "Ошибка SMR: консенсус недоступен. Менее 2/3 активных нод!"
                    ));
                    return;
                }
            }

            // Создаем ордер
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

            // Определяем политику роутинга
            RoutingPolicy policy = RoutingPolicy.BEST_EXECUTION;
            if ("L2".equals(path)) {
                policy = RoutingPolicy.FORCE_L2;
            } else if ("SMR".equals(path)) {
                policy = RoutingPolicy.FORCE_SMR;
            }

            router.routeOrder(orderTx, policy);
            Thread.sleep(50); // Ждем мэтчинг

            ctx.json(Map.of("status", "success", "message", "Ордер отправлен в обработку"));
        });

        // POST /api/jail: Тюрьма и штраф (Slashing) валидаторов
        app.post("/api/jail", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            String action = (String) body.get("action"); // "jail" or "unjail"

            boolean jail = "jail".equals(action);
            validatorJailStatus.put(name, jail);

            int index = name.charAt(name.length() - 1) - 'A';
            String addr = validatorAddresses.get(index);

            if (jail) {
                // Выполняем Slashing на L1: списываем 20% стейка валидатора
                vault.getLedgerState().slashStake(addr, 0.20);
                System.out.println("[L1_SLASH] Валидатор " + name + " оштрафован на 20% своего стейка на L1!");
            } else {
                // Восстановление стейка при разджайле (покупается обратно для симулятора)
                vault.getLedgerState().registerBootstrapStake(addr, 10000.0);
            }

            ctx.json(Map.of("status", "success", "message", "Статус валидатора " + name + " обновлен"));
        });

        // GET /api/orderbook: Получить текущий стакан
        app.get("/api/orderbook", ctx -> {
            // Берем стакан из быстрого Секвенсора L2 (он самый актуальный)
            var ob = l2Engine.handler.getOrderBook(MARKET_ID);
            Map<String, Object> book = new HashMap<>();

            List<Map<String, Object>> bids = new ArrayList<>();
            List<Map<String, Object>> asks = new ArrayList<>();

            if (ob != null) {
                // Читаем Bid
                ob.getBids().forEach((price, orders) -> {
                    double totalAmount = orders.stream().mapToDouble(Order::getAmount).sum();
                    if (totalAmount > 0.001) {
                        bids.add(Map.of("price", price, "amount", totalAmount, "type", "L2"));
                    }
                });

                // Читаем Ask
                ob.getAsks().forEach((price, orders) -> {
                    double totalAmount = orders.stream().mapToDouble(Order::getAmount).sum();
                    if (totalAmount > 0.001) {
                        asks.add(Map.of("price", price, "amount", totalAmount, "type", "L2"));
                    }
                });
            }

            book.put("bids", bids);
            book.put("asks", asks);
            ctx.json(book);
        });

        // GET /api/trades: Получить список последних сделок
        app.get("/api/trades", ctx -> {
            // Считываем список сделок, совершенных в L2 движке
            List<Trade> trades = l2Engine.handler.getExecutedTrades();
            List<Map<String, Object>> mapped = new ArrayList<>();

            // Возвращаем в обратном хронологическом порядке (последние первыми)
            for (int i = trades.size() - 1; i >= Math.max(0, trades.size() - 50); i--) {
                Trade t = trades.get(i);
                Map<String, Object> item = new HashMap<>();
                item.put("time", new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(t.getTimestamp())));
                item.put("price", t.getPrice());
                item.put("amount", t.getAmount());
                item.put("side", t.isBuyerIsolated() ? "buy" : "sell"); // Упрощенный сайд
                item.put("type", "L2");
                
                // Найдем имена по адресам публичных ключей
                item.put("maker", getNameByAddress(t.getSellerId()));
                item.put("taker", getNameByAddress(t.getBuyerId()));
                mapped.add(item);
            }

            ctx.json(mapped);
        });

        System.out.println("Javalin-сервер успешно запущен на порту 8000!");
        System.out.println("Откройте браузер на: http://localhost:8000");
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
