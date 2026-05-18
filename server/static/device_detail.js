// 单设备控制台 — 基于 V9.1 dashboard.js 改造,所有 API 加 /api/devices/{id}/ 前缀,
// 参数持久化从 localStorage 改成服务器 /api/devices/{id}/overrides。

const DEVICE_ID = document.body.dataset.deviceId;
const API = `/api/devices/${encodeURIComponent(DEVICE_ID)}`;

const $ = (sel) => document.querySelector(sel);

const els = {
  deviceDot: $("#device-dot"),
  deviceInfo: $("#device-info"),
  deviceStatus: $("#device-status"),
  configBadge: $("#config-badge"),
  logStream: $("#log-stream"),
  btnClearLogs: $("#btn-clear-logs"),
  taskKind: $("#task-kind"),
  progressFill: $("#progress-fill"),
  progressText: $("#progress-text"),
  elapsed: $("#elapsed"),
  workflowSelect: $("#workflow"),
  workflowDesc: $("#workflow-desc"),
  keyword: $("#keyword"),
  maxItems: $("#max-items"),
  dmTemplate: $("#dm-template"),
  btnInspect: $("#btn-inspect"),
  btnWorkflow: $("#btn-workflow"),
  btnStop: $("#btn-stop"),
  screenshot: $("#screenshot"),
  screenshotEmpty: $("#screenshot-empty"),
  btnRefreshScreenshot: $("#btn-refresh-screenshot"),
};

let latestState = {};
let savedOverrides = {};
// Throttle auto-refresh of the latest screenshot. During a workflow run, every
// step uploads a new PNG (1-2 MB each) — if dashboard re-downloads on every state
// poll (1.5s), the user's bandwidth queues up and ALL other requests stall behind.
// User can still tap the 刷新 button for an instant fresh fetch.
let lastAutoShotRefresh = 0;
const AUTO_SHOT_REFRESH_MS = 10000;
// After 点停止, lock out Start for a brief window so the phone has time to
// actually unwind (workflow thread interrupt + shell kill, see AgentService).
// Mirrors AgentService onTask preempt's 5s join + small buffer.
let stopLockoutUntil = 0;
const STOP_LOCKOUT_MS = 6000;

// ---------------- helpers ----------------
function appendLog(msg) {
  const line = document.createElement("div");
  line.className = "log-line";
  if (/error|failed|fail/i.test(msg)) line.classList.add("err");
  else if (/warn|stop requested|skip/i.test(msg)) line.classList.add("warn");
  line.textContent = msg;
  els.logStream.appendChild(line);
  while (els.logStream.childElementCount > 600) els.logStream.removeChild(els.logStream.firstChild);
  els.logStream.scrollTop = els.logStream.scrollHeight;
}

async function fetchJSON(url, opts = {}) {
  try {
    const res = await fetch(url, opts);
    return await res.json();
  } catch {
    return null;
  }
}

// ---------------- device status (poll 3s) ----------------
async function refreshDevice() {
  const data = await fetchJSON(`${API}`);
  if (!data) {
    els.deviceDot.className = "dot dot-off";
    els.deviceInfo.innerHTML = "<dt>状态</dt><dd>请求失败</dd>";
    return;
  }
  if (!data.online) {
    els.deviceDot.className = "dot dot-off";
    const reason = data.registered ? `离线 (最后心跳 ${data.last_seen ? new Date(data.last_seen*1000).toLocaleString() : "无"})` : "未注册";
    els.deviceInfo.innerHTML = `
      <dt>状态</dt><dd>${reason}</dd>
      <dt>提示</dt><dd>等 APK 端连上 MQTT 发心跳</dd>
    `;
    return;
  }
  els.deviceDot.className = "dot dot-on";
  els.deviceInfo.innerHTML = `
    <dt>ID</dt><dd>${data.id}</dd>
    <dt>厂商</dt><dd>${data.manufacturer || "—"}</dd>
    <dt>型号</dt><dd>${data.model || "—"}</dd>
    <dt>Android</dt><dd>${data.android || "—"}</dd>
    <dt>IP</dt><dd>${data.ip || "—"}</dd>
    <dt>电量</dt><dd>${data.battery ? data.battery + "%" : "—"}</dd>
    <dt>最后心跳</dt><dd>${data.last_seen ? new Date(data.last_seen*1000).toLocaleString() : "—"}</dd>
  `;
  // remember server-side persisted overrides so we can hydrate the form once on load
  savedOverrides = {
    workflow_id: data.override_workflow_id || "",
    keyword: data.override_keyword || "",
    max_items: data.override_max_items || 0,
    dm_template: data.override_dm_template || "",
  };
}

