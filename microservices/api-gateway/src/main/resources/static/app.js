const gatewayStatus = document.getElementById("gatewayStatus");
const tokenState = document.getElementById("tokenState");
const roleState = document.getElementById("roleState");
const serviceStatusGrid = document.getElementById("serviceStatusGrid");
const kafkaStatus = document.getElementById("kafkaStatus");
const kafkaNotificationsGrid = document.getElementById("kafkaNotificationsGrid");
const tokenBox = document.getElementById("tokenBox");
const apiLog = document.getElementById("apiLog");
const customerView = document.getElementById("customerView");
const adminView = document.getElementById("adminView");
const monitoringControllerUrl = "http://127.0.0.1:8767";

const serviceHealthChecks = [
  { label: "Gateway", path: "/actuator/health" },
    { label: "Auth", path: "/health/auth" },
  { label: "Catalog", path: "/health/catalog" },
  { label: "Inventory", path: "/health/inventory" },
  { label: "Cart", path: "/health/cart" },
  { label: "Payment", path: "/health/payment" },
  { label: "Order", path: "/health/order" },
  { label: "Notifications", path: "/health/notifications" }
];

let accessToken = "";
let claims = {};
let kafkaPollTimer = null;
let monitoringState = { prometheus: false, grafana: false, otel: false };
const kafkaSnapshots = {
  paymentOrderEvents: [],
  inventoryOrderEvents: [],
  orderPaymentEvents: []
};

function parseJwtClaims(jwt) {
  try {
    const part = jwt.split(".")[1];
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const pad = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    return JSON.parse(atob(pad));
  } catch {
    return {};
  }
}

function roles() {
  return Array.isArray(claims.roles) ? claims.roles.map((r) => String(r).toUpperCase()) : [];
}

function hasRole(role) {
  return roles().includes(role.toUpperCase());
}

function currentUser() {
  return claims.sub || "";
}

function authHeaders() {
  return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}

function monitoringHeaders() {
  const token = document.getElementById("monitoringToken").value.trim();
  return token ? { "X-Monitoring-Token": token } : {};
}

function renderMonitoringState() {
  ["prometheus", "grafana", "otel"].forEach((name) => {
    document.getElementById(`${name}Switch`).checked = Boolean(monitoringState[name]);
    document.getElementById(`${name}State`).textContent = monitoringState[name] ? "ON" : "OFF";
  });
}

async function refreshMonitoringState() {
  try {
    const response = await fetch(`${monitoringControllerUrl}/status`, { headers: monitoringHeaders() });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    monitoringState = await response.json();
    renderMonitoringState();
    document.getElementById("monitoringControlStatus").textContent = "Local controller connected.";
  } catch (err) {
    document.getElementById("monitoringControlStatus").textContent = `Controller unavailable: ${err.message}`;
  }
}

