// 顶层设备网格 — 每 3 秒拉一次设备列表,支持批量勾选 + 下发。
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

const selected = new Set();

async function fetchJSON(url, opts) {
  try {
    const res = await fetch(url, opts);
    return await res.json();
  } catch {
    return null;
  }
}

function ago(seconds) {
  if (!seconds) return "—";
  const diff = Date.now() / 1000 - seconds;
  if (diff < 60) return `${diff.toFixed(0)}s 前`;
  if (diff < 3600) return `${(diff / 60).toFixed(0)} 分钟前`;
  if (diff < 86400) return `${(diff / 3600).toFixed(1)} 小时前`;
  return `${(diff / 86400).toFixed(1)} 天前`;
}

function renderDevices(list) {
  const grid = $("#device-grid");
  const empty = $("#empty-state");

  if (!list || list.length === 0) {
    if (empty) empty.style.display = "block";
    [...grid.querySelectorAll(".device-card-tile")].forEach((n) => n.remove());
    selected.clear();
    updateBatchCount();
    return;
  }
  if (empty) empty.style.display = "none";

  const currentIds = new Set(list.map((d) => d.id));
  // drop any selections for devices that disappeared
  [...selected].forEach((id) => {
    if (!currentIds.has(id)) selected.delete(id);
  });

  const tiles = list
    .map((d) => {
      const dotCls = d.online ? "dot-on" : "dot-off";
      const battery = d.battery ? `${d.battery}%` : "—";
      const taskInfo = d.current_task_id
        ? `跑中 (${d.last_task_kind || "task"})`
        : d.last_task_kind === "idle" ? "空闲" : "—";
      const wf = d.override_workflow_id || "—";
      const keyword = d.override_keyword || "—";
      const checked = selected.has(d.id) ? "checked" : "";
      return `
        <div class="device-card-tile" data-id="${escapeHtml(d.id)}">
          <label class="tile-select" onclick="event.stopPropagation()">
            <input type="checkbox" class="tile-checkbox" data-id="${escapeHtml(d.id)}" ${checked}>
          </label>
          <a class="tile-link" href="/devices/${encodeURIComponent(d.id)}">
            <div class="tile-head">
              <h3 class="tile-name">${escapeHtml(d.name || d.id)}</h3>
              <span class="dot ${dotCls}"></span>
            </div>
            <div class="tile-meta">${escapeHtml(d.manufacturer || "")} ${escapeHtml(d.model || "")} · Android ${escapeHtml(d.android || "—")}</div>
            <div class="tile-row"><span class="label">ID</span><span class="value">${escapeHtml(d.id)}</span></div>
            <div class="tile-row"><span class="label">心跳</span><span class="value">${ago(d.last_seen)}</span></div>
            <div class="tile-row"><span class="label">电量</span><span class="value">${battery}</span></div>
            <div class="tile-row"><span class="label">任务</span><span class="value">${escapeHtml(taskInfo)}</span></div>
            <div class="tile-row"><span class="label">workflow</span><span class="value">${escapeHtml(wf)}</span></div>
            <div class="tile-row"><span class="label">关键词</span><span class="value">${escapeHtml(keyword)}</span></div>
          </a>
        </div>
      `;
    })
    .join("");
  const existing = grid.querySelectorAll(".device-card-tile");
  existing.forEach((n) => n.remove());
  grid.insertAdjacentHTML("beforeend", tiles);

  $("#count-badge").textContent = `设备: ${list.length}`;

  // wire checkboxes
  $$(".tile-checkbox").forEach((cb) => {
    cb.addEventListener("change", (e) => {
      const id = e.target.getAttribute("data-id");
      if (e.target.checked) selected.add(id);
      else selected.delete(id);
      updateBatchCount();
    });
  });
  updateBatchCount();
}

function updateBatchCount() {
  $("#batch-count").textContent = String(selected.size);
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function loadWorkflows() {
  const list = await fetchJSON("/api/workflows");
  const sel = $("#batch-workflow");
  if (!list) return;
  sel.innerHTML = list.map((w) => `<option value="${escapeHtml(w.id)}">${escapeHtml(w.name)}</option>`).join("");
}

async function batchRun() {
  if (selected.size === 0) {
    alert("先勾选要批量下发的设备");
    return;
  }
  const workflow_id = $("#batch-workflow").value;
  if (!workflow_id) {
    alert("选个 workflow");
    return;
  }
  const overrides = {};
  const kw = $("#batch-keyword").value.trim();
  if (kw) overrides.keyword = kw;
  const mx = parseInt($("#batch-max-items").value, 10);
  if (!isNaN(mx) && mx > 0) overrides.max_items = mx;

  const res = await fetchJSON("/api/batch/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      device_ids: [...selected],
      workflow_id,
      overrides,
    }),
  });
  if (!res || !res.ok) {
    alert("批量下发失败,看 server log");
    return;
  }
  alert(`下发 ${res.dispatched.length} 台,跳过 ${res.skipped.length} 台`);
}

async function batchStop() {
  if (selected.size === 0) {
    alert("先勾选要停止的设备");
    return;
  }
  await fetchJSON("/api/batch/stop", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device_ids: [...selected], workflow_id: "" }),
  });
  alert(`已对 ${selected.size} 台发停止指令`);
}

$("#batch-run").addEventListener("click", batchRun);
$("#batch-stop").addEventListener("click", batchStop);
$("#batch-select-all").addEventListener("change", (e) => {
  $$(".tile-checkbox").forEach((cb) => {
    cb.checked = e.target.checked;
    const id = cb.getAttribute("data-id");
    if (e.target.checked) selected.add(id);
    else selected.delete(id);
  });
  updateBatchCount();
});

async function refresh() {
  const list = await fetchJSON("/api/devices");
  renderDevices(list);
  const health = await fetchJSON("/api/health");
  if (health) {
    $("#mqtt-badge").textContent = health.mqtt_connected
      ? `MQTT: ${health.mqtt_host}:${health.mqtt_port}`
      : `MQTT: 未连接`;
  }
}

loadWorkflows();
refresh();
setInterval(refresh, 3000);
