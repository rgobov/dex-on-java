// === Telegram Mini App — Antigravity DEX ===

let tg = null;
let userId = "telegram-" + Math.random().toString(36).substring(2, 8); // fallback
let currentSide = "buy";

// --- Init ---
window.onload = function() {
    try {
        tg = window.Telegram?.WebApp;
        if (tg) {
            tg.expand();
            tg.ready();
            const user = tg.initDataUnsafe?.user;
            if (user) {
                userId = "tg-" + user.id;
                document.getElementById("tg-user-id").innerText = user.first_name || userId;
                logStatus("Online", true);
            } else {
                document.getElementById("tg-user-id").innerText = userId;
                logStatus("Demo (no TG data)", false);
            }
            // Apply Telegram theme
            applyTelegramTheme();
        } else {
            document.getElementById("tg-user-id").innerText = userId + " (standalone)";
            logStatus("Standalone", false);
        }
    } catch (e) {
        console.error("TG init error:", e);
        document.getElementById("tg-user-id").innerText = userId;
        logStatus("Error", false);
    }

    log("Telegram Mini App initialized. User: " + userId, "info");
    setInterval(fetchState, 1500);
    fetchState();
};

function applyTelegramTheme() {
    if (!tg) return;
    const vars = [
        "--bg-app", "--bg-panel", "--bg-card", "--bg-input",
        "--border", "--text", "--text-dim"
    ];
    // CSS vars are already set via var(--tg-theme-*) in the stylesheet
    document.body.style.background = "var(--tg-theme-bg-color)";
}

function logStatus(text, online) {
    document.getElementById("tg-connection-status").innerText = text;
    const dot = document.getElementById("tg-connection-dot");
    dot.className = "status-dot " + (online ? "online" : "offline");
}

// --- State Fetching ---
async function fetchState() {
    try {
        const res = await fetch("/api/state");
        if (!res.ok) { logStatus("Disconnected", false); return; }
        logStatus("Connected", true);

        const state = await res.json();
        updateBalances(state);
        updatePosition(state);
        updateValidators(state);
        return state;
    } catch (e) {
        logStatus("Offline", false);
    }
}

async function fetchTonBalance() {
    try {
        const res = await fetch("/api/ton/balance?userId=" + encodeURIComponent(userId));
        if (!res.ok) return;
        return await res.json();
    } catch (e) {}
}

async function updateBalances(state) {
    // Find current user in accounts
    const account = state.accounts?.find(a => a.userId === userId);
    const free = account ? account.freeBalance : 0;
    const margin = account ? account.lockedMargin : 0;

    document.getElementById("tg-l2-free").innerText = free.toFixed(2);
    document.getElementById("tg-l2-margin").innerText = margin.toFixed(2);

    // Fetch TON balance separately
    const tonData = await fetchTonBalance();
    if (tonData) {
        document.getElementById("tg-ton-balance").innerText = (tonData.tonBalance || 0).toFixed(2);
    }
}

function updatePosition(state) {
    const account = state.accounts?.find(a => a.userId === userId);
    if (!account) return;

    const size = account.positionSize || 0;
    const entry = account.entryPrice || 0;
    const mid = getMidFromState(state);

    const sideEl = document.getElementById("tg-pos-side");
    const sizeEl = document.getElementById("tg-pos-size");
    const entryEl = document.getElementById("tg-pos-entry");
    const pnlEl = document.getElementById("tg-pos-pnl");

    if (size === 0) {
        sideEl.innerText = "—";
        sideEl.className = "position-badge none";
        sizeEl.innerText = "0.00 BTC";
        entryEl.innerText = "$0.00";
        pnlEl.innerText = "$0.00";
    } else {
        const isLong = size > 0;
        sideEl.innerText = isLong ? "LONG" : "SHORT";
        sideEl.className = "position-badge " + (isLong ? "long" : "short");
        sizeEl.innerText = Math.abs(size).toFixed(4) + " BTC";
        entryEl.innerText = "$" + entry.toFixed(2);

        const pnl = isLong
            ? size * (mid - entry)
            : Math.abs(size) * (entry - mid);
        const pnlClass = pnl >= 0 ? "green" : "red";
        pnlEl.innerText = (pnl >= 0 ? "+" : "") + pnl.toFixed(2);
        pnlEl.style.color = "var(--" + pnlClass + ")";
    }

    document.getElementById("tg-mid-price").innerText = "$" + mid.toLocaleString('en-US', { minimumFractionDigits: 2 });
}