async function setMonitoring(name, enabled) {
  const control = document.getElementById(`${name}Switch`);
  control.disabled = true;
  try {
    const response = await fetch(`${monitoringControllerUrl}/${name}/${enabled ? "start" : "stop"}`, {
      method: "POST",
      headers: monitoringHeaders()
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
    monitoringState = data;
    renderMonitoringState();
    document.getElementById("monitoringControlStatus").textContent = `${name} ${enabled ? "started" : "stopped"}.`;
  } catch (err) {
    control.checked = !enabled;
    document.getElementById("monitoringControlStatus").textContent = `Control failed: ${err.message}`;
  } finally {
    control.disabled = false;
  }
}

function setLog(message, data) {
  apiLog.textContent = data ? `${message}\n${JSON.stringify(data, null, 2)}` : message;
}

function renderTable(data) {
  if (!Array.isArray(data) || !data.length) {
    return "<p>No records found.</p>";
  }
  const keys = Object.keys(data[0]);
  const header = keys.map((k) => `<th>${k}</th>`).join("");
  const rows = data.map((row) => `<tr>${keys.map((k) => `<td>${row[k]}</td>`).join("")}</tr>`).join("");
  return `<div class="table-wrap"><table><thead><tr>${header}</tr></thead><tbody>${rows}</tbody></table></div>`;
}

function updateAuthUi() {
  tokenState.textContent = accessToken ? "Available" : "Missing";
  tokenState.style.color = accessToken ? "#1d9e75" : "#c84545";
  const r = roles();
  roleState.textContent = r.length ? r.join(",") : "None";
  roleState.style.color = r.length ? "#1d9e75" : "#c84545";

  document.getElementById("loadProductsBtn").disabled = !hasRole("USER") && !hasRole("ADMIN");
  document.getElementById("viewCartBtn").disabled = !hasRole("USER") && !hasRole("ADMIN");
  document.getElementById("checkoutBtn").disabled = !hasRole("USER") && !hasRole("ADMIN");
  document.getElementById("myOrdersBtn").disabled = !hasRole("USER") && !hasRole("ADMIN");

  const adminDisabled = !hasRole("ADMIN");
  ["addProductBtn", "restockBtn", "allOrdersBtn", "paymentStatsBtn", "observabilityBtn"].forEach((id) => {
    document.getElementById(id).disabled = adminDisabled;
  });
}

async function api(path, options = {}) {
  const headers = { ...authHeaders(), ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }

  const response = await fetch(path, {
    ...options,
    headers
  });

  let data = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }

  if (!response.ok) {
    throw new Error(JSON.stringify(data || { status: response.status }));
  }

  return data;
}

function newEventCount(prevList, currentList) {
  return Math.max(0, currentList.length - prevList.length);
}

function displayValue(value, fallback = "NA") {
  if (value === null || value === undefined || value === "") {
    return fallback;
  }
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (Array.isArray(value)) {
    return value.map((item) => displayValue(item, "")).filter(Boolean).join(", ");
  }
  if (typeof value === "object") {
    for (const key of ["value", "string", "text", "val"]) {
      if (Object.prototype.hasOwnProperty.call(value, key)) {
        return displayValue(value[key], fallback);
      }
    }
    try {
      return JSON.stringify(value);
    } catch {
      return fallback;
    }
  }
  return String(value);
}

function safeLastEvent(list) {
  if (!Array.isArray(list) || !list.length) {
    return "No events yet.";
  }
  const wrapper = list[list.length - 1] || {};
  const payload = wrapper.payload && typeof wrapper.payload === "object" ? wrapper.payload : wrapper;
  const orderId = displayValue(payload.orderId);
  const eventType = displayValue(payload.eventType, "EVENT");
  const createdAt = displayValue(payload.createdAt || wrapper.receivedAt, "time-na");
  return `${eventType} | orderId=${orderId} | ${createdAt}`;
}

function renderKafkaCards(payload) {
  const cards = [
    {
      title: "Payment <- orders.created",
      key: "paymentOrderEvents",
      events: payload.paymentOrderEvents || []
    },
    {
      title: "Inventory <- orders.created",
      key: "inventoryOrderEvents",
      events: payload.inventoryOrderEvents || []
    },
    {
      title: "Order <- payments.processed",
      key: "orderPaymentEvents",
      events: payload.orderPaymentEvents || []
    }
  ];

  kafkaNotificationsGrid.innerHTML = cards.map((card) => {
    const prev = kafkaSnapshots[card.key] || [];
    const delta = newEventCount(prev, card.events);
    const total = card.events.length;
    const summary = safeLastEvent(card.events);
    return `<div class="kafka-card">
      <h3>${card.title}</h3>
      <p><strong>Total:</strong> ${total}</p>
      <p><strong>New:</strong> <span class="badge ${delta > 0 ? "up" : "neutral"}">${delta}</span></p>
      <p class="hint">${summary}</p>
    </div>`;
  }).join("");

  kafkaSnapshots.paymentOrderEvents = payload.paymentOrderEvents || [];
  kafkaSnapshots.inventoryOrderEvents = payload.inventoryOrderEvents || [];
  kafkaSnapshots.orderPaymentEvents = payload.orderPaymentEvents || [];
}

async function refreshKafkaNotifications() {
  if (!accessToken) {
    kafkaStatus.textContent = "Login to start Kafka event monitoring.";
    kafkaNotificationsGrid.innerHTML = "";
    return;
  }

  try {
    const [paymentOrderEvents, inventoryOrderEvents, orderPaymentEvents] = await Promise.all([
      api("/payments/events/orders", { method: "GET" }),
      api("/inventory/events/orders", { method: "GET" }),
      api("/orders/events/payments", { method: "GET" })
    ]);

    renderKafkaCards({
      paymentOrderEvents,
      inventoryOrderEvents,
      orderPaymentEvents
    });
    kafkaStatus.textContent = "Kafka notifications live.";
  } catch (err) {
    kafkaStatus.textContent = "Kafka notification polling failed.";
    setLog("Kafka polling failed", { error: err.message });
  }
}

function startKafkaPolling() {
  if (kafkaPollTimer) {
    clearInterval(kafkaPollTimer);
  }
  refreshKafkaNotifications();
  kafkaPollTimer = setInterval(refreshKafkaNotifications, 5000);
}

function stopKafkaPolling() {
  if (kafkaPollTimer) {
    clearInterval(kafkaPollTimer);
    kafkaPollTimer = null;
  }
  kafkaSnapshots.paymentOrderEvents = [];
  kafkaSnapshots.inventoryOrderEvents = [];
  kafkaSnapshots.orderPaymentEvents = [];
  kafkaNotificationsGrid.innerHTML = "";
  kafkaStatus.textContent = "Login to start Kafka event monitoring.";
}

async function checkService(path) {
  try {
    const res = await fetch(path);
    return res.ok;
  } catch {
    return false;
  }
}

async function refreshServiceBoard() {
  const statuses = await Promise.all(
    serviceHealthChecks.map(async (svc) => ({
      ...svc,
      up: await checkService(svc.path)
    }))
  );

  serviceStatusGrid.innerHTML = statuses
    .map((s) => `<div class="service-card"><span>${s.label}</span><span class="badge ${s.up ? "up" : "down"}">${s.up ? "UP" : "DOWN"}</span></div>`)
    .join("");

  const gw = statuses.find((x) => x.label === "Gateway");
  gatewayStatus.textContent = gw && gw.up ? "UP" : "DOWN";
  gatewayStatus.style.color = gw && gw.up ? "#1d9e75" : "#c84545";
}

async function loadProducts() {
  try {
    const products = await api("/catalog/items", { method: "GET" });
    customerView.innerHTML = `<div class="product-grid">${products.map((p) => `
      <div class="product">
        <strong>${p.name}</strong>
        <p>ID: ${p.id}</p>
        <p>Category: ${p.category}</p>
        <p>Price: $${p.price}</p>
        <input id="qty-${p.id}" type="number" min="1" value="1" />
        <button onclick='addToCart(${JSON.stringify(p)})'>Add to Cart</button>
      </div>`).join("")}</div>`;
    setLog("Catalog loaded", products);
  } catch (err) {
    setLog("Failed to load catalog", { error: err.message });
  }
}

window.addToCart = async function addToCart(product) {
  const qty = Number(document.getElementById(`qty-${product.id}`).value || 1);
  try {
    const result = await api(`/cart/${currentUser()}/items`, {
      method: "POST",
      body: JSON.stringify({
        productId: product.id,
        name: product.name,
        quantity: qty,
        price: product.price
      })
    });
    setLog("Item added to cart", result);
  } catch (err) {
    setLog("Add to cart failed", { error: err.message });
  }
};

async function viewCart() {
  try {
    const cart = await api(`/cart/${currentUser()}`, { method: "GET" });
    const items = cart.items || [];
    customerView.innerHTML = `${renderTable(items)}<p><strong>Total:</strong> $${cart.total ?? 0}</p>`;
    setLog("Cart loaded", cart);
  } catch (err) {
    setLog("Cart load failed", { error: err.message });
  }
}

async function checkout() {
  try {
    const order = await api("/orders/checkout", {
      method: "POST",
      headers: { "X-Idempotency-Key": `web-${currentUser()}-${Date.now()}` },
      body: JSON.stringify({ userId: currentUser(), paymentMethod: "CARD" })
    });
    customerView.innerHTML = renderTable([{
      orderId: order.orderId,
      userId: order.userId,
      total: order.total,
      status: order.status,
      createdAt: order.createdAt
    }]);
    setLog("Checkout successful", order);
  } catch (err) {
    setLog("Checkout failed", { error: err.message });
  }
}

async function myOrders() {
  try {
    const orders = await api(`/orders/user/${currentUser()}`, { method: "GET" });
    customerView.innerHTML = renderTable(orders.map((o) => ({
      orderId: o.orderId,
      total: o.total,
      status: o.status,
      createdAt: o.createdAt
    })));
    setLog("Loaded user orders", orders);
  } catch (err) {
    setLog("Load my orders failed", { error: err.message });
  }
}

async function adminAllOrders() {
  try {
    const orders = await api("/orders/admin/all", { method: "GET" });
    adminView.innerHTML = renderTable(orders.map((o) => ({
      orderId: o.orderId,
      userId: o.userId,
      total: o.total,
      status: o.status,
      createdAt: o.createdAt
    })));
    setLog("Loaded all orders", orders);
  } catch (err) {
    setLog("Load all orders failed", { error: err.message });
  }
}

async function paymentStats() {
  try {
    const stats = await api("/payments/admin/stats", { method: "GET" });
    adminView.innerHTML = renderTable([{
      totalTransactions: stats.totalTransactions,
      approvedTransactions: stats.approvedTransactions,
      revenue: stats.revenue
    }]);
    setLog("Payment stats loaded", stats);
  } catch (err) {
    setLog("Payment stats failed", { error: err.message });
  }
}

async function observabilitySnapshot() {
  try {
    const snapshot = await api("/notifications/admin/observability", { method: "GET" });
    adminView.innerHTML = renderTable([snapshot]);
    setLog("Observability snapshot loaded", snapshot);
  } catch (err) {
    setLog("Observability snapshot failed", { error: err.message });
  }
}

document.getElementById("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const data = await api("/auth/token", {
      method: "POST",
      headers: {},
      body: JSON.stringify({
        username: document.getElementById("username").value.trim(),
        password: document.getElementById("password").value
      })
    });
    accessToken = data.access_token || "";
    claims = parseJwtClaims(accessToken);
    tokenBox.textContent = JSON.stringify({ token: data, claims }, null, 2);
    setLog("Login success", data);
    startKafkaPolling();
  } catch (err) {
    accessToken = "";
    claims = {};
    tokenBox.textContent = "Login failed.";
    setLog("Login failed", { error: err.message });
    stopKafkaPolling();
  }
  updateAuthUi();
});