// ---------------- task state (poll 1.5s) ----------------
async function refreshState() {
  const s = await fetchJSON(`${API}/state`);
  if (!s) return;
  latestState = s;
  const running = !!s.running;
  els.taskKind.textContent = running ? (s.task_kind || "运行中") : "空闲";
  els.taskKind.classList.toggle("running", running);

  const total = Math.max(1, s.total || 1);
  const cur = Math.min(s.current_index || 0, total);
  const pct = (cur / total) * 100;
  els.progressFill.style.width = `${pct.toFixed(1)}%`;
  els.progressText.textContent = `${s.current_index || 0} / ${s.total || 0}`;

  if (s.elapsed_seconds !== undefined) {
    els.elapsed.textContent = `已运行 ${s.elapsed_seconds.toFixed(1)}s`;
  } else if (s.started_at && s.ended_at) {
    const dur = (s.ended_at - s.started_at).toFixed(1);
    els.elapsed.textContent = `上次耗时 ${dur}s · ${s.status || ""}`;
  } else {
    els.elapsed.textContent = "未开始";
  }

  const lockedOut = Date.now() < stopLockoutUntil;
  els.btnInspect.disabled = running || lockedOut;
  els.btnWorkflow.disabled = running || lockedOut;
  els.btnStop.disabled = !running;

  if (s.last_screenshot && s.last_screenshot !== els.screenshot.dataset.path) {
    const now = Date.now();
    if (now - lastAutoShotRefresh >= AUTO_SHOT_REFRESH_MS) {
      lastAutoShotRefresh = now;
      els.screenshot.dataset.path = s.last_screenshot;
      refreshScreenshot();
    }
  }
}

// ---------------- screenshot ----------------
function refreshScreenshot() {
  const url = `${API}/screenshots/latest?_=${Date.now()}`;
  els.screenshot.onload = () => {
    els.screenshotEmpty.style.display = "none";
    els.screenshot.style.display = "block";
  };
  els.screenshot.onerror = () => {
    els.screenshot.style.display = "none";
    els.screenshotEmpty.style.display = "block";
  };
  els.screenshot.src = url;
}

// ---------------- logs (SSE) ----------------
function connectLogStream() {
  const es = new EventSource(`${API}/logs/stream`);
  es.onmessage = (ev) => {
    if (!ev.data) return;
    try {
      const { msg } = JSON.parse(ev.data);
      appendLog(msg);
    } catch {
      appendLog(ev.data);
    }
  };
  es.onerror = () => {
    appendLog("[dashboard] log stream disconnected, retrying in 3s");
    es.close();
    setTimeout(connectLogStream, 3000);
  };
}

