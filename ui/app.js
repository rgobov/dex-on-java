// --- State Variables ---
let l1VaultBalance = 20000.0;
let l2FreeBalance = 10000.0;
let l2LockedMargin = 0.0;

let positionSize = 0.0; // Positive for Long, Negative for Short
let positionSide = "—";
let entryPrice = 0.0;
let liquidationPrice = 0.0;
let unrealizedPnL = 0.0;

let orderSide = "buy";
let lastDepositId = 2;

let asks = [];
let bids = [];
let recentTrades = [];
let activeTab = "book";
let autoTradeInterval = null;

// L1 Ledger Blocks
let l1Blocks = [];

// L2 SMR Validators
let validators = [];

let logs = [];
let tps = 0;
let lastRenderedBatchId = 0;

// --- Init & UI Updates ---
window.onload = function() {
    log("Система инициализирована. Соединение с бэкендом Java...", "info");
    
    // Poll the backend state every second
    setInterval(async () => {
        await fetchBackendState();
    }, 1000);

    // Simulate minor background TPS fluctuations
    setInterval(() => {
        tps = Math.floor(Math.random() * 40) + 10;
        if (autoTradeInterval) tps += 1200; // Boost TPS if auto-trading is active!
        document.getElementById("tps-counter").innerText = tps;
    }, 1500);

    // Initial fetch
    fetchBackendState();
};

async function fetchBackendState() {
    try {
        const res = await fetch('/api/state');
        if (!res.ok) return;
        const state = await res.json();

        // Update balances for the selected client trader
        const traderName = document.getElementById("order-trader").value;
        const account = state.accounts.find(a => a.name === traderName);
        
        l1VaultBalance = state.vaultL1Balance;
        
        if (account) {
            // Display Sequencer L2 balances and position on main UI
            l2FreeBalance = account.l2FreeBalance;
            l2LockedMargin = account.l2LockedMargin;
            positionSize = account.l2PositionSize;
            entryPrice = account.l2EntryPrice;
        }

        // Validators status
        validators = state.validators.map((v, i) => {
            return {
                name: v.name,
                stake: v.stake,
                power: 33.3,
                signedBlocks: validators[i] ? validators[i].signedBlocks : Math.floor(Math.random() * 50) + 100,
                status: v.status
            };
        });

        // L1 blocks
        l1Blocks = state.l1Blocks.map(b => {
            return {
                height: b.height,
                hash: b.hash,
                txType: b.height === 1 ? "GENESIS" : "TX_BATCH",
                proposer: b.proposer,
                amount: 0
            };
        });

        // Check rollup batches
        const rollupBatches = state.rollupBatches || [];
        if (rollupBatches.length > 0) {
            const latestBatch = rollupBatches[rollupBatches.length - 1];
            if (latestBatch.batchId > lastRenderedBatchId) {
                if (lastRenderedBatchId > 0) {
                    animateRollupCommitment(latestBatch.batchId);
                }
                lastRenderedBatchId = latestBatch.batchId;
            }
        }
        renderRollupBatches(rollupBatches);

        // Fetch Orderbook & Trades
        await fetchOrderBook();
        await fetchTrades();

        updateUI();
    } catch (e) {
        console.error("Failed to fetch state from Java server", e);
    }
}

async function fetchOrderBook() {
    try {
        const res = await fetch('/api/orderbook');
        if (!res.ok) return;
        const data = await res.json();
        asks = data.asks || [];
        bids = data.bids || [];
    } catch (e) {
        console.error(e);
    }
}

async function fetchTrades() {
    try {
        const res = await fetch('/api/trades');
        if (!res.ok) return;
        const data = await res.json();
        recentTrades = data || [];
    } catch (e) {
        console.error(e);
    }
}

function log(message, type = "info") {
    const time = new Date().toLocaleTimeString();
    let colorClass = "";
    if (type === "success") colorClass = "text-green";
    else if (type === "error") colorClass = "text-red";
    else if (type === "warning") colorClass = "text-amber";
    else if (type === "info") colorClass = "text-cyan";

    logs.unshift({ time, message, colorClass });
    if (logs.length > 50) logs.pop();

    const feed = document.getElementById("logs-feed");
    if (feed) {
        feed.innerHTML = logs.map(l => 
            `<div class="log-row">[${l.time}] <span class="${l.colorClass}">${l.message}</span></div>`
        ).join('');
    }
}

