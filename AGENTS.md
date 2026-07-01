# AGENTS.md — dex-on-java

## ⚠️ AI Instruction: read code before editing

Before making ANY change to the codebase, you MUST:

1. **Read the file(s) you plan to edit** — never rely on your training data or previous conversation context for file contents
2. **Check imports and existing patterns** — read 10+ lines of context around your edit point to match code style, library usage, and naming conventions
3. **Search for similar code** — use `grep` or `task` to find examples of the pattern you're implementing before writing new code
4. **Read the test file** for the class you're editing before changing production code
5. **Verify `mvn test` passes** after your changes

The codebase evolves with every session. Your training data is stale. Always read first.

---

## Build & test

```bash
# Compile (Java 25 required)
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=OrderBookTest

# Run a single test method
mvn test -Dtest=OrderBookTest#testLimitOrderMatchingAndPartialFill
```

No test services, fixtures, or external dependencies needed. Tests run in-process.

## Start the server

### Single-node dev server (no consensus)

```bash
mvn compile exec:java -Dexec.mainClass="com.example.dex.server.DexServer"
# UI: http://localhost:8000
```

### Three-node PBFT cluster

Terminal 1 — Validator A:
```bash
PEERS=val-1:8001,val-2:8002,val-3:8003 \
  mvn compile exec:java -Dexec.mainClass="com.example.dex.server.ValidatorNode" \
  -Dexec.args="val-1 8001 data-val1"
```

Terminal 2 — Validator B:
```bash
PEERS=val-1:8001,val-2:8002,val-3:8003 \
  mvn compile exec:java -Dexec.mainClass="com.example.dex.server.ValidatorNode" \
  -Dexec.args="val-2 8002 data-val2"
```

Terminal 3 — Validator C:
```bash
PEERS=val-1:8001,val-2:8002,val-3:8003 \
  mvn compile exec:java -Dexec.mainClass="com.example.dex.server.ValidatorNode" \
  -Dexec.args="val-3 8003 data-val3"
```

Each validator exposes its own REST API on the configured port. Client SDK (`DexClient`) auto-failovers between them.

### Telegram Mini App

```bash
# Start a single validator, then open:
# http://localhost:8001/telegram.html
mvn compile exec:java -Dexec.mainClass="com.example.dex.server.ValidatorNode" \
  -Dexec.args="val-1 8001 data-val1"
```

For production Telegram Mini App: create bot via @BotFather → set Menu Button URL → URL must be HTTPS (ngrok ok for dev).

---

## Architecture

Full design doc in [`ARCHITECTURE.md`](ARCHITECTURE.md) — three-layer vision.  
**Layer naming (our system, not Ethereum global):**

| Layer | Name | Responsibility |
|---|---|---|
| **L1** | Ethereum | Base settlement layer. Ultimate source of truth for Arbitrum (L2). |
| **L2** | Arbitrum / TON | Networks holding USDC (Arbitrum) / USDT (TON). Our bridge targets for deposits/withdrawals. |
| **L3** | Our SMR (PBFT + Execution) | Deterministic matching, margin, liquidation, state machine replicated across 3 validators. |

> **Note:** We are NOT an Ethereum L3 (rollup-on-rollup). We build our own PBFT-based state machine (L3) that uses Arbitrum/TON as settlement layers (L2). Hyperliquid uses similar model but calls their consensus layer L1.

> **Package naming vs layer naming:** Java packages `l1/` (legacy PoS code, not used in current architecture), `l2/` (our PBFT consensus — part of L3 layer!), and `l3/` (FlatFileStore — also part of L3 layer) predate this naming convention and do NOT align with it.

Current state below.

Single Maven module. Packages under `src/main/java/com/example/dex/`:

| Package | Purpose |
|---|---|
| `server/` | DexServer (single-node dev) + ValidatorNode (PBFT entry point) |
| `disruptor/` | LMAX Disruptor event pipeline — **entrypoint for all state changes** |
| `matching/` | Price-time priority order book (OrderBook) |
| `margin/` | MarginManager, LiquidationEngine, FundingCalculator |
| `models/` | Order, Trade, ChainTransaction, RollupBatch, Position, etc. |
| `bridge/` | **ArbitrumBridge** (retryable tickets, 7d challenge window) + **TonBridge** (instant, no window) + ArbitrumBridgePoller, TonBridgePoller, WithdrawalFinalizer, TonWithdrawalFinalizer |
| `l1/` | Legacy — L1ConsensusPoS, LedgerState, L1Block (not used in current L1/L2/L3 naming) |
| `l2/` | PBFT consensus (PbftConsensus, LeaderElector), Mempool, L2Block, ValidatorNetwork |
| `l3/persistence/` | FlatFileStore — WAL (JSON Lines) + periodic snapshots, recovery on restart |
| `client/` | DexClient SDK with validator failover |
| `oracle/` | Price oracle service |
| `funding/` | Perpetual funding rate calculator |
| `cryptography/` | RSA key generation, signing, verification |

### Bridges

Two parallel bridges for deposits/withdrawals:

| Bridge | Network | Deposit | Withdrawal | Challenge window | When to use |
|---|---|---|---|---|---|
| `ArbitrumBridge` | Ethereum / Arbitrum | ~10-15 min (retryable ticket) | 7 days (or fast 0.3% fee) | 7 days | Production main |
| `TonBridge` | TON (Telegram) | ~3 sec (USDT transfer) | ~1 sec (immediate) | None | Telegram Mini App |

### Fast withdrawal (Arbitrum)

Standard Arbitrum withdrawal takes 7 days. Fast withdrawal (0.3% fee) skips the wait:

```
User → POST /api/withdraw {fast: true, feeBps: 30}
  → WITHDRAW_SIGNED tx with isFastWithdraw=true
  → PBFT → StateExecutionHandler → PendingWithdrawal(fast=true)
  → WithdrawalFinalizer → depositL1(user, amount - fee) + depositL1(LP, fee) ← IMMEDIATELY
```

ChainTransaction carries `isFastWithdraw` (boolean) and `fastFeeBps` (int, 0-10000) fields.

### TON Bridge (Telegram)

```
User sends USDT (TON) to vault → TonBridge.depositUsdt()
  → 3s confirmation → outbox → TonBridgePoller → Mempool → PBFT → L3 credited

User withdraws → POST /api/ton/withdraw {signature}
  → WITHDRAW_SIGNED → PBFT → TonWithdrawalFinalizer
  → TonBridge.sendToWallet() ← IMMEDIATELY (no 7-day wait)
```

Telegram users (`tg-*` userId prefix) bypass RSA signature verification — auth is via Telegram WebApp `initData` (HMAC-signed by bot token).

### Disruptor pipeline

All state transitions go through `com.lmax.disruptor.RingBuffer<ChainTxEvent>` → `StateExecutionHandler#onEvent`. The handler processes: DEPOSIT, WITHDRAW, PLACE_ORDER, CANCEL_ORDER, UPDATE_ORACLE.

### Data flow (three-node cluster)

```
Client → POST /api/order → ValidatorNode (any peer)
  → Mempool.add(tx)
  → PbftConsensus.runConsensusRound() [1s tick]
    → Leader proposes block from mempool batch
    → PrePrepare → Prepare → Commit (≥2/3 required)
    → On commit: execute block txs via RingBuffer → StateExecutionHandler
    → FlatFileStore periodic snapshot (every 30s)
```

---

## UI

| File | Description |
|---|---|
| `ui/index.html` | Full dashboard — orderbook, validator monitor, Arbitrum bridge, SVG network viz |
| `ui/telegram.html` | Telegram Mini App — compact layout, TON bridge, Telegram auth |
| `ui/telegram-app.js` | Telegram Mini App JS — TG WebApp SDK, TON deposit/withdraw, order placement |
| `ui/app.js` | Dashboard JS — state polling, SVG animations, bridge modals |
| `ui/style.css` | Shared styles — dark theme, both UIs |

`telegram.html` loads `telegram-web-app.js` from CDN and falls back gracefully if not inside Telegram.

---

## Test quirks

- **`ThroughputBenchmarkTest`** is heavy (~250k transactions + JVM warmup). Expect it to take 30-60+ seconds. It measures TPS for L2 (consensus-only) vs SMR (full execution) paths and includes crypto/rollup hashing benchmarks. Note: test's "L2" naming predates current layer naming — it refers to our PBFT layer, not Arbitrum/TON.
- `ValidatorNetworkTest` uses ephemeral ports (90xx) for inter-validator HTTP.
- `ArbitrumBridgeTest` uses configurable timing parameters — 3 tests cover deposit, withdrawal, and challenge flow.
- `ArbitrumBridgePollerTest` tests poller outbox reading + dedup — 3 tests.
- `WithdrawalFinalizerTest` includes fast withdrawal test (L1 credited immediately, fee goes to LP).
- `TonBridgeTest` tests TON bridge deposit, withdrawal, poller, no-challenge-window behavior — 4 tests.
- `PbftConsensusTest` tests in-process PBFT with 3 validators — block commit, leader rotation, empty block.
- `StateMachineReplicationTest` tests bridge dedup, valid/invalid signature withdrawals, insufficient funds — 5 tests.
- `EndToEndIntegrationTest` includes bridge deposit end-to-end test — 3 tests.
- No integration test requires external services. All tests run in-process.
- Benchmark test suppresses stdout during measurement via `System.setOut`.

---

## Notable details

- Java 25 required (`maven.compiler.source/target = 25`). Check your JDK version first.
- No Maven wrapper (`mvnw`) committed.
- No CI workflows, no pre-commit hooks, no linter config.
- UI is plain HTML/JS/CSS served as external static files from `ui/`. No framework.
- RSA signatures (SHA256withRSA) for all crypto operations (skipped for `tg-*` users).
- Rollup state root is SHA-256 of `(batchId | prevStateRoot | timestamp | tradeData)`.
- `L1Vault` requires ≥2/3 validator multisig signatures for withdrawals.
- Margin system supports both isolated and cross margin modes.
- ValidatorNode uses `PEERS` env var for peer discovery (`val-1:8001,val-2:8002,...`).
- FlatFileStore saves to `data-<nodeId>/l3/` — snapshot (JSON) + wal.jsonl. Saved every 30s (or every 10 blocks).
- PbftConsensus uses stake-weighted round-robin leader election (equal stakes by default).
- ArbitrumBridge mock has configurable challenge window (default 7 days in prod, ms in tests).
- TonBridge mock has configurable confirm delay (default 3s).
- `ChainTransaction` carries `isFastWithdraw` and `fastFeeBps` fields for fast Arbitrum withdrawal.
- `PendingWithdrawal` carries `isFastWithdraw`, `fastFeeBps`, `beneficiary` fields; used by both finalizers.
- Snapshots now persist `processedBridgeTicketIds` and `pendingWithdrawals` (restored on recovery).
- All interaction with Arbitrum/TON bridges happens via mock in-memory — no external RPC needed.
- Total: **36 tests**, all passing in ~17-20 seconds.