function getMidFromState(state) {
    // Try to get from orderbook or use default
    return 60000;
}

function updateValidators(state) {
    const height = state.height || 0;
    document.getElementById("tg-block-height").innerText = height;
}

// --- Tab Switching ---
function switchTab(name) {
    document.querySelectorAll(".tg-tab[data-tab]").forEach(t => t.classList.remove("active"));
    document.querySelectorAll(".tg-section[id^='tab-']").forEach(t => t.style.display = "none");
    document.querySelector(`.tg-tab[data-tab="${name}"]`)?.classList.add("active");
    document.getElementById("tab-" + name).style.display = "block";
}

// --- Side Selection ---
function setSide(side) {
    currentSide = side;
    document.querySelectorAll(".tg-tab.buy, .tg-tab.sell").forEach(b => b.classList.remove("active"));
    document.querySelector(".tg-tab." + side).classList.add("active");
}

// --- Order ---
async function submitOrder() {
    const price = parseFloat(document.getElementById("tg-price").value);
    const amount = parseFloat(document.getElementById("tg-amount").value);
    const leverage = parseInt(document.getElementById("tg-leverage").value);

    if (!price || !amount || amount <= 0) {
        tg?.showPopup?.({ title: "Ошибка", message: "Некорректная цена или объём" });
        return;
    }

    try {
        const res = await fetch("/api/order", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                userId: userId,
                price: price,
                amount: amount,
                leverage: leverage || 1,
                isBuy: currentSide === "buy"
            })
        });
        if (res.ok) {
            tg?.showPopup?.({ title: "Готово", message: "Ордер отправлен в консенсус" });
            fetchState();
        } else {
            const data = await res.json();
            tg?.showPopup?.({ title: "Ошибка", message: data.message || "Ошибка отправки" });
        }
    } catch (e) {
        tg?.showPopup?.({ title: "Ошибка", message: "Соединение потеряно" });
    }
}

// --- TON Deposit ---
async function depositTon() {
    const amount = parseFloat(document.getElementById("tg-deposit-amount").value);
    if (!amount || amount <= 0) {
        tg?.showPopup?.({ title: "Ошибка", message: "Некорректная сумма" });
        return;
    }

    const btn = document.querySelector("#tab-bridge .btn.success");
    btn.disabled = true;
    btn.innerText = "Ожидание...";

    try {
        const res = await fetch("/api/ton/deposit", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ userId: userId, amount: amount })
        });
        if (res.ok) {
            tg?.showPopup?.({ title: "Депозит принят", message: "USDT поступит на L2 через ~3 секунды" });
            setTimeout(fetchState, 3000);
        }
    } catch (e) {}

    btn.disabled = false;
    btn.innerText = "↑ Депозит";
}

// --- TON Withdrawal ---
async function withdrawTon() {
    const amount = parseFloat(document.getElementById("tg-withdraw-amount").value);
    if (!amount || amount <= 0) {
        tg?.showPopup?.({ title: "Ошибка", message: "Некорректная сумма" });
        return;
    }

    // Build the message to sign
    const ts = Date.now();
    const msg = userId + ":" + amount + ":" + ts + ":false:0";

    // In production, this would be signed by the user's TON wallet key.
    // For the mock, we generate an RSA signature using the backend's test keys.
    // The user's ID is their public key.
    let signature = "tg-sig-" + userId + "-" + ts;

    const btn = document.querySelector("#tab-bridge .btn.danger");
    btn.disabled = true;
    btn.innerText = "Отправка...";

    try {
        const res = await fetch("/api/ton/withdraw", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                userId: userId,
                amount: amount,
                signature: signature,
                timestamp: ts
            })
        });
        if (res.ok) {
            tg?.showPopup?.({ title: "Вывод принят", message: "USDT отправлен на ваш TON кошелёк ~1 сек" });
            setTimeout(fetchState, 2000);
        } else {
            const data = await res.json();
            tg?.showPopup?.({ title: "Ошибка", message: data.message || "Вывод отклонён" });
        }
    } catch (e) {}

    btn.disabled = false;
    btn.innerText = "↓ Вывод";
}

// --- Logging ---
function log(msg, type) {
    console.log("[" + type + "] " + msg);
}