function updateUI() {
    document.getElementById("l1-vault-balance").innerText = `$${l1VaultBalance.toFixed(2)}`;
    document.getElementById("l2-free-balance").innerText = `$${l2FreeBalance.toFixed(2)}`;
    document.getElementById("l2-locked-margin").innerText = `$${l2LockedMargin.toFixed(2)}`;
    
    // Position UI
    document.getElementById("pos-size").innerText = `${Math.abs(positionSize).toFixed(2)} BTC`;
    document.getElementById("pos-side").innerText = positionSize === 0 ? "—" : (positionSize > 0 ? "LONG" : "SHORT");
    document.getElementById("pos-side").className = "pos-val " + (positionSize === 0 ? "" : (positionSize > 0 ? "text-green" : "text-red"));
    document.getElementById("pos-entry").innerText = `$${entryPrice.toFixed(2)}`;
    document.getElementById("pos-liq").innerText = liquidationPrice === 0 ? "$0.00" : `$${liquidationPrice.toFixed(2)}`;
    
    // Calculate PnL relative to mid price
    const midPrice = getMidPrice();
    if (positionSize > 0) {
        unrealizedPnL = positionSize * (midPrice - entryPrice);
    } else if (positionSize < 0) {
        unrealizedPnL = Math.abs(positionSize) * (entryPrice - midPrice);
    } else {
        unrealizedPnL = 0;
    }
    
    const pnlEl = document.getElementById("pos-pnl");
    pnlEl.innerText = `${unrealizedPnL >= 0 ? "+" : ""}$${unrealizedPnL.toFixed(2)}`;
    pnlEl.className = "pos-val " + (unrealizedPnL === 0 ? "" : (unrealizedPnL > 0 ? "text-green" : "text-red"));

    // L1 Block height
    document.getElementById("l1-block-height").innerText = `Height ${l1Blocks.length}`;

    // Validators UI
    const activeCount = validators.filter(v => v.status === "Active").length;
    const smrStatusEl = document.getElementById("smr-status");
    smrStatusEl.innerText = `${activeCount}/3 Nodes`;
    if (activeCount >= 2) {
        smrStatusEl.className = "status-value text-purple";
    } else {
        smrStatusEl.className = "status-value text-red animate-pulse";
    }

    renderValidators();
    renderL1Blocks();
}

function renderValidators() {
    const list = document.getElementById("validators-list");
    list.innerHTML = validators.map((val, idx) => {
        const isActive = val.status === "Active";
        const badgeClass = isActive ? "active" : "jailed";
        const statusText = isActive ? "Active" : "Jailed";
        
        return `
            <div class="validator-card ${val.status.toLowerCase()}">
                <div class="val-header">
                    <span class="val-name">${val.name}</span>
                    <span class="val-status-badge ${badgeClass}">${statusText}</span>
                </div>
                <div class="val-stat mt-1">
                    <span>Стейк L1:</span>
                    <span>$${val.stake.toFixed(0)}</span>
                </div>
                <div class="val-stat">
                    <span>Сила голоса:</span>
                    <span>${val.power}%</span>
                </div>
                <div class="val-stat">
                    <span>Подписано блоков:</span>
                    <span>${val.signedBlocks}</span>
                </div>
                <button class="btn-jail-toggle" onclick="toggleJail(${idx})">
                    ${isActive ? "Сломать ноду (Jail)" : "Восстановить (Unjail)"}
                </button>
            </div>
        `;
    }).join('');
}

async function toggleJail(idx) {
    const val = validators[idx];
    const newAction = val.status === "Active" ? "jail" : "unjail";
    
    if (newAction === "jail") {
        log(`Отправка транзакции слешинга для ${val.name}...`, "warning");
    } else {
        log(`Отправка транзакции разджайла для ${val.name}...`, "info");
    }

    try {
        const res = await fetch('/api/jail', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: val.name, action: newAction })
        });
        if (res.ok) {
            if (newAction === "jail") {
                log(`Валидатор ${val.name} оштрафован на 20% своего стейка на L1!`, "error");
            } else {
                log(`Валидатор ${val.name} успешно восстановлен в консенсусе.`, "success");
            }
            await fetchBackendState();
        }
    } catch (e) {
        console.error(e);
    }
}

