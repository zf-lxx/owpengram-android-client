const tg = window.Telegram?.WebApp;
try {
  tg?.ready?.();
  tg?.expand?.();
} catch {
  /* ignore */
}

function applyQwenpawTheme() {
  const scheme =
    tg?.colorScheme ||
    (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  document.documentElement.classList.toggle("dark", scheme === "dark");
}
applyQwenpawTheme();
try {
  tg?.onEvent?.("themeChanged", applyQwenpawTheme);
} catch {
  /* ignore */
}

const $ = (id) => document.getElementById(id);
const banner = $("banner");

function showBanner(text, kind = "") {
  banner.textContent = text;
  banner.className = "banner" + (kind ? " " + kind : "");
  banner.hidden = !text;
}

function initData() {
  return tg?.initData || "";
}

async function api(path, { method = "GET", json, markdown } = {}) {
  const headers = { "X-Telegram-Init-Data": initData() };
  const opts = { method, headers };
  if (json !== undefined) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(json);
  }
  const res = await fetch(path, opts);
  let data = {};
  try {
    data = await res.json();
  } catch {
    data = {};
  }
  if (!res.ok || data.ok === false) {
    throw new Error(data.error || `请求失败 (${res.status})`);
  }
  return data;
}

function sessionLabel(s) {
  if (s === "online") return "在线";
  if (s === "exited") return "已退出";
  return "未创建";
}

function render(state) {
  const st = $("status");
  const bits = [
    `会话：${sessionLabel(state.session)}`,
    `模型：${state.model || "—"}`,
    `工具：${state.tools || "—"}`,
    `技能 ${state.skills?.filter((x) => x.enabled).length || 0}/${state.skills?.length || 0} 已启用`,
    `MCP ${state.mcp?.filter((x) => x.enabled).length || 0}/${state.mcp?.length || 0} 已启用`,
  ];
  if (state.running) bits.push("当前：正在生成");
  if (state.uptimeMin != null) bits.push(`进程已运行 ${state.uptimeMin} 分钟`);
  st.className = "card";
  st.textContent = bits.join("\n");

  const models = $("models");
  models.innerHTML = "";
  for (const id of state.models || []) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "item" + (id === state.model ? " selected" : "");
    btn.innerHTML = `<span class="name">${escapeHtml(id)}</span><span class="hint">${id === state.model ? "当前" : "切换"}</span>`;
    btn.addEventListener("click", () => run(() => api("/admin/api/model", { method: "POST", json: { model: id } })));
    models.appendChild(btn);
  }

  renderToggles($("skills"), state.skills, (s) => ({
    title: s.name,
    desc: `${s.scope === "chat" ? "本聊天" : "公共"} · ${s.description || ""}`,
    enabled: s.enabled,
    onToggle: () =>
      run(() => api("/admin/api/skill/toggle", { method: "POST", json: { name: s.name, enabled: !s.enabled } })),
  }));
  if (!(state.skills || []).length) $("skills").innerHTML = `<div class="card muted">还没有技能。</div>`;

  renderToggles($("mcp"), state.mcp, (s) => ({
    title: s.name,
    desc: s.url || s.description || "",
    enabled: s.enabled,
    onToggle: () =>
      run(() => api("/admin/api/mcp/toggle", { method: "POST", json: { name: s.name, enabled: !s.enabled } })),
  }));
  if (!(state.mcp || []).length) $("mcp").innerHTML = `<div class="card muted">还没有 MCP。用下面的表单添加。</div>`;
}

function renderToggles(root, items, map) {
  root.innerHTML = "";
  for (const raw of items || []) {
    const s = map(raw);
    const row = document.createElement("div");
    row.className = "item";
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.innerHTML = `<div class="name">${escapeHtml(s.title)}</div>${s.desc ? `<div class="desc">${escapeHtml(s.desc)}</div>` : ""}`;
    const sw = document.createElement("button");
    sw.type = "button";
    sw.className = "switch" + (s.enabled ? " on" : "");
    sw.setAttribute("aria-pressed", s.enabled ? "true" : "false");
    sw.addEventListener("click", s.onToggle);
    row.append(meta, sw);
    root.appendChild(row);
  }
}

function escapeHtml(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function run(fn, okMsg) {
  try {
    const state = await fn();
    render(state);
    if (okMsg) showBanner(okMsg, "ok");
    else showBanner("");
    try {
      tg?.HapticFeedback?.impactOccurred?.("light");
    } catch {
      /* ignore */
    }
    return state;
  } catch (err) {
    showBanner(err.message, "error");
    throw err;
  }
}

$("skill-form").addEventListener("submit", (ev) => {
  ev.preventDefault();
  const url = $("skill-url").value.trim();
  if (!url) return showBanner("请填写 SKILL.md 的 URL", "error");
  run(() => api("/admin/api/skill/add", { method: "POST", json: { url } }), "已添加并启用技能").then(() => {
    $("skill-url").value = "";
  }).catch(() => {});
});

$("skill-file").addEventListener("change", async (ev) => {
  const file = ev.target.files?.[0];
  ev.target.value = "";
  if (!file) return;
  try {
    const text = await file.text();
    await run(
      () => api("/admin/api/skill/add", { method: "POST", json: { markdown: text, name: file.name.replace(/\.md$/i, "") } }),
      "已添加并启用技能",
    );
  } catch (err) {
    showBanner(err.message, "error");
  }
});

$("mcp-form").addEventListener("submit", (ev) => {
  ev.preventDefault();
  const name = $("mcp-name").value.trim();
  const url = $("mcp-url").value.trim();
  const hk = $("mcp-header-name").value.trim();
  const hv = $("mcp-header-value").value.trim();
  const body = { name, url };
  if (hk || hv) body.headers = { [hk]: hv };
  run(() => api("/admin/api/mcp/add", { method: "POST", json: body }), "已添加并启用 MCP")
    .then(() => {
      $("mcp-name").value = "";
      $("mcp-url").value = "";
      $("mcp-header-name").value = "";
      $("mcp-header-value").value = "";
    })
    .catch(() => {});
});

$("btn-reset").addEventListener("click", () => {
  const ok = window.confirm("确定开新会话？只会清空对话，不会改模型、技能或 MCP。");
  if (!ok) return;
  run(() => api("/admin/api/reset", { method: "POST", json: {} }), "已开新会话，上下文已清空").catch(() => {});
});

async function boot() {
  if (!initData()) {
    showBanner("请在 Telegram 里打开这个管理页（菜单按钮或 /settings）。", "error");
    $("status").textContent = "未从 Telegram 打开，无法校验身份。";
    return;
  }
  await run(() => api("/admin/api/state")).catch(() => {});
}

boot();
