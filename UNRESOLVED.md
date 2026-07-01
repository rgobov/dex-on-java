# Unresolved Architectural Questions

> Этот документ трекает принципиальные вопросы, которые не имеют финального решения. Каждый пункт — кандидат на отдельный ADR (Architecture Decision Record).

---

## 1. Currency Model (Critical)

**Вопрос:** Как учитывать мульти-валютные депозиты (USDC Arbitrum, USDT TON, будущие стейблкоины)?

**Варианты:**
- **Single USD** — все депозиты конвертируются в внутренний USD через оракул. Один баланс, единая маржа.
- **Two-pool** — отдельные пулы: `balance_usdc`, `balance_usdt`. Маржа в конкретном пуле.
- **Oracle-weighted equity** (рекомендовано) — `equity_usd = Σ balance[token] * price_usd(token)`. Маржа считается от equity. Вывод — в токене депозита (MVP) или через внутренний свап (V2).

**Риски:**
- Single USD: системный риск де-пега (USDT падает → USDC-холдеры теряют)
- Two-pool: UX фрагментация, сложнее cross-margin
- Oracle-weighted: зависимость от оракула, нужен фоллбэк

**Решение:** Не принято. Блокирует MarginManager redesign.

---

## 2. Cross-Token Withdrawal Policy

**Вопрос:** Может ли пользователь задепонить USDC, а вывести USDT?

**MVP:** Запрет. `withdraw(token)` требует `balance[token] >= amount`.

**V2:** Внутренний свап по оракулу + spread (10-20 bps). LP пул опционально.

**V3:** Интеграция с внешними DEX (Curve, DeDust, Uniswap).

**Решение:** MVP = запрет. Не зафиксировано в коде.

---

## 3. Real Bridge Integration (Arbitrum / TON)

**Вопрос:** Интегрировать реальные Arbitrum / TON или остаться на mock для MVP?

В нашей терминологии: L1 = Ethereum, L2 = Arbitrum / TON, L3 = Our SMR.  
Arbitrum и TON — это L2 (внешние сети), через которые проходят депозиты/выводы и публикуются роллап-батчи.

**Arbitrum (L2):**
- Реальный RPC (Alchemy/Infura/own node)
- Retryable ticket contracts (Ethereum L1 → Arbitrum L2 inbox)
- Rollup batch posting → Arbitrum smart contract (state root + trade data)
- Challenge window 7 дней (настраиваемый?)

**TON (L2 our system, technically its own L1):**
- TON RPC (toncenter / own lite server)
- Vault: multisig (2-of-3 validator keys) vs смарт-контракт с проверкой подписей
- Jetton USDT transfer (TonConnect / Telegram Wallet)
- Time-lock на выводы (24h challenge) — да/нет?

**Решение:** Не начато. Нужен PoC.

---

## 4. TON Vault Custody Model

**Вопрос:** Как устроен custodial vault для USDT на TON?

**Варианты:**
- **Multisig wallet** (2-of-3 validator keys) — простое, стандартное, TON SDK поддерживает
- **Smart contract** — проверяет ≥2/3 валидаторских подписей on-chain, может иметь time-lock, upgradeability

**Time-lock:** 24h задержка вывода + возможность оспорить (как Arbitrum challenge window).

**External guardians:** 4-й/5-й ключ (аудитор, арбитр) для снижения риска сговора 2/3 валидаторов.

**Решение:** Не выбрано.

---

## 5. Validator Stake & Economics

**Сейчас:** Равные стейки, round-robin leader election.

**Продакшн вопросы:**
- Stake в чём? (USDC bond, native token, reputation score)
- Slashing conditions: equivocation, downtime, invalid state root
- Как доказывать slashable offense? (fraud proofs, ZK, challenge period)
- Fee distribution: protocol % / validators % / stakers % / insurance fund %
- Validator onboarding: permissioned whitelist + governance?
- Kick / jail / slash за оффлайн > N блоков?

**Решение:** Не начато.

---

## 6. Trading Engine Parameters

| Параметр | Статус |
|---|---|
| Cross vs Isolated margin default | Открыто |
| Liquidation: протокол-бот vs любой пользователь (keeper) vs аукцион | Открыто |
| Funding rate: premium index / oracle TWAP / mark price basis | Открыто |
| Funding interval: 1h / 8h / continuous | Открыто |
| ADL (auto-deleveraging) при банкротстве страхового фонда | Не реализовано |
| Risk limits: max position, max leverage, notional caps per user/market | Открыто |
| Partial liquidation vs full | Открыто |

---

## 7. Telegram / TON Integration Details

- **Bot token storage:** env var / secret manager / HSM?
- **initData validation:** проверка `auth_date` (freshness), `hash` (HMAC), trusted bot token
- **TON Connect / Tonkeeper / Telegram Wallet** для реальных депозитов (не mock)
- **Threat model:** что если Telegram WebApp скомпрометирован? MITM на initData?
- **Mini App permissions:** какие данные запрашивать у пользователя?

---

## 8. Persistence & Recovery

- **WAL format:** JSON Lines (текущий) vs binary (protobuf/msgpack) для размера/скорости
- **Snapshot frequency:** 30s / 10 blocks — оптимально под нагрузку?
- **Point-in-time recovery tests:** нет интеграционных тестов краш-рекавери
- **Pruning / archival:** удаление старых блоков, компакция WAL
- **Merkle proof of state:** для light clients / auditors

---

## 9. Tokenomics & Governance (если будет)

- Нужен ли нативный токен? (ARCHITECTURE.md: опционально)
- Staking: bond в USDC или нативном токене?
- Fee switch: % протоколу, % валидаторам, % стейкерам, % insurance fund
- DAO governance: on-chain (Arbitrum DAO) или off-chain (Snapshot + multisig)
- Treasury management: multisig, timelock, investment policy

---

## 10. Regulatory & Infrastructure

- **Geo-blocking:** Cloudflare IP geo / KYC provider integration
- **Incorporation:** Cayman / BVI / Panama / другой — влияет на geo-block logic
- **Audit:** кто, когда, scope (consensus, bridges, margin, crypto)
- **Monitoring:** Prometheus/Grafana, алерты, SLA, runbooks
- **Disaster recovery:** RPO/RTO targets, backup strategy
- **Key management:** HSM / KMS для валидаторских ключей, rotation policy

---

## Приоритизация (топ-5 для unblocking)

1. **Currency Model** → блокирует MarginManager, Bridge deposit/withdraw
2. **Real Bridge Integration** → PoC для Arbitrum + TON integration
3. **TON Vault Custody** → multisig vs contract, time-lock
4. **Validator Stake Model** → leader election, slashing
5. **Cross-Token Withdrawal Policy** → зафиксировать MVP = запрет в коде

---

> Обновляй этот документ при каждом решении. Перенеси решённые вопросы в ADR или в AGENTS.md.