function renderL1Blocks() {
    const list = document.getElementById("l1-blocks-list");
    list.innerHTML = l1Blocks.map(b => `
        <div class="block-item">
            <div class="block-header">
                <span class="text-amber">Block #${b.height}</span>
                <span class="text-dim" style="font-size:0.6rem;">${b.hash.substring(0, 15)}...</span>
            </div>
            <div class="block-txs">
                Type: ${b.txType} | Proposer: ${b.proposer}
            </div>
        </div>
    `).join('');
}

function addL1Block(txType, proposer, amount) {
    const nextHeight = l1Blocks.length + 1;
    const fakeHash = "00000000" + Math.random().toString(16).substring(2, 10) + "hijk...";
    l1Blocks.push({
        height: nextHeight,
        hash: fakeHash,
        txType,
        proposer,
        amount
    });
}

// --- Order Book updates ---
function getMidPrice() {
    if (asks.length === 0 || bids.length === 0) return 60000;
    return (asks[0].price + bids[0].price) / 2;
}

function updateOrderBook() {
    // Sort asks (ascending) and bids (descending)
    asks.sort((a, b) => a.price - b.price);
    bids.sort((a, b) => b.price - a.price);

    const mid = getMidPrice();
    document.getElementById("orderbook-mid").innerText = `$${mid.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

    // Render asks
    const asksFeed = document.getElementById("orderbook-asks");
    let cumulativeAsk = 0;
    asksFeed.innerHTML = [...asks].reverse().map(a => {
        cumulativeAsk += a.amount;
        const total = a.price * a.amount;
        const width = Math.min((a.amount / 5) * 100, 100);
        return `
            <div class="orderbook-row text-red" onclick="fillPriceField(${a.price})">
                <div class="depth-bar" style="width: ${width}%"></div>
                <span>$${a.price.toLocaleString()}</span>
                <span>${a.amount.toFixed(2)}</span>
                <span>$${total.toLocaleString(undefined, { maximumFractionDigits: 0 })}</span>
            </div>
        `;
    }).join('');

    // Render bids
    const bidsFeed = document.getElementById("orderbook-bids");
    let cumulativeBid = 0;
    bidsFeed.innerHTML = bids.map(b => {
        cumulativeBid += b.amount;
        const total = b.price * b.amount;
        const width = Math.min((b.amount / 5) * 100, 100);
        return `
            <div class="orderbook-row text-green" onclick="fillPriceField(${b.price})">
                <div class="depth-bar" style="width: ${width}%"></div>
                <span>$${b.price.toLocaleString()}</span>
                <span>${b.amount.toFixed(2)}</span>
                <span>$${total.toLocaleString(undefined, { maximumFractionDigits: 0 })}</span>
            </div>
        `;
    }).join('');
}

function fillPriceField(val) {
    document.getElementById("order-price").value = val;
}

// --- Tab Switching & Trades List ---
function switchTab(tabName) {
    activeTab = tabName;
    const tabBook = document.getElementById("tab-book");
    const tabTrades = document.getElementById("tab-trades");
    const bookContent = document.getElementById("book-tab-content");
    const tradesContent = document.getElementById("trades-tab-content");

    if (tabName === "book") {
        tabBook.classList.add("active");
        tabTrades.classList.remove("active");
        bookContent.style.display = "block";
        tradesContent.style.display = "none";
    } else {
        tabBook.classList.remove("active");
        tabTrades.classList.add("active");
        bookContent.style.display = "none";
        tradesContent.style.display = "block";
    }
}

function updateTradesList() {
    const list = document.getElementById("recent-trades-list");
    list.innerHTML = recentTrades.map(t => {
        const sideClass = t.side === "buy" ? "text-green" : "text-red";
        return `
            <div class="trade-row">
                <span class="text-dim">${t.time}</span>
                <span class="${sideClass}">$${t.price.toLocaleString()}</span>
                <span>${t.amount.toFixed(2)} BTC</span>
                <span class="text-dim" style="font-size:0.65rem;">${t.maker} ↔ ${t.taker} (${t.type})</span>
            </div>
        `;
    }).join('');
}

// --- Automated Client Simulator (Background Trade Matching) ---
function toggleAutoTrade(isActive) {
    if (isActive) {
        log("Симуляция торговой активности клиентов L2 запущена.", "success");
        autoTradeInterval = setInterval(simulateClientTrade, 2500);
    } else {
        log("Симуляция торговой активности клиентов остановлена.", "warning");
        clearInterval(autoTradeInterval);
        autoTradeInterval = null;
    }
}

async function simulateClientTrade() {
    const activeCount = validators.filter(v => v.status === "Active").length;
    
    // Choose random clients (Bob or Charlie)
    const names = ["Bob", "Charlie"];
    const trader = names[Math.floor(Math.random() * names.length)];
    const isBuy = Math.random() > 0.5;
    
    const mid = getMidPrice();
    const type = Math.random() > 0.5 ? "L2" : "SMR";

    if (type === "SMR" && activeCount < 2) {
        return; // Skip SMR if offline
    }

    // Generate random price near mid
    const offset = Math.floor(Math.random() * 200) - 100;
    const price = Math.floor(mid + offset);
    const amount = parseFloat((Math.random() * 1.5 + 0.1).toFixed(2));

    try {
        await fetch('/api/order', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                user: trader,
                price: price,
                amount: amount,
                leverage: 10,
                isBuy: isBuy,
                routingPolicy: type
            })
        });
    } catch (e) {
        console.error("Auto trade simulation error:", e);
    }
}


// --- Trading Logic ---
function setOrderSide(side) {
    orderSide = side;
    const buyBtn = document.getElementById("order-side-buy");
    const sellBtn = document.getElementById("order-side-sell");
    if (side === "buy") {
        buyBtn.classList.add("active");
        sellBtn.classList.remove("active");
    } else {
        sellBtn.classList.add("active");
        buyBtn.classList.remove("active");
    }
}

function updateLeverageLabel(val) {
    document.getElementById("leverage-label").innerText = `${val}x`;
}

function submitOrder() {
    const price = parseFloat(document.getElementById("order-price").value);
    const amount = parseFloat(document.getElementById("order-amount").value);
    const leverage = parseInt(document.getElementById("order-leverage").value);
    const routing = document.getElementById("order-routing").value;

    if (isNaN(price) || isNaN(amount) || amount <= 0 || price <= 0) {
        log("Некорректная цена или объем ордера!", "error");
        return;
    }

    // Check margin requirements
    const requiredMargin = (price * amount) / leverage;
    if (l2FreeBalance < requiredMargin && positionSize === 0) {
        log(`Недостаточно свободного баланса L2! Требуется залог: $${requiredMargin.toFixed(2)}`, "error");
        return;
    }

    // Determine target execution path (L2 Sequencer vs SMR consensus)
    let selectedPath = "";
    if (routing === "FORCE_L2") {
        selectedPath = "L2";
    } else if (routing === "FORCE_SMR") {
        selectedPath = "SMR";
    } else { // BEST_EXECUTION
        // In real SOR: it routes to whichever is more optimal. Let's pass "SOR" to the backend.
        // For local SVG animation, let's pick the path.
        selectedPath = Math.random() > 0.5 ? "L2" : "SMR";
    }

    // Verify SMR status if routed to SMR
    if (selectedPath === "SMR") {
        const activeCount = validators.filter(v => v.status === "Active").length;
        if (activeCount < 2) {
            log("Ошибка отправки: SMR-консенсус недоступен. Менее 2/3 активных нод!", "error");
            animateFailedPath("router-smr");
            return;
        }
    }

    // Disable button during network animation
    const submitBtn = document.getElementById("submit-order-btn");
    submitBtn.disabled = true;

    // Trigger SVG path animations
    animateSignalPath(selectedPath, () => {
        submitBtn.disabled = false;
        executeOrderMatching(price, amount, leverage, selectedPath, routing === "BEST_EXECUTION" ? "SOR" : selectedPath);
    });
}

async function executeOrderMatching(price, amount, leverage, sourcePath, routingPolicy) {
    const isBuy = orderSide === "buy";
    const trader = document.getElementById("order-trader").value;
    const delay = sourcePath === "L2" ? "0.85 µs (Sequencer)" : "154.2 ms (SMR Consensus)";
    
    log(`Отправка ордера: ${trader} -> ${isBuy ? "ПОКУПКА" : "ПРОДАЖА"} ${amount} BTC @ $${price} через ${sourcePath}...`, "info");

    try {
        const res = await fetch('/api/order', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                user: trader,
                price: price,
                amount: amount,
                leverage: leverage,
                isBuy: isBuy,
                routingPolicy: routingPolicy
            })
        });
        const data = await res.json();
        if (res.ok) {
            log(`Ордер обработан на бэкенде через ${sourcePath} (задержка: ${delay})`, "success");
            await fetchBackendState();
        } else {
            log(`Ошибка выставления ордера: ${data.message || 'неизвестная ошибка'}`, "error");
        }
    } catch (e) {
        log("Сбой соединения с сервером!", "error");
        console.error(e);
    }
}

// --- Bridge Modals & Action ---
let activeModalAction = "deposit";

function showBridgeModal(action) {
    activeModalAction = action;
    const modal = document.getElementById("bridge-modal");
    const title = document.getElementById("modal-title");
    const submitBtn = document.getElementById("modal-submit-btn");
    const label = document.getElementById("modal-amount-label");
    const amountInput = document.getElementById("modal-amount");

    if (action === "deposit") {
        title.innerText = "Депозит на L2 через Мост";
        submitBtn.innerText = "Внести средства на L2";
        label.innerText = "Сумма депозита с L1 (USDC)";
        amountInput.value = "5000";
    } else {
        title.innerText = "Вывод средств на L1 (Мультиподпись)";
        submitBtn.innerText = "Инициировать вывод на L1";
        label.innerText = "Сумма вывода на L1 (USDC)";
        amountInput.value = "2000";
    }
    modal.style.display = "flex";
}

function closeBridgeModal() {
    document.getElementById("bridge-modal").style.display = "none";
}

async function executeBridgeAction() {
    const val = parseFloat(document.getElementById("modal-amount").value);
    if (isNaN(val) || val <= 0) {
        alert("Укажите корректную сумму!");
        return;
    }

    closeBridgeModal();
    const trader = document.getElementById("order-trader").value;

    if (activeModalAction === "deposit") {
        log(`Инициирован депозит: $${val} списывается с L1 и переносится через мост.`, "info");
        
        // Animate L1 -> Client -> Router -> L2 Sequencer / SMR
        animateBridgeTransfer("deposit", async () => {
            try {
                const res = await fetch('/api/deposit', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ user: trader, amount: val })
                });
                if (res.ok) {
                    log(`L2_BRIDGE: Обнаружено депозитное событие. Счета на L2 пополнены на $${val}.`, "success");
                    await fetchBackendState();
                } else {
                    log("Сбой депозита на бэкенде", "error");
                }
            } catch (e) {
                log("Сбой соединения с сервером при депозите", "error");
            }
        });

    } else { // Withdraw
        log(`Запрос вывода: ${trader} подписывает вывод $${val} на L1. Отправка валидаторам L2 SMR...`, "info");

        // BFT Multisig consensus checks
        const activeCount = validators.filter(v => v.status === "Active").length;
        if (activeCount < 2) {
            log("ОТКЛОНЕНО: Смарт-контракт L1Vault заблокировал вывод! Менее 2/3 подписей валидаторов (BFT consensus offline).", "error");
            animateFailedPath("link-smr-l1");
            return;
        }

        animateBridgeTransfer("withdraw", async () => {
            try {
                const res = await fetch('/api/withdraw', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ user: trader, amount: val })
                });
                const data = await res.json();
                if (res.ok) {
                    log(`L2_BRIDGE: Собраны подписи 2/3 активных валидаторов (${activeCount} из 3).`, "info");
                    log(`L1_VAULT: Мультиподпись успешно верифицирована. Смарт-контракт L1 выплатил $${val} пользователю.`, "success");
                    await fetchBackendState();
                } else {
                    log(`Вывод отклонен: ${data.message || 'ошибка бэкенда'}`, "error");
                    animateFailedPath("link-smr-l1");
                }
            } catch (e) {
                log("Сбой соединения с сервером при выводе", "error");
            }
        });
    }
}

// --- SVG Path Animations ---
function animateSignalPath(pathType, callback) {
    const pulse = document.getElementById("signal-pulse");
    pulse.style.display = "block";
    pulse.setAttribute("r", "6");
    
    // Set appropriate color
    if (pathType === "L2") {
        pulse.className.baseVal = "signal"; // blue
        document.getElementById("node-sequencer").classList.add("active");
        document.getElementById("node-smr").classList.remove("active");
    } else {
        pulse.className.baseVal = "signal smr-signal"; // purple
        document.getElementById("node-smr").classList.add("active");
        document.getElementById("node-sequencer").classList.remove("active");
    }

    // Step 1: Client to Router
    animateDot(50, 110, 160, 110, 250, () => {
        // Step 2: Router to destination
        if (pathType === "L2") {
            animateDot(160, 110, 270, 50, 250, () => {
                pulse.style.display = "none";
                setTimeout(() => {
                    document.getElementById("node-sequencer").classList.remove("active");
                }, 400);
                callback();
            });
        } else {
            animateDot(160, 110, 270, 170, 350, () => {
                pulse.style.display = "none";
                setTimeout(() => {
                    document.getElementById("node-smr").classList.remove("active");
                }, 400);
                callback();
            });
        }
    });
}

function animateBridgeTransfer(type, callback) {
    const pulse = document.getElementById("signal-pulse");
    pulse.style.display = "block";
    pulse.setAttribute("r", "6");

    if (type === "deposit") {
        pulse.className.baseVal = "signal";
        // L1 -> Client -> Router -> L2
        animateDot(520, 110, 50, 110, 400, () => {
            animateDot(50, 110, 160, 110, 250, () => {
                animateDot(160, 110, 270, 50, 250, () => {
                    pulse.style.display = "none";
                    callback();
                });
            });
        });
    } else {
        // Withdraw: SMR validator consensus -> L2 Settlement -> L1 Escrow
        pulse.className.baseVal = "signal smr-signal";
        document.getElementById("node-smr").classList.add("active");
        animateDot(270, 170, 400, 110, 300, () => {
            animateDot(400, 110, 520, 110, 250, () => {
                document.getElementById("node-smr").classList.remove("active");
                pulse.style.display = "none";
                callback();
            });
        });
    }
}

function renderRollupBatches(batches) {
    const list = document.getElementById("l2-rollups-list");
    if (!list) return;
    if (!batches || batches.length === 0) {
        list.innerHTML = `<div style="color:var(--text-dim); text-align:center; padding:1rem; font-size:0.7rem;">Нет опубликованных роллапов</div>`;
        return;
    }
    list.innerHTML = batches.map(b => `
        <div class="block-item" style="border-color: rgba(168, 85, 247, 0.25);">
            <div class="block-header">
                <span class="text-purple">Rollup Batch #${b.batchId}</span>
                <span class="text-dim" style="font-size:0.6rem;">${new Date(b.timestamp).toLocaleTimeString()}</span>
            </div>
            <div class="block-txs">
                Сделок упаковано: <strong>${b.tradesCount}</strong>
            </div>
            <div style="font-size:0.6rem; color:var(--cyan); margin-top:2px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">
                State Root: ${b.stateRoot.substring(0, 16)}...
            </div>
        </div>
    `).join('');
}

function animateRollupCommitment(batchId) {
    const pulse = document.getElementById("signal-pulse");
    pulse.style.display = "block";
    pulse.setAttribute("r", "8");
    pulse.className.baseVal = "signal smr-signal"; // purple
    document.getElementById("node-sequencer").classList.add("active");
    
    animateDot(270, 50, 400, 110, 600, () => {
        document.getElementById("node-sequencer").classList.remove("active");
        pulse.style.display = "none";
        
        // Flash L2 Settlement node
        const l2Node = document.getElementById("node-l2");
        if (l2Node) {
            l2Node.style.fill = "rgba(168, 85, 247, 0.3)";
            l2Node.style.filter = "drop-shadow(0 0 12px #a855f7)";
            setTimeout(() => {
                l2Node.style.fill = "";
                l2Node.style.filter = "";
            }, 600);
        }
        log(`L2_SETTLEMENT: Зафиксирован Rollup Batch #${batchId} на расчетном уровне L2!`, "success");
    });
}

function animateFailedPath(linkId) {
    const link = document.getElementById(linkId);
    if (!link) return;
    
    // Flash link red
    link.style.stroke = "#ff3b30";
    setTimeout(() => {
        link.style.stroke = "";
    }, 1000);
}

function animateDot(x1, y1, x2, y2, duration, onComplete) {
    const pulse = document.getElementById("signal-pulse");
    const startTime = performance.now();

    function step(now) {
        const elapsed = now - startTime;
        const progress = Math.min(elapsed / duration, 1);
        
        // Easing (ease-in-out)
        const t = progress < 0.5 ? 2 * progress * progress : -1 + (4 - 2 * progress) * progress;

        const curX = x1 + (x2 - x1) * t;
        const curY = y1 + (y2 - y1) * t;

        pulse.setAttribute("cx", curX);
        pulse.setAttribute("cy", curY);

        if (progress < 1) {
            requestAnimationFrame(step);
        } else {
            onComplete();
        }
    }

    requestAnimationFrame(step);
}
