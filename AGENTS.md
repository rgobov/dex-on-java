# AGENTS.md — dex-on-java

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

## Architecture

Full design doc in [`ARCHITECTURE.md`](ARCHITECTURE.md) — three-layer vision (L1 Arbitrum → L2 PBFT → L3 Execution).  
Current state below.

Single Maven module. Packages under `src/main/java/com/example/dex/`:

| Package | Purpose |
|---|---|
| `server/` | DexServer (single-node dev) + ValidatorNode (PBFT entry point) |
| `disruptor/` | LMAX Disruptor event pipeline — **entrypoint for all state changes** |
| `matching/` | Price-time priority order book (OrderBook) |
| `margin/` | MarginManager, LiquidationEngine, FundingCalculator |
| `models/` | Order, Trade, ChainTransaction, RollupBatch, Position, etc. |
| `bridge/` | L1↔L2 bridge + ArbitrumBridge mock (retryable tickets, challenge window) |
| `l1/` | L1 ledger state, PoS consensus, block/transaction models |
| `l2/` | PBFT consensus, Mempool, L2Block, LeaderElector, ValidatorNetwork (HTTP) |
| `l3/persistence/` | FlatFileStore — WAL (JSON Lines) + periodic snapshots, recovery on restart |
| `client/` | DexClient SDK with validator failover |
| `oracle/` | Price oracle service |
| `funding/` | Perpetual funding rate calculator |
| `cryptography/` | RSA key generation, signing, verification |

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

## Test quirks

- **`ThroughputBenchmarkTest`** is heavy (~250k transactions + JVM warmup). Expect it to take 30-60+ seconds. It measures TPS for L2 vs SMR paths and includes crypto/rollup hashing benchmarks.
- `ValidatorNetworkTest` uses ephemeral ports (90xx) for inter-validator HTTP.
- `ArbitrumBridgeTest` uses configurable timing parameters — 3 tests cover deposit, withdrawal, and challenge flow.
- `PbftConsensusTest` tests in-process PBFT with 3 validators — block commit, leader rotation, empty block.
- No integration test requires external services. `EndToEndIntegrationTest` is in-process.
- Benchmark test suppresses stdout during measurement via `System.setOut`.

## Notable details

- Java 25 required (`maven.compiler.source/target = 25`). Check your JDK version first.
- No Maven wrapper (`mvnw`) committed.
- No CI workflows, no pre-commit hooks, no linter config.
- UI is plain HTML/JS/CSS served as external static files from `ui/`. No framework.
- RSA signatures (SHA256withRSA) for all crypto operations.
- Rollup state root is SHA-256 of `(batchId | prevStateRoot | timestamp | tradeData)`.
- `L1Vault` requires ≥2/3 validator multisig signatures for withdrawals.
- Margin system supports both isolated and cross margin modes.
- ValidatorNode uses `PEERS` env var for peer discovery (`val-1:8001,val-2:8002,...`).
- FlatFileStore saves to `data-<nodeId>/l3/` — snapshot (JSON) + wal.jsonl.
- PbftConsensus uses stake-weighted round-robin leader election (equal stakes by default).
- ArbitrumBridge mock has configurable challenge window (default 7 days in prod, ms in tests).
