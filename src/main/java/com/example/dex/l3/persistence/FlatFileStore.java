package com.example.dex.l3.persistence;

import com.example.dex.disruptor.StateExecutionHandler;
import com.example.dex.margin.MarginManager;
import com.example.dex.matching.OrderBook;
import com.example.dex.models.*;
import com.example.dex.oracle.OracleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class FlatFileStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dataDir;
    private final Path walFile;
    private final Path snapshotFile;

    public FlatFileStore(Path dataDir) {
        this.dataDir = dataDir;
        this.walFile = dataDir.resolve("wal.jsonl");
        this.snapshotFile = dataDir.resolve("snapshot.json");
    }

    public void init() throws IOException {
        Files.createDirectories(dataDir);
    }

    public boolean hasSnapshot() {
        return Files.exists(snapshotFile);
    }

    // === WAL operations ===

    public synchronized void appendToWal(ChainTransaction tx) throws IOException {
        String json = MAPPER.writeValueAsString(tx) + System.lineSeparator();
        Files.writeString(walFile, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized long walSize() throws IOException {
        if (!Files.exists(walFile)) return 0;
        return Files.size(walFile);
    }

    // === Snapshot operations ===

    public synchronized void saveSnapshot(long seq, StateExecutionHandler handler,
                                           MarginManager marginManager, OracleService oracleService) throws IOException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sequence", seq);

        snapshot.put("balances", buildBalancesList(marginManager));
        snapshot.put("positions", buildPositionsList(marginManager));
        snapshot.put("markets", buildMarketsList(marginManager));
        snapshot.put("oraclePrices", oracleService.getAllPrices());
        snapshot.put("orderBooks", buildOrderBooksList(handler));
        snapshot.put("trades", buildTradesList(handler));
        snapshot.put("processedBridgeTicketIds", List.copyOf(handler.getProcessedBridgeTxIds()));
        snapshot.put("pendingWithdrawals", buildPendingWithdrawalsList(handler));

        String json = MAPPER.writeValueAsString(snapshot);
        Files.writeString(snapshotFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private List<Map<String, Object>> buildPendingWithdrawalsList(StateExecutionHandler handler) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var pw : handler.getPendingWithdrawals()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requestId", pw.requestId);
            m.put("userId", pw.userId);
            m.put("amount", pw.amount);
            m.put("timestamp", pw.timestamp);
            list.add(m);
        }
        return list;
    }

    public SnapshotData loadSnapshot() throws IOException {
        if (!Files.exists(snapshotFile)) {
            return null;
        }
        Map<String, Object> data = MAPPER.readValue(snapshotFile.toFile(), new TypeReference<>() {});
        return new SnapshotData(data);
    }

    public List<ChainTransaction> replayAllFromWal() throws IOException {
        if (!Files.exists(walFile)) return List.of();
        String content = Files.readString(walFile);
        return content.lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return MAPPER.readValue(line, ChainTransaction.class);
                    } catch (IOException e) {
                        throw new RuntimeException("WAL read error", e);
                    }
                })
                .collect(Collectors.toList());
    }

    public synchronized void truncateWal() throws IOException {
        Files.writeString(walFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // === Build helpers for snapshot ===

    private List<Map<String, Object>> buildBalancesList(MarginManager mm) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String uid : mm.getAllRegisteredUsers()) {
            AccountBalance b = mm.getBalance(uid);
            if (b != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", uid);
                m.put("freeBalance", b.getFreeBalance());
                m.put("lockedMargin", b.getLockedMargin());
                list.add(m);
            }
        }
        return list;
    }

    private List<Map<String, Object>> buildPositionsList(MarginManager mm) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String uid : mm.getAllRegisteredUsers()) {
            Map<String, Position> userPositions = mm.getUserPositions(uid);
            if (userPositions != null) {
                for (Map.Entry<String, Position> e : userPositions.entrySet()) {
                    Position p = e.getValue();
                    if (p.getSize() > 0) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("userId", uid);
                        m.put("marketId", e.getKey());
                        m.put("isLong", p.isLong());
                        m.put("size", p.getSize());
                        m.put("entryPrice", p.getEntryPrice());
                        m.put("margin", p.getMargin());
                        m.put("leverage", p.getLeverage());
                        m.put("isIsolated", p.isIsolated());
                        list.add(m);
                    }
                }
            }
        }
        return list;
    }

    private List<Map<String, Object>> buildMarketsList(MarginManager mm) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String mid : mm.getRegisteredMarkets()) {
            MarketSpecification spec = mm.getMarketSpec(mid);
            if (spec != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("marketId", mid);
                m.put("oracleFeedId", spec.getOracleFeedId());
                m.put("maxLeverage", spec.getMaxLeverage());
                m.put("maintenanceMarginRate", spec.getMaintenanceMarginRate());
                m.put("makerFeeRate", spec.getMakerFeeRate());
                m.put("takerFeeRate", spec.getTakerFeeRate());
                m.put("fundingRate", spec.getFundingRate());
                list.add(m);
            }
        }
        return list;
    }

    private List<Map<String, Object>> buildOrderBooksList(StateExecutionHandler handler) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String mid : handler.getRegisteredMarkets()) {
            OrderBook ob = handler.getOrderBook(mid);
            if (ob != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("marketId", mid);

                List<Map<String, Object>> bidsList = new ArrayList<>();
                for (Map.Entry<Double, LinkedList<Order>> level : ob.getBids().entrySet()) {
                    for (Order o : level.getValue()) {
                        bidsList.add(orderToMap(o));
                    }
                }
                m.put("bids", bidsList);

                List<Map<String, Object>> asksList = new ArrayList<>();
                for (Map.Entry<Double, LinkedList<Order>> level : ob.getAsks().entrySet()) {
                    for (Order o : level.getValue()) {
                        asksList.add(orderToMap(o));
                    }
                }
                m.put("asks", asksList);

                list.add(m);
            }
        }
        return list;
    }

    private List<Map<String, Object>> buildTradesList(StateExecutionHandler handler) {
        return handler.getExecutedTrades().stream()
                .map(this::tradeToMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> orderToMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getOrderId());
        m.put("userId", o.getUserId());
        m.put("marketId", o.getMarketId());
        m.put("isBuy", o.isBuy());
        m.put("price", o.getPrice());
        m.put("amount", o.getAmount());
        m.put("remainingAmount", o.getRemainingAmount());
        m.put("isLimit", o.isLimit());
        m.put("leverage", o.getLeverage());
        m.put("isIsolated", o.isIsolated());
        m.put("timestamp", o.getTimestamp());
        m.put("signature", o.getSignature());
        return m;
    }

    private Map<String, Object> tradeToMap(Trade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buyerId", t.getBuyerId());
        m.put("sellerId", t.getSellerId());
        m.put("marketId", t.getMarketId());
        m.put("price", t.getPrice());
        m.put("amount", t.getAmount());
        m.put("buyerLeverage", t.getBuyerLeverage());
        m.put("buyerIsolated", t.isBuyerIsolated());
        m.put("sellerLeverage", t.getSellerLeverage());
        m.put("sellerIsolated", t.isSellerIsolated());
        m.put("timestamp", t.getTimestamp());
        return m;
    }

    // === Snapshot data holder with reconstruction ===

    public static class SnapshotData {
        public final long sequence;
        public final List<BalanceEntry> balances;
        public final List<PositionEntry> positions;
        public final List<MarketEntry> markets;
        public final Map<String, Double> oraclePrices;
        public final List<OrderBookEntry> orderBooks;
        public final List<TradeEntry> trades;
        public final java.util.Set<String> processedBridgeTicketIds;
        public final List<PendingWithdrawalEntry> pendingWithdrawals;

        @SuppressWarnings("unchecked")
        SnapshotData(Map<String, Object> data) {
            this.sequence = ((Number) data.getOrDefault("sequence", 0)).longValue();

            this.balances = new ArrayList<>();
            for (Map<String, Object> b : (List<Map<String, Object>>) data.getOrDefault("balances", List.of())) {
                balances.add(new BalanceEntry(b));
            }

            this.positions = new ArrayList<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) data.getOrDefault("positions", List.of())) {
                positions.add(new PositionEntry(p));
            }

            this.markets = new ArrayList<>();
            for (Map<String, Object> m : (List<Map<String, Object>>) data.getOrDefault("markets", List.of())) {
                markets.add(new MarketEntry(m));
            }

            this.oraclePrices = (Map<String, Double>) data.getOrDefault("oraclePrices", Map.of());

            this.orderBooks = new ArrayList<>();
            for (Map<String, Object> ob : (List<Map<String, Object>>) data.getOrDefault("orderBooks", List.of())) {
                orderBooks.add(new OrderBookEntry(ob));
            }

            this.trades = new ArrayList<>();
            for (Map<String, Object> t : (List<Map<String, Object>>) data.getOrDefault("trades", List.of())) {
                trades.add(new TradeEntry(t));
            }

            List<String> rawTickets = (List<String>) data.getOrDefault("processedBridgeTicketIds", List.of());
            this.processedBridgeTicketIds = new java.util.HashSet<>(rawTickets);

            this.pendingWithdrawals = new ArrayList<>();
            for (Map<String, Object> pw : (List<Map<String, Object>>) data.getOrDefault("pendingWithdrawals", List.of())) {
                pendingWithdrawals.add(new PendingWithdrawalEntry(pw));
            }
        }

        public AccountBalance toBalance(BalanceEntry e) {
            return new AccountBalance(e.userId, e.freeBalance + e.lockedMargin);
        }

        public Position toPosition(PositionEntry e) {
            return new Position(e.userId, e.marketId, e.isLong, e.size, e.entryPrice, e.margin, e.leverage, e.isIsolated);
        }

        public MarketSpecification toMarket(MarketEntry e) {
            MarketSpecification spec = new MarketSpecification(
                    e.marketId, e.oracleFeedId, e.maxLeverage,
                    e.maintenanceMarginRate, e.makerFeeRate, e.takerFeeRate
            );
            spec.setFundingRate(e.fundingRate);
            return spec;
        }

        public Order toOrder(Map<String, Object> m) {
            return new Order(
                    (String) m.get("orderId"),
                    (String) m.get("userId"),
                    (String) m.get("marketId"),
                    (boolean) m.get("isBuy"),
                    ((Number) m.get("price")).doubleValue(),
                    ((Number) m.get("amount")).doubleValue(),
                    (boolean) m.get("isLimit"),
                    ((Number) m.get("leverage")).doubleValue(),
                    (boolean) m.get("isIsolated"),
                    ((Number) m.get("timestamp")).longValue(),
                    (String) m.getOrDefault("signature", "")
            );
        }

        public Trade toTrade(TradeEntry e) {
            return new Trade(
                    e.buyerId, e.sellerId, e.marketId, e.price, e.amount,
                    e.buyerLeverage, e.buyerIsolated, e.sellerLeverage, e.sellerIsolated, e.timestamp
            );
        }

        public void restoreInto(StateExecutionHandler handler, MarginManager marginManager, OracleService oracleService) {
            for (BalanceEntry b : balances) {
                String uid = b.userId;
                AccountBalance bal = toBalance(b);
                if (marginManager.getBalance(uid) == null) {
                    marginManager.registerUser(uid, b.freeBalance);
                }
                AccountBalance existing = marginManager.getBalance(uid);
                double already = existing.getFreeBalance() + existing.getLockedMargin();
                if (b.freeBalance + b.lockedMargin > already) {
                    existing.deposit((b.freeBalance + b.lockedMargin) - already);
                }
            }

            for (PositionEntry p : positions) {
                Position pos = toPosition(p);
                marginManager.getUserPositions(p.userId).put(p.marketId, pos);
            }

            for (MarketEntry m : markets) {
                if (marginManager.getMarketSpec(m.marketId) == null) {
                    marginManager.registerMarket(toMarket(m));
                }
            }

            for (var entry : oraclePrices.entrySet()) {
                oracleService.setPrice(entry.getKey(), entry.getValue());
            }

            for (OrderBookEntry obe : orderBooks) {
                if (!handler.getRegisteredMarkets().contains(obe.marketId)) {
                    handler.registerMarket(obe.marketId);
                }
                OrderBook ob = handler.getOrderBook(obe.marketId);
                if (ob != null) {
                    for (Map<String, Object> orderMap : obe.bids) {
                        Order o = toOrder(orderMap);
                        o.setRemainingAmount(((Number) orderMap.get("remainingAmount")).doubleValue());
                        ob.getBids().computeIfAbsent(o.getPrice(), k -> new LinkedList<>()).add(o);
                    }
                    for (Map<String, Object> orderMap : obe.asks) {
                        Order o = toOrder(orderMap);
                        o.setRemainingAmount(((Number) orderMap.get("remainingAmount")).doubleValue());
                        ob.getAsks().computeIfAbsent(o.getPrice(), k -> new LinkedList<>()).add(o);
                    }
                }
            }

            for (TradeEntry te : trades) {
                handler.getExecutedTrades().add(toTrade(te));
            }
        }

        // --- Entry types ---

        public static class BalanceEntry {
            public final String userId;
            public final double freeBalance;
            public final double lockedMargin;
            BalanceEntry(Map<String, Object> m) {
                this.userId = (String) m.get("userId");
                this.freeBalance = ((Number) m.get("freeBalance")).doubleValue();
                this.lockedMargin = ((Number) m.get("lockedMargin")).doubleValue();
            }
        }

        public static class PositionEntry {
            public final String userId, marketId;
            public final boolean isLong, isIsolated;
            public final double size, entryPrice, margin, leverage;
            PositionEntry(Map<String, Object> m) {
                this.userId = (String) m.get("userId");
                this.marketId = (String) m.get("marketId");
                this.isLong = (boolean) m.get("isLong");
                this.size = ((Number) m.get("size")).doubleValue();
                this.entryPrice = ((Number) m.get("entryPrice")).doubleValue();
                this.margin = ((Number) m.get("margin")).doubleValue();
                this.leverage = ((Number) m.get("leverage")).doubleValue();
                this.isIsolated = (boolean) m.get("isIsolated");
            }
        }

        public static class MarketEntry {
            public final String marketId, oracleFeedId;
            public final double maxLeverage, maintenanceMarginRate, makerFeeRate, takerFeeRate, fundingRate;
            MarketEntry(Map<String, Object> m) {
                this.marketId = (String) m.get("marketId");
                this.oracleFeedId = (String) m.get("oracleFeedId");
                this.maxLeverage = ((Number) m.get("maxLeverage")).doubleValue();
                this.maintenanceMarginRate = ((Number) m.get("maintenanceMarginRate")).doubleValue();
                this.makerFeeRate = ((Number) m.get("makerFeeRate")).doubleValue();
                this.takerFeeRate = ((Number) m.get("takerFeeRate")).doubleValue();
                this.fundingRate = ((Number) m.get("fundingRate")).doubleValue();
            }
        }

        public static class OrderBookEntry {
            public final String marketId;
            public final List<Map<String, Object>> bids;
            public final List<Map<String, Object>> asks;
            @SuppressWarnings("unchecked")
            OrderBookEntry(Map<String, Object> m) {
                this.marketId = (String) m.get("marketId");
                this.bids = (List<Map<String, Object>>) m.getOrDefault("bids", List.of());
                this.asks = (List<Map<String, Object>>) m.getOrDefault("asks", List.of());
            }
        }

        public static class TradeEntry {
            public final String buyerId, sellerId, marketId;
            public final double price, amount, buyerLeverage, sellerLeverage;
            public final boolean buyerIsolated, sellerIsolated;
            public final long timestamp;
            TradeEntry(Map<String, Object> m) {
                this.buyerId = (String) m.get("buyerId");
                this.sellerId = (String) m.get("sellerId");
                this.marketId = (String) m.get("marketId");
                this.price = ((Number) m.get("price")).doubleValue();
                this.amount = ((Number) m.get("amount")).doubleValue();
                this.buyerLeverage = ((Number) m.get("buyerLeverage")).doubleValue();
                this.sellerLeverage = ((Number) m.get("sellerLeverage")).doubleValue();
                this.buyerIsolated = (boolean) m.get("buyerIsolated");
                this.sellerIsolated = (boolean) m.get("sellerIsolated");
                this.timestamp = ((Number) m.get("timestamp")).longValue();
            }
        }

        public static class PendingWithdrawalEntry {
            public final String requestId, userId;
            public final double amount;
            public final long timestamp;
            PendingWithdrawalEntry(Map<String, Object> m) {
                this.requestId = (String) m.get("requestId");
                this.userId = (String) m.get("userId");
                this.amount = ((Number) m.get("amount")).doubleValue();
                this.timestamp = ((Number) m.get("timestamp")).longValue();
            }
        }
    }
}