document.getElementById("logoutBtn").addEventListener("click", () => {
  accessToken = "";
  claims = {};
  tokenBox.textContent = "No token.";
  customerView.innerHTML = "";
  adminView.innerHTML = "";
  setLog("Logged out");
  stopKafkaPolling();
  updateAuthUi();
});

document.getElementById("loadProductsBtn").addEventListener("click", loadProducts);
document.getElementById("viewCartBtn").addEventListener("click", viewCart);
document.getElementById("checkoutBtn").addEventListener("click", checkout);
document.getElementById("myOrdersBtn").addEventListener("click", myOrders);
document.getElementById("allOrdersBtn").addEventListener("click", adminAllOrders);
document.getElementById("paymentStatsBtn").addEventListener("click", paymentStats);
document.getElementById("observabilityBtn").addEventListener("click", observabilitySnapshot);
document.getElementById("monitoringToken").addEventListener("change", refreshMonitoringState);
["prometheus", "grafana", "otel"].forEach((name) => {
  document.getElementById(`${name}Switch`).addEventListener("change", (event) => setMonitoring(name, event.target.checked));
});

document.getElementById("addProductForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const payload = {
      id: Number(document.getElementById("prodId").value),
      name: document.getElementById("prodName").value,
      price: Number(document.getElementById("prodPrice").value),
      category: document.getElementById("prodCategory").value,
      active: true
    };
    const result = await api("/catalog/admin/items", { method: "POST", body: JSON.stringify(payload) });
    setLog("Product created", result);
  } catch (err) {
    setLog("Create product failed", { error: err.message });
  }
});

document.getElementById("restockForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const payload = {
      productId: Number(document.getElementById("stockProductId").value),
      quantity: Number(document.getElementById("stockQty").value)
    };
    const result = await api("/inventory/admin/restock", { method: "POST", body: JSON.stringify(payload) });
    setLog("Inventory restocked", result);
  } catch (err) {
    setLog("Restock failed", { error: err.message });
  }
});

refreshServiceBoard();
setInterval(refreshServiceBoard, 10000);
updateAuthUi();
stopKafkaPolling();
renderMonitoringState();

