// Dashboard frontend. Vanilla JS, no framework.
// Streams logs via SSE, polls device + task state on a timer.

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
  btnInspect: $("#btn-inspect"),
  btnWorkflow: $("#btn-workflow"),
  btnStop: $("#btn-stop"),
  screenshot: $("#screenshot"),
  screenshotEmpty: $("#screenshot-empty"),
  btnRefreshScreenshot: $("#btn-refresh-screenshot"),
};

// ---------- helpers ----------
function timeStr(ts) {
  const d = new Date(ts * 1000);
  return d.toTimeString().slice(0, 8);
}

function appendLog(msg) {
  const line = document.createElement("div");
  line.className = "log-line";
  if (/error|failed|fail/i.test(msg)) line.classList.add("err");
  else if (/warn|stop requested|skip/i.test(msg)) line.classList.add("warn");
  line.textContent = msg;
  els.logStream.appendChild(line);
  // cap DOM nodes
  while (els.logStream.childElementCount > 600) {
    els.logStream.removeChild(els.logStream.firstChild);
  }
  els.logStream.scrollTop = els.logStream.scrollHeight;
}

async function fetchJSON(url, opts = {}) {
  try {
    const res = await fetch(url, opts);
    return await res.json();
  } catch (err) {
    return null;
  }
}

// ---------- device status (poll every 3s) ----------
async function refreshDevice() {
  const data = await fetchJSON("/api/device");
  if (!data) {
    els.deviceDot.className = "dot dot-off";
    els.deviceInfo.innerHTML = '<dt>状态</dt><dd>请求失败</dd>';
    return;
  }
  if (!data.connected) {
    els.deviceDot.className = data.status === "unauthorized" ? "dot dot-warn" : "dot dot-off";
    const reason = data.reason || data.status || "未连接";
    els.deviceInfo.innerHTML = `
      <dt>状态</dt><dd>${reason}</dd>
      <dt>提示</dt><dd>插上 USB,在手机上点"允许 USB 调试"</dd>
    `;
    return;
  }
  els.deviceDot.className = "dot dot-on";
  els.deviceInfo.innerHTML = `
    <dt>序列号</dt><dd>${data.serial}</dd>
    <dt>厂商</dt><dd>${data.manufacturer || "—"}</dd>
    <dt>型号</dt><dd>${data.model || "—"}</dd>
    <dt>Android</dt><dd>${data.android || "—"}</dd>
    <dt>IP</dt><dd>${data.ip || "—"}</dd>
    <dt>MAC</dt><dd>${data.mac || "—"}</dd>
    <dt>电量</dt><dd>${data.battery ?? "—"}${data.battery !== undefined ? "%" : ""}</dd>
    <dt>分辨率</dt><dd>${data.resolution || "—"}</dd>
    <dt>前台</dt><dd>${data.foreground_pkg || "—"}</dd>
    <dt>屏幕</dt><dd>${data.screen_on ? "亮" : "暗"}</dd>
  `;
}

// ---------- task state (poll every 1.5s) ----------
async function refreshState() {
  const s = await fetchJSON("/api/state");
  if (!s) return;
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
    els.elapsed.textContent = `上次耗时 ${dur}s`;
  } else {
    els.elapsed.textContent = "未开始";
  }

  els.btnInspect.disabled = running;
  els.btnWorkflow.disabled = running;
  els.btnStop.disabled = !running;

  // pull latest screenshot if state has changed it
  if (s.last_screenshot && s.last_screenshot !== els.screenshot.dataset.path) {
    els.screenshot.dataset.path = s.last_screenshot;
    refreshScreenshot();
  }
}

// ---------- screenshot ----------
function refreshScreenshot() {
  const url = `/api/screenshot/latest?_=${Date.now()}`;
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

// ---------- logs (SSE) ----------
function connectLogStream() {
  const es = new EventSource("/api/logs/stream");
  es.onmessage = (ev) => {
    if (!ev.data) return;
    try {
      const { msg } = JSON.parse(ev.data);
      appendLog(msg);
    } catch (e) {
      appendLog(ev.data);
    }
  };
  es.onerror = () => {
    appendLog("[dashboard] log stream disconnected, retrying in 3s");
    es.close();
    setTimeout(connectLogStream, 3000);
  };
}

// ---------- buttons ----------
els.btnInspect.addEventListener("click", async () => {
  const body = { workflow: els.workflowSelect.value };
  const r = await fetchJSON("/api/run/inspect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (r && !r.ok) appendLog(`[dashboard] inspect rejected: ${r.msg}`);
});

els.btnWorkflow.addEventListener("click", async () => {
  const body = {
    workflow: els.workflowSelect.value,
    keyword: els.keyword.value.trim() || undefined,
    max_items: Number(els.maxItems.value) || undefined,
  };
  const r = await fetchJSON("/api/run/workflow", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (r && !r.ok) appendLog(`[dashboard] workflow rejected: ${r.msg}`);
});

els.btnStop.addEventListener("click", async () => {
  await fetchJSON("/api/run/stop", { method: "POST" });
});

els.btnClearLogs.addEventListener("click", () => {
  els.logStream.innerHTML = "";
});

els.btnRefreshScreenshot.addEventListener("click", refreshScreenshot);

// ---------- workflows ----------
async function loadWorkflows() {
  const list = await fetchJSON("/api/workflows");
  if (!Array.isArray(list) || list.length === 0) {
    els.workflowDesc.textContent = "(workflows/ 目录下没有工作流,加一个 yaml 即可)";
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
    els.workflowSelect.appendChild(opt);
  }
  applyWorkflow();
}

function applyWorkflow() {
  const opt = els.workflowSelect.selectedOptions[0];
  if (!opt) return;
  els.keyword.value = opt.dataset.keyword || "";
  els.maxItems.value = opt.dataset.maxItems || 1;
  els.workflowDesc.textContent = opt.dataset.description || "";
  els.configBadge.textContent = `工作流: ${opt.value}`;
}

els.workflowSelect.addEventListener("change", applyWorkflow);

// ---------- bootstrap ----------
loadWorkflows();
refreshDevice();
refreshState();
refreshScreenshot();
connectLogStream();

setInterval(refreshDevice, 3000);
setInterval(refreshState, 1500);