// ---------------- buttons ----------------
els.btnInspect.addEventListener("click", async () => {
  const body = { workflow_id: els.workflowSelect.value };
  const r = await fetchJSON(`${API}/run/inspect`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (r && r.ok === false) appendLog(`[dashboard] inspect rejected`);
});

els.btnWorkflow.addEventListener("click", async () => {
  const overrides = {
    keyword: els.keyword.value.trim() || null,
    max_items: Number(els.maxItems.value) || null,
    dm_template: els.dmTemplate.value.trim() || null,
  };
  const body = {
    workflow_id: els.workflowSelect.value,
    overrides,
  };
  const r = await fetchJSON(`${API}/run/workflow`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (r && r.ok === false) appendLog(`[dashboard] workflow rejected`);
});

els.btnStop.addEventListener("click", async () => {
  // Lock out the start buttons for STOP_LOCKOUT_MS so user can't spam Start
  // before phone has actually killed the current workflow + shell. Without
  // this, every premature start dispatches a new task that gets preempted /
  // queued, generating spam tasks (see BUGS.md re: stale pending tasks).
  stopLockoutUntil = Date.now() + STOP_LOCKOUT_MS;
  els.btnWorkflow.disabled = true;
  els.btnInspect.disabled = true;
  await fetch(`${API}/run/stop`, { method: "POST" });
});

els.btnClearLogs.addEventListener("click", () => {
  els.logStream.innerHTML = "";
});

els.btnRefreshScreenshot.addEventListener("click", refreshScreenshot);

// ---------------- screenshot history (failure replay) ----------------
async function refreshHistory() {
  const list = await fetchJSON(`${API}/screenshots?limit=24`);
  const root = document.getElementById("screenshot-history");
  if (!root) return;
  if (!Array.isArray(list) || list.length === 0) {
    root.innerHTML = `<p class="empty">还没有截图。</p>`;
    return;
  }
  root.innerHTML = list
    .map((s) => {
      const t = new Date(s.mtime * 1000);
      const stamp = `${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}:${String(t.getSeconds()).padStart(2, "0")}`;
      const isFail = /^fail_/.test(s.name) || /_fail_/.test(s.name);
      return `
        <a class="history-item ${isFail ? "history-item-fail" : ""}" href="${s.url}" target="_blank">
          <img loading="lazy" src="${s.url}?_=${Math.floor(s.mtime)}" alt="${s.name}">
          <span class="history-meta">${stamp} · ${s.name}</span>
        </a>
      `;
    })
    .join("");
}
const btnRefreshHistory = document.getElementById("btn-refresh-history");
if (btnRefreshHistory) btnRefreshHistory.addEventListener("click", refreshHistory);
setInterval(refreshHistory, 8000);
refreshHistory();

// ---------------- workflows ----------------
async function loadWorkflows() {
  const list = await fetchJSON("/api/workflows");
  if (!Array.isArray(list) || list.length === 0) {
    els.workflowDesc.textContent = "(workflows/ 目录下没有工作流)";
    return;
  }
  els.workflowSelect.innerHTML = "";
  for (const w of list) {
    const opt = document.createElement("option");
    opt.value = w.id;
    opt.textContent = w.name;
    opt.dataset.keyword = w.default_keyword || "";
    opt.dataset.maxItems = w.default_max_items || 1;
    opt.dataset.description = w.description || "";
    opt.dataset.dmTemplate = w.default_dm_template || "";
    els.workflowSelect.appendChild(opt);
  }
  // hydrate from server-persisted overrides if present, else yaml defaults
  if (savedOverrides.workflow_id && [...els.workflowSelect.options].some((o) => o.value === savedOverrides.workflow_id)) {
    els.workflowSelect.value = savedOverrides.workflow_id;
  }
  applyWorkflow(/*useSaved=*/true);
}

function applyWorkflow(useSaved = false) {
  const opt = els.workflowSelect.selectedOptions[0];
  if (!opt) return;
  if (useSaved && savedOverrides.keyword) els.keyword.value = savedOverrides.keyword;
  else els.keyword.value = opt.dataset.keyword || "";
  if (useSaved && savedOverrides.max_items) els.maxItems.value = savedOverrides.max_items;
  else els.maxItems.value = opt.dataset.maxItems || 1;
  if (useSaved && savedOverrides.dm_template) els.dmTemplate.value = savedOverrides.dm_template;
  else els.dmTemplate.value = opt.dataset.dmTemplate || "";
  els.workflowDesc.textContent = opt.dataset.description || "";
  els.configBadge.textContent = `workflow: ${opt.value}`;
}

// ---------------- server-side persistence on form input ----------------
async function persistOverrides(partial) {
  await fetch(`${API}/overrides`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(partial),
  }).catch(() => {});
}

let saveTimer = null;
function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    persistOverrides({
      workflow_id: els.workflowSelect.value,
      keyword: els.keyword.value,
      max_items: Number(els.maxItems.value) || 0,
      dm_template: els.dmTemplate.value,
    });
  }, 800);
}

els.keyword.addEventListener("input", scheduleSave);
els.maxItems.addEventListener("input", scheduleSave);
els.dmTemplate.addEventListener("input", scheduleSave);
els.workflowSelect.addEventListener("change", () => {
  applyWorkflow(/*useSaved=*/false);
  scheduleSave();
});

// ---------------- bootstrap ----------------
(async () => {
  await refreshDevice();  // hydrate savedOverrides first
  await loadWorkflows();  // then build form using overrides
  refreshState();
  refreshScreenshot();
  connectLogStream();
  setInterval(refreshDevice, 3000);
  setInterval(refreshState, 1500);
})();
