# Antigravity DEX — Архитектурный документ

## Цель

Построить децентрализованную биржу деривативов (perp futures) с минимальной задержкой,
максимальной пропускной способностью и устойчивостью к регуляторному давлению.

Архитектурный референс — HyperLiquid. Отличия: проще (PBFT вместо HotStuff), меньше валидаторов
(3 вместо 24), L1 settlement через Arbitrum вместо собственного L1.

---

## 1. Трёхслойная архитектура

```
┌─────────────────────────────────────────────────────┐
│                    L3: EXECUTION                      │
│  OrderBook (price-time priority)                     │
│  MarginManager (isolated/cross margin)               │
│  LiquidationEngine, FundingCalculator                 │
│  Flat file persistence (WAL + snapshots + blocks)     │
│  ~1ms на блок                                        │
├─────────────────────────────────────────────────────┤
│                    L2: CONSENSUS                      │
│  3 валидатора, PBFT (2/3), stake-weighted leader      │
│  Mempool + gossip, ordering блоков                    │
│  gRPC между валидаторами                              │
│  ~10ms на блок                                       │
├─────────────────────────────────────────────────────┤
│                    L1: SETTLEMENT                     │
│  Arbitrum bridge — публикация state root              │
│  Rollup batch + multisig выводы                      │
│  ~$0.001-0.003 за batch, ~$17-50/мес                 │
└─────────────────────────────────────────────────────┘
```

### Поток транзакции

```
1. Клиент → POST /api/order → любой валидатор (REST API)
2. Валидатор кладёт tx в mempool, gossips всем остальным
3. После gossip у всех валидаторов одинаковый набор tx
4. Лидер (stake-weighted round-robin) собирает блок:
   - max 5000 tx ИЛИ timeout 200ms (что раньше)
   - порядок: по timestamp + tx_hash (детерминированно)
5. PBFT: PrePrepare → Prepare (2/3) → Commit (2/3)
6. Блок финализирован — каждый валидатор исполняет L3:
   - for (tx : block) { handler.onEvent(tx); }
   - State root = одинаковый у всех (детерминированность)
7. Rollup publisher: state root → L1 Arbitrum bridge
```

---

## 2. Сравнение с HyperLiquid

| | HyperLiquid | Antigravity DEX |
|---|---|---|
| Консенсус | HyperBFT (HotStuff), 24+ валидатора | PBFT, 3 валидатора |
| End-to-end latency | ~200ms median, ~900ms p99 | ~10-50ms |
| TPS | 200k/sec | 1M+ |
| L1 settlement | Нет (сами L1) | Arbitrum (~$0.001/block) |
| Единая точка отказа | **Есть** — API Gateway (CloudFront) | **Нет** — каждый валидатор = свой API |
| География валидаторов | Все 24 в AWS Tokyo | 3 региона (London, Falkenstein, Singapore) |
| Язык | Rust | Java 25 |
| Смарт-контракты | HyperEVM | Нет (пока) |
| Нативный токен | HYPE ($10B market cap) | Опционально |
| Операционные расходы | ~$360k/mo (24× валидаторы) | ~$600/mo (3× сервера) |

---

## 3. Ключевые решения

### 3.1. Почему нет единого API Gateway

HyperLiquid использует CloudFront для всех клиентов. При пике API-серверы
перегружаются — торговать нельзя, хотя блокчейн жив (было два инцидента
в 2025 с даунтаймом ~40 мин).

**Решение:** каждый валидатор сам принимает REST/WS запросы.
Клиент подключается к любому. При падении — failover к другому.
Никакой единой точки отказа.

### 3.2. Почему 3 валидатора

- PBFT с 3 = 1 Byzantine fault (такая же устойчивость, как у HyperLiquid с их 24)
- Меньше round-trips = быстрее консенсус (~5ms vs ~100ms)
- Можно честно разнести по трём разным регионам и провайдерам
- В 8 раз дешевле инфраструктура

### 3.3. Почему именно Arbitrum

- Безопасность Ethereum (finality, bridge)
- Дешевизна: ~$0.0007 за tx, ~$0.001-0.003 за batch state root
- EIP-4844 (blobs) делает L1 posting ещё дешевле
- Не нужно строить собственный L1 валидаторный сет (сложно, дорого, долго)

### 3.4. Скорость vs Persistence

Matching (OrderBook, Margin) — **только in-memory, без I/O на горячем пути**.

Persistence — параллельно:
- WAL: все ChainTransaction дописываются в JSON Lines файл
- Snapshots: каждые N блоков — полный срез стейта на диск
- Blocks: каждый финализированный блок сохраняется
- Recovery: load snapshot → replay WAL → актуальное состояние

---

## 4. API-слой (без единой точки отказа)

```
Клиент (SDK)
  │
  ├── Val A (AWS London, port 9001)
  │     ├── REST: submit tx, query state, orderbook
  │     ├── WS: fills, position updates
  │     └── gRPC: gossip + PBFT с Val B, Val C
  │
  ├── Val B (Hetzner Falkenstein, port 9001)
  │
  └── Val C (Singapore bare metal, port 9001)
```

Client-side failover:
```
for (val : validators) {
    try { return POST(val, order); }
    catch (Timeout | 503) { continue; }
}
```

---

## 5. Доход

Protocol fee — комиссия с каждой сделки (0.02-0.05%).

Текущая архитектура не требует нативного токена для работы.
Токен (если будет) — для стейкинга, распределения комиссий и
управления (DAO).

---

## 6. Регуляторная стратегия (HyperLiquid path)

1. Incorporation: Cayman Islands / BVI
2. Компания владеет IP, но **не управляет биржей**
3. Сеть валидаторов — открытая, код open-source
4. Геоблокировка US + sanctioned countries (Cloudflare IP block)
5. Без KYC — кошелёк = адрес, торговля без идентификации
6. Аргумент регулятору: «мы — протокол (L1 settlement layer),
   не биржа. Код открыт, валидаторы независимы»

---

## 7. План имплементации

| Phase | Что | Срок |
|---|---|---|
| 0 | Cleanup: удалить SmartOrderRouter, дублирование L2/SMR | 1 день |
| 1 | Flat file persistence (WAL + snapshots + blocks) | 2-3 дня |
| 2 | Block, Mempool, PBFT (в одном процессе) | 3-4 дня |
| 3 | gRPC network layer | 3-4 дня |
| 4 | ValidatorNode (entry point, CLI) | 1 день |
| 5 | Client SDK + failover | 2 дня |
| 6 | L1 Arbitrum bridge | 2 дня |
| | **Total** | **~14-17 дней** |
