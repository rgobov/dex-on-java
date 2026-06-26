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

```bash
mvn compile exec:java -Dexec.mainClass="com.example.dex.server.DexServer"
# UI: http://localhost:8000
```

Alternatively, build and run the jar after `mvn package`:
```bash
java -cp target/dex-on-java-1.0-SNAPSHOT.jar:$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout) com.example.dex.server.DexServer
```

## Architecture

Full design doc in [`ARCHITECTURE.md`](ARCHITECTURE.md) — includes three-layer vision (L1 Arbitrum → L2 PBFT → L3 Execution).  
Current (pre-refactor) state below.

Single Maven module, no monorepo. Packages under `src/main/java/com/example/dex/`:

| Package | Purpose |
|---|---|
| `server/` | Javalin REST API (port 8000), wires everything together |
| `disruptor/` | LMAX Disruptor event pipeline — **entrypoint for all state changes** |
| `matching/` | Price-time priority order book (OrderBook) |
| `margin/` | MarginManager, LiquidationEngine, FundingCalculator |
| `models/` | Order, Trade, ChainTransaction, RollupBatch, Position, etc. |
| `router/` | Smart Order Router — routes between L2 & SMR engines |
| `bridge/` | L1↔L2 bridge (deposit/withdraw with BFT multisig) |
| `l1/` | L1 ledger state, PoS consensus, block/transaction models |
| `oracle/` | Price oracle service |
| `funding/` | Perpetual funding rate calculator |
| `cryptography/` | RSA key generation, signing, verification |

### Dual-engine design (key quirk)

The system runs **two identical StateExecutionHandler engines** in parallel:
- **L2 Sequencer** — fast, single-node, no consensus overhead
- **L3 SMR** — slow, via 3 validators, BFT consensus (≥2/3 required)

SmartOrderRouter can split orders across both (`BEST_EXECUTION`) or force one path. System events (deposits, oracle updates) are broadcast to both.

### Disruptor pipeline

All state transitions go through `com.lmax.disruptor.RingBuffer<ChainTxEvent>` → `StateExecutionHandler#onEvent`. The handler processes: DEPOSIT, WITHDRAW, PLACE_ORDER, CANCEL_ORDER, UPDATE_ORACLE.

## Test quirks

- **`ThroughputBenchmarkTest`** is heavy (~250k transactions + JVM warmup). Expect it to take 30-60+ seconds. It measures TPS for L2 vs SMR paths and includes crypto/rollup hashing benchmarks.
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
