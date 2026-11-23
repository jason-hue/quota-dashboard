/* 额度看板 — 纯前端，凭证只存本机 localStorage */
(function () {
  'use strict';

  var KEY = 'quota-board-v1';
  var SCHEMA = 2; // v1 默认塞了 3 个占位账号；v2 初始为空，添加后才显示
  var PROVIDERS = {
    'glm-cn':     { label: 'GLM Coding Plan', color: '#3b6ff5', badgeBg: '#e7efff', base: 'https://open.bigmodel.cn' },
    'glm-global': { label: 'Z.AI GLM',        color: '#7c5cfa', badgeBg: '#f0ebff', base: 'https://api.z.ai' },
    'deepseek':   { label: 'DeepSeek',        color: '#2f6bff', badgeBg: '#e7efff', base: 'https://api.deepseek.com' }
  };

  var store = load();
  var editingId = null;
  var detailId = null;
  var refreshTimer = null;
  var sortable = null;
  var justDragged = false;   // 拖拽刚结束时抑制 click 误开详情

  // ---------------- 存储 ----------------
  function defaultAccount(name, provider) {
    return { id: uid(), name: name, provider: provider, token: '', authMode: 'auto', org: '', project: '', planType: '', result: null, lastAt: 0 };
  }
  function load() {
    try {
      var raw = localStorage.getItem(KEY);
      if (raw) {
        var s = JSON.parse(raw);
        if (s && Array.isArray(s.accounts)) {
          s.settings = s.settings || { autoRefresh: true, interval: 5 };
          if (s.settings.autoImport === undefined) s.settings.autoImport = true;
          // v1→v2 迁移：移除初始占位账号（无凭证且为默认名），保留用户已配置的账号
          if (s.schema < 2) {
            var seeds = ['GLM 账号 1', 'GLM 账号 2', 'DeepSeek'];
            s.accounts = s.accounts.filter(function (a) {
              return a.token || seeds.indexOf(a.name) === -1;
            });
          }
          s.schema = SCHEMA;
          return s;
        }
      }
    } catch (e) { /* 忽略损坏数据 */ }
    return {
      accounts: [],
      settings: { autoRefresh: true, interval: 5, autoImport: true },
      schema: SCHEMA
    };
  }
  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(store)); } catch (e) { showTip('本地存储写入失败：' + e.message); }
  }
  function uid() { return 'a' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function showTip(msg) { var t = document.getElementById('tip'); t.textContent = msg; t.hidden = false; }

  // ---------------- 网络请求（认证方式自动回退） ----------------
  // APK 内优先走 Capacitor 原生 HTTP（不受浏览器 CORS 限制），浏览器里回退 fetch
  function nativeHttp() {
    try {
      var C = window.Capacitor;
      if (!C) return null;
      if (C.getPlatform && C.getPlatform() === 'web') return null;
      var P = C.Plugins || {};
      return P.CapacitorHttp || P.Http || C.CapacitorHttp || null;
    } catch (e) { return null; }
  }

  async function doFetch(url, headers) {
    var nh = nativeHttp();
    if (nh && typeof nh.get === 'function') {
      try {
        var resp = await nh.get({ url: url, headers: headers, webFetch: false, readTimeout: 20000, connectTimeout: 20000 });
        var body = resp.data;
        var text = typeof body === 'string' ? body : JSON.stringify(body);
        if (typeof body === 'string') { try { body = JSON.parse(body); } catch (e) { /* 非 JSON */ } }
        return { ok: resp.status >= 200 && resp.status < 300, status: resp.status, body: body, text: text };
      } catch (e) { /* 原生失败则回退 fetch */ }
    }
    var res = await fetch(url, { method: 'GET', headers: headers, cache: 'no-store' });
    var t = await res.text();
    var b = null;
    try { b = JSON.parse(t); } catch (e) { /* 非 JSON */ }
    return { ok: res.ok, status: res.status, body: b, text: t };
  }

  async function requestJSON(url, acc, extraHeaders) {
    var raw = (acc.token || '').trim();
    var token = raw.replace(/^Bearer\s+/i, '').trim();
    var isJWT = /^eyJ/.test(token);
    var modes;
    if (acc.authMode === 'bearer') modes = ['bearer'];
    else if (acc.authMode === 'raw') modes = ['raw'];
    else modes = isJWT ? ['bearer'] : ['raw', 'bearer'];

    var lastError = '';
    for (var i = 0; i < modes.length; i++) {
      var mode = modes[i];
      var headers = { 'Accept': 'application/json' };
      if (extraHeaders) Object.keys(extraHeaders).forEach(function (k) { headers[k] = extraHeaders[k]; });
      headers['Authorization'] = mode === 'bearer' ? ('Bearer ' + token) : token;
      try {
        var r = await doFetch(url, headers);
        // 服务商常在 HTTP 200 里用业务码报错（如 code=401 / success=false），必须一并判失败
        var bizCode = r.body && r.body.code != null ? Number(r.body.code) : null;
        var bizFail = (bizCode != null && bizCode !== 200) || (r.body && r.body.success === false);
        if (r.ok && !bizFail) return { ok: true, data: r.body, mode: mode };
        var msg = r.body && (r.body.msg || r.body.message) ? (r.body.msg || r.body.message) : (r.text || '').slice(0, 100);
        lastError = (r.ok ? '业务码 ' + bizCode : 'HTTP ' + r.status) + (msg ? ' · ' + msg : '');
        // 认证类错误才尝试下一种认证方式
        if ((r.status === 401 || r.status === 403) || (bizFail && (bizCode === 401 || bizCode === 403 || bizCode === 10001 || /token|auth|key|登录|鉴权/i.test(msg || '')))) continue;
        break;
      } catch (e) {
        lastError = (e && e.message) ? e.message : '网络错误（可能是网络不通或被拦截）';
        break;
      }
    }
    return { ok: false, error: lastError || '请求失败' };
  }

  async function fetchGLM(acc) {
    var base = PROVIDERS[acc.provider].base;
    var extra = {};
    if (acc.org) extra['bigmodel-organization'] = acc.org.trim();
    if (acc.project) extra['bigmodel-project'] = acc.project.trim();
    var qs = acc.planType ? ('?type=' + encodeURIComponent(acc.planType)) : '';

    var quota = await requestJSON(base + '/api/monitor/usage/quota/limit' + qs, acc, extra);
    var bal = await requestJSON(base + '/api/biz/account/query-customer-account-report', acc, {});
    if (!quota.ok) {
      return { ok: false, error: quota.error, balance: bal.ok ? bal.data : null, balanceError: bal.ok ? '' : bal.error };
    }
    return { ok: true, quota: quota.data, balance: bal.ok ? bal.data : null, balanceError: bal.ok ? '' : bal.error, mode: quota.mode };
  }

  async function fetchDeepSeek(acc) {
    var r = await requestJSON(PROVIDERS.deepseek.base + '/user/balance', acc, {});
    return r.ok ? { ok: true, data: r.data, mode: r.mode } : { ok: false, error: r.error };
  }

  // ---------------- 解析 ----------------
  function unitName(u) { return u === 3 ? '小时' : u === 6 ? '周' : u === 5 ? '月' : u === 1 ? '天' : ('单位' + u); }
  function parseWindow(l) {
    var usedPct = Number(l.percentage || 0);
    var cap = Number(l.usage != null ? l.usage : l.currentValue || 0);
    var used = Number(l.currentValue || 0);
    var remain = l.remaining != null ? Number(l.remaining) : Math.max(0, cap - used);
    var kind = l.type === 'TOKENS_LIMIT' ? 'Token 池' : '调用池';
    var n = l.number > 1 ? l.number : '';
    // 短标签：用于卡片上直接展示（如 "5小时"、"本周"）
    var short;
    if (l.unit === 6) short = '本周';
    else if (l.unit === 3) short = (l.number || 5) + '小时';
    else if (l.unit === 5) short = '本月';
    else if (l.unit === 1) short = (l.number > 1 ? l.number : '每') + '天';
    else short = n + unitName(l.unit);
    return {
      title: kind + ' · ' + n + unitName(l.unit) + '窗口',
      short: short,
      unit: l.unit, number: l.number,
      usedPct: usedPct,
      remainPct: Math.max(0, 100 - usedPct),
      cap: cap, used: used, remain: remain,
      resetAt: l.nextResetTime || null,
      details: l.usageDetails || [],
      raw: l
    };
  }
  function parseGLM(result) {
    if (!result || !result.ok) return null;
    var data = result.quota && result.quota.data ? result.quota.data : {};
    var limits = Array.isArray(data.limits) ? data.limits : [];
    var windows = limits.map(parseWindow);
    // 卡片展示顺序：小时窗口(5h)在前，其次周/月/天，保证「周额度」紧跟在「5小时」下面
    var rank = { 3: 0, 1: 1, 6: 2, 5: 3 }; // unit: 3小时 1天 6周 5月
    var sorted = windows.slice().sort(function (a, b) {
      return (rank[a.unit] != null ? rank[a.unit] : 9) - (rank[b.unit] != null ? rank[b.unit] : 9);
    });
    var primary = null;
    windows.forEach(function (w) { if (!primary || w.remainPct < primary.remainPct) primary = w; });
    var weekly = null;
    windows.forEach(function (w) { if (w.unit === 6) weekly = w; });
    return { level: data.level || '—', windows: windows, sorted: sorted, primary: primary, weekly: weekly };
  }
  function parseDS(result) {
    if (!result || !result.ok) return null;
    var d = result.data || {};
    var infos = Array.isArray(d.balance_infos) ? d.balance_infos : [];
    var primary = infos[0] || {};
    var total = Number(primary.total_balance || 0);
    return {
      available: d.is_available !== false,
      infos: infos,
      currency: primary.currency || '',
      total: total,
      granted: Number(primary.granted_balance || 0),
      topped: Number(primary.topped_up_balance || 0)
    };
  }
  function stateOf(acc) {
    var r = acc.result;
    if (!r) return { key: 'idle', label: '未刷新', color: '#a9aeba', pct: 0 };
    if (!r.ok) return { key: 'error', label: '查询失败', color: '#f04438', pct: 0 };
    if (acc.provider === 'deepseek') {
      var d = parseDS(r);
      if (!d) return { key: 'error', label: '数据异常', color: '#f04438', pct: 0 };
      if (!d.available || d.total <= 0) return { key: 'low', label: '余额不足', color: '#f04438', pct: 0 };
      return { key: 'ok', label: '余额正常', color: '#12b76a', pct: 100 };
    }
    var g = parseGLM(r);
    if (!g || !g.primary) return { key: 'error', label: '数据异常', color: '#f04438', pct: 0 };
    var p = g.primary.remainPct;
    if (p <= 10) return { key: 'low', label: '额度告急', color: '#f04438', pct: p };
    if (p <= 30) return { key: 'warn', label: '额度偏紧', color: '#f79009', pct: p };
    return { key: 'ok', label: '额度充足', color: '#12b76a', pct: p };
  }
  function fmtTime(ms) {
    if (!ms) return '—';
    var d = new Date(ms);
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function fmtCountdown(ms) {
    if (!ms) return '';
    var diff = ms - Date.now();
    if (diff <= 0) return '即将重置';
    var h = Math.floor(diff / 3600000), m = Math.floor((diff % 3600000) / 60000);
    if (h >= 24) return Math.floor(h / 24) + ' 天后';
    return h > 0 ? (h + ' 小时 ' + m + ' 分后') : (m + ' 分钟后');
  }
  function money(n, cur) { return (cur === 'USD' ? '$' : '¥') + Number(n || 0).toFixed(2); }

  // ---------------- 渲染 ----------------
  function render() {
    renderSubtitle();
    var wrap = document.getElementById('cards');
    wrap.innerHTML = store.accounts.map(cardHTML).join('');
    wrap.querySelectorAll('.card').forEach(function (el) {
      el.addEventListener('click', function (e) {
        if (justDragged) return;                                  // 拖完那一下不要打开详情
        if (e.target.closest && e.target.closest('.drag-handle')) return; // 点手柄不打开详情
        openDetail(el.getAttribute('data-id'));
      });
    });
    initSortable();
    document.getElementById('empty').hidden = store.accounts.length > 0;
    if (detailId && !document.getElementById('detailSheet').hidden) renderDetail();
  }
  function renderSubtitle() {
    var el = document.getElementById('subtitle');
    if (!store.accounts.length) { el.textContent = '还没有添加账号'; return; }
    var times = store.accounts.filter(function (a) { return a.lastAt; }).map(function (a) { return a.lastAt; });
    if (!times.length) { el.textContent = '还没有刷新过'; return; }
    el.textContent = '上次更新 ' + fmtTime(Math.max.apply(null, times)) + ' · 共 ' + store.accounts.length + ' 个账号';
  }

  // ---------------- 卡片拖拽排序 ----------------
  // 顺序直接体现在 store.accounts 数组里，备份/导入天然兼容；
  // 用 SortableJS 的触摸 fallback（Android WebView 原生 HTML5 DnD 不可靠），
  // 只允许从右侧拖拽手柄开始，避免与页面滚动、卡片点击冲突。
  function initSortable() {
    var wrap = document.getElementById('cards');
    if (!wrap || typeof Sortable === 'undefined') return;
    if (sortable) { sortable.destroy(); sortable = null; }
    sortable = Sortable.create(wrap, {
      handle: '.drag-handle',
      animation: 180,
      delay: 120,                 // 触摸时短按一下再拖，防误触
      delayOnTouchOnly: true,
      touchStartThreshold: 3,
      forceFallback: true,
      fallbackTolerance: 3,
      scroll: true,
      bubbleScroll: true,
      ghostClass: 'card-ghost',
      chosenClass: 'card-chosen',
      dragClass: 'card-drag',
      onEnd: function () {
        var byId = {};
        store.accounts.forEach(function (a) { byId[a.id] = a; });
        var ids = Array.prototype.map.call(wrap.querySelectorAll('.card'), function (el) {
          return el.getAttribute('data-id');
        });
        var next = ids.map(function (id) { return byId[id]; }).filter(Boolean);
        if (next.length === store.accounts.length) {
          store.accounts = next;   // 重排数据，下次 render 按新顺序渲染
          save();                  // 持久化到 localStorage
          renderSubtitle();
        }
        justDragged = true;        // 拖完紧随的 click 不要打开详情
        setTimeout(function () { justDragged = false; }, 350);
      }
    });
  }

  function cardHTML(acc) {
    var p = PROVIDERS[acc.provider] || PROVIDERS['glm-cn'];
    var st = stateOf(acc);
    var mainLabel = '', mainValue = '', side = '';
    var glm = null;

    var quotaLines = '';
    if (acc.result && acc.result.ok && acc.provider !== 'deepseek') {
      glm = parseGLM(acc.result);
      if (glm && glm.primary) {
        // 顶部大百分比已去掉：下面各窗口已直接显示，不再重复。套餐等级挪到 provider 后面。
        // 直接列出各额度窗口（5小时 / 本周 / ...），周额度用尽一眼可见
        quotaLines = '<div class="quota-lines">' + (glm.sorted || glm.windows).map(function (w) {
          var c = w.remainPct <= 10 ? '#f04438' : w.remainPct <= 30 ? '#f79009' : '#12b76a';
          return '<div class="quota-line">' +
            '<span class="ql-name">' + esc(w.short || w.title) + (w === glm.primary ? ' <em>最紧</em>' : '') + '</span>' +
            '<span class="ql-bar"><i style="width:' + Math.max(0, Math.min(100, w.remainPct)) + '%;background:' + c + '"></i></span>' +
            '<span class="ql-pct" style="color:' + c + '">' + Math.round(w.remainPct) + '%</span>' +
          '</div>';
        }).join('') + '</div>';
      }
    } else if (acc.result && acc.result.ok && acc.provider === 'deepseek') {
      var d = parseDS(acc.result);
      if (d) {
        mainLabel = '可用余额';
        mainValue = '<span style="font-size:22px">' + esc(money(d.total, d.currency)) + '</span>';
        side = (d.available ? '状态正常' : '余额不足') + '<br>' + (d.currency || '—') + ' 账户';
      }
    } else if (!acc.result) {
      mainLabel = acc.token ? '等待查询' : '未配置凭证';
      mainValue = '<span style="font-size:19px;color:#a9aeba">—</span>';
      side = '点开卡片<br>填写凭证';
    } else {
      mainLabel = '查询失败';
      mainValue = '<span style="font-size:19px;color:#f04438">!</span>';
      side = '点开查看<br>错误原因';
    }

    var errLine = acc.result && !acc.result.ok ? '<div class="err-text">' + esc(acc.result.error) + '</div>' : '';
    // GLM 成功卡片：去掉顶部大百分比（下面各窗口已显示），套餐等级挪到 provider 名称后面
    var planText = (glm && glm.level && glm.level !== '—') ? ' · 套餐 ' + esc(glm.level) : '';
    var isGLMOk = !!(glm && glm.primary);
    var mainHtml = isGLMOk ? '' :
      '<div class="card-main"><div><div class="metric-label">' + mainLabel + '</div><div class="metric-value">' + mainValue + '</div></div><div class="metric-side">' + side + '</div></div>';
    // GLM 卡片各窗口已有自己的进度条，顶部这条总进度条就是多余的那条，去掉
    var topBar = isGLMOk ? '' :
      '<div class="bar"><i style="width:' + Math.max(0, Math.min(100, st.pct)) + '%"></i></div>';
    return '<article class="card' + (acc.result && !acc.result.ok ? ' error' : '') + '" data-id="' + acc.id + '" style="--accent:' + p.color + ';--badge-bg:' + p.badgeBg + ';--state:' + st.color + '">' +
      '<div class="card-head">' +
        '<div class="card-id"><span class="provider-dot"></span><div><div class="card-name">' + esc(acc.name) + '</div><div class="card-provider">' + esc(p.label) + planText + '</div></div></div>' +
        '<div class="card-head-right"><span class="badge">' + esc(st.label) + '</span>' +
          '<span class="drag-handle" title="长按拖动排序" aria-label="拖动排序"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><circle cx="9" cy="6" r="1.6"/><circle cx="15" cy="6" r="1.6"/><circle cx="9" cy="12" r="1.6"/><circle cx="15" cy="12" r="1.6"/><circle cx="9" cy="18" r="1.6"/><circle cx="15" cy="18" r="1.6"/></svg></span>' +
        '</div>' +
      '</div>' +
      mainHtml +
      topBar +
      quotaLines +
      errLine +
      '<div class="card-foot"><span>' + (acc.lastAt ? ('更新于 ' + fmtTime(acc.lastAt)) : '尚未更新') + '</span><span>查看详情 ›</span></div>' +
    '</article>';
  }

  // ---------------- 详情 ----------------
  function openDetail(id) {
    detailId = id;
    var acc = find(id);
    if (!acc) return;
    var p = PROVIDERS[acc.provider] || PROVIDERS['glm-cn'];
    document.getElementById('detailDot').style.background = p.color;
    document.getElementById('detailTitle').textContent = acc.name;
    document.getElementById('detailSub').textContent = p.label + (acc.token ? (' · 凭证已保存' + (acc.result && acc.result.mode ? (' · ' + acc.result.mode.toUpperCase()) : '')) : ' · 未填写凭证');
    renderDetail();
    openSheet('detailSheet');
  }
  function renderDetail() {
    var acc = find(detailId);
    if (!acc) return;
    var body = document.getElementById('detailBody');
    var r = acc.result;

    if (!acc.token) {
      body.innerHTML = '<div class="err-box">还没有填写凭证。点右上角「编辑」粘贴 API Key 或网页令牌。</div>' + rawBox(acc);
      return;
    }
    if (!r) {
      body.innerHTML = '<div class="about">还没有查询过，点顶部刷新按钮试一下。</div>' + rawBox(acc);
      return;
    }
    if (!r.ok) {
      body.innerHTML = '<div class="err-box"><strong>查询失败</strong><br>' + esc(r.error) + '<br><br>常见原因：凭证填错 / 复制成了整段 cookie / 新版积分套餐缺少组织 ID 与项目 ID / 套餐类型选错。</div>' + rawBox(acc);
      return;
    }

    var html = '';
    if (acc.provider !== 'deepseek') {
      var g = parseGLM(r);
      if (!g) { body.innerHTML = '<div class="err-box">返回数据无法解析</div>' + rawBox(acc); return; }
      var primary = g.primary || { remainPct: 0, title: '—' };
      html += '<div class="detail-hero"><div class="big">' + Math.round(primary.remainPct) + '%</div><div class="cap">最紧张窗口剩余 · ' + esc(primary.title) + '</div>' +
        '<div class="detail-grid">' +
          kv('套餐等级', g.level) +
          kv('额度窗口', g.windows.length + ' 个') +
          kv('更新时间', fmtTime(acc.lastAt)) +
          kv('认证方式', (r.mode || '—').toUpperCase()) +
        '</div></div>';

      if (r.balanceError) html += '<div class="err-box" style="background:#fffbeb;border-color:#fde68a;color:#92400e">余额接口未取到：' + esc(r.balanceError) + '（不影响额度展示）</div>';
      if (r.balance) {
        var b = r.balance.data || r.balance || {};
        html += '<div class="sec-title">账户余额</div><div class="detail-grid">' +
          kv('可用余额', b.availableBalance != null ? b.availableBalance : '—') +
          kv('账户余额', b.balance != null ? b.balance : '—') +
          kv('累计充值', b.rechargeAmount != null ? b.rechargeAmount : '—') +
          kv('赠送金额', b.giveAmount != null ? b.giveAmount : '—') +
          kv('累计消费', b.totalSpendAmount != null ? b.totalSpendAmount : '—') +
          kv('冻结金额', b.frozenBalance != null ? b.frozenBalance : '—') +
        '</div>';
      }

      html += '<div class="sec-title">额度明细</div>';
      if (!g.windows.length) {
        var srvMsg = r.quota && (r.quota.msg || r.quota.message) ? (r.quota.msg || r.quota.message) : '';
        html += '<div class="err-box">接口没有返回额度窗口数据。' + (srvMsg ? '<br>服务端提示：' + esc(srvMsg) : '<br>如果用的是新版积分套餐，请在编辑里补上组织 ID、项目 ID 并选择正确的套餐类型。') + '</div>';
      }
      g.windows.forEach(function (w) {
        var color = w.remainPct <= 10 ? '#f04438' : w.remainPct <= 30 ? '#f79009' : '#12b76a';
        html += '<div class="window"><div class="window-head"><strong>' + esc(w.title) + '</strong><span style="color:' + color + '">剩余 ' + Math.round(w.remainPct) + '%</span></div>' +
          '<div class="bar" style="--state:' + color + '"><i style="width:' + Math.max(0, Math.min(100, w.remainPct)) + '%"></i></div>' +
          '<div class="window-meta"><span>已用 ' + w.used + ' / ' + (w.cap || '—') + ' · 剩余 ' + w.remain + '</span><span>' + (w.resetAt ? (fmtTime(w.resetAt) + ' · ' + fmtCountdown(w.resetAt)) : '') + '</span></div>';
        if (w.details && w.details.length) {
          html += '<div style="margin-top:9px">';
          w.details.forEach(function (d) { html += '<div class="tool-row"><span>' + esc(d.modelCode) + '</span><span>' + esc(d.usage) + '</span></div>'; });
          html += '</div>';
        }
        html += '</div>';
      });
    } else {
      var d2 = parseDS(r);
      if (!d2) { body.innerHTML = '<div class="err-box">返回数据无法解析</div>' + rawBox(acc); return; }
      html += '<div class="detail-hero"><div class="big">' + esc(money(d2.total, d2.currency)) + '</div><div class="cap">账户总余额 · ' + (d2.available ? '可正常调用 API' : '余额不足') + '</div>' +
        '<div class="detail-grid">' +
          kv('币种', d2.currency || '—') +
          kv('赠送余额', money(d2.granted, d2.currency)) +
          kv('充值余额', money(d2.topped, d2.currency)) +
          kv('更新时间', fmtTime(acc.lastAt)) +
        '</div></div>';
      html += '<div class="sec-title">币种明细</div>';
      d2.infos.forEach(function (info) {
        html += '<div class="window"><div class="window-head"><strong>' + esc(info.currency || '—') + '</strong><span>' + esc(money(info.total_balance, info.currency)) + '</span></div>' +
          '<div class="window-meta" style="margin-top:8px"><span>赠送 ' + esc(money(info.granted_balance, info.currency)) + '</span><span>充值 ' + esc(money(info.topped_up_balance, info.currency)) + '</span></div></div>';
      });
    }
    html += rawBox(acc);
    body.innerHTML = html;
    var tog = body.querySelector('.raw-toggle');
    if (tog) tog.addEventListener('click', function () {
      var pre = body.querySelector('.raw-pre');
      pre.hidden = !pre.hidden;
      tog.textContent = pre.hidden ? '查看原始数据' : '收起原始数据';
    });
  }
  function kv(k, v) { return '<div class="kv"><div class="k">' + esc(k) + '</div><div class="v">' + esc(v) + '</div></div>'; }
  function rawBox(acc) {
    var raw = acc.result ? (acc.result.quota || acc.result.data || acc.result.balance || {}) : {};
    return '<div class="raw-toggle">查看原始数据</div><pre class="raw-pre" hidden>' + esc(JSON.stringify(raw, null, 2)) + '</pre>';
  }

  // ---------------- 弹层控制 ----------------
  var SHEETS = ['detailSheet', 'editSheet', 'settingsSheet', 'backupSheet'];
  function openSheet(id) {
    document.getElementById('overlay').hidden = false;
    SHEETS.forEach(function (s) { document.getElementById(s).hidden = (s !== id); });
  }
  function closeSheets() {
    document.getElementById('overlay').hidden = true;
    SHEETS.forEach(function (s) { document.getElementById(s).hidden = true; });
    detailId = null; editingId = null;
  }

  // ---------------- 编辑表单 ----------------
  var HOWTO = {
    'glm-cn': '获取凭证：<b>API Key</b> 在 bigmodel.cn 控制台创建；<b>网页令牌</b> 在 bigmodel.cn 登录后按 F12 → Network → 刷新用量页 → 找到 quota/limit 请求 → 复制请求头里的 Authorization（含 Bearer）。新版积分套餐还需同请求里的 bigmodel-organization、bigmodel-project 和 URL 里的 type。不要复制整段 Cookie。',
    'glm-global': '获取凭证：在 z.ai 登录后按 F12 → Network → 打开用量页 → 找到 quota/limit 请求 → 复制请求头里的 Authorization（Bearer 开头）；或直接用 Z.AI 的 API Key（不带 Bearer 也可以）。不要复制整段 Cookie。',
    'deepseek': '获取凭证：在 platform.deepseek.com 创建 API Key（sk- 开头），粘贴到这里即可。查询余额只需要这一个 Key，不需要网页 cookie。'
  };
  function openEdit(id) {
    editingId = id || null;
    var acc = id ? find(id) : defaultAccount('', 'glm-cn');
    document.getElementById('editTitle').textContent = id ? '编辑账号' : '添加账号';
    document.getElementById('fName').value = acc.name || '';
    document.getElementById('fProvider').value = acc.provider;
    document.getElementById('fToken').value = acc.token || '';
    document.getElementById('fAuthMode').value = acc.authMode || 'auto';
    document.getElementById('fOrg').value = acc.org || '';
    document.getElementById('fProject').value = acc.project || '';
    document.getElementById('fPlanType').value = acc.planType || '';
    document.getElementById('btnDelete').hidden = !id;
    setLoginStatus('');
    updateFormHint();
    openSheet('editSheet');
  }
  function updateFormHint() {
    var pv = document.getElementById('fProvider').value;
    document.getElementById('glmExtra').hidden = (pv === 'deepseek');
    document.getElementById('howto').innerHTML = HOWTO[pv] || '';
  }
  function find(id) { for (var i = 0; i < store.accounts.length; i++) if (store.accounts[i].id === id) return store.accounts[i]; return null; }

  // ---------------- 凭证备份 / 恢复 ----------------
  var BACKUP_PREFIX = 'QBOARD1:';

  // UTF-8 安全的 base64 编解码（兼容中文账号名）
  function b64encode(str) {
    var bytes = new TextEncoder().encode(str);
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  }
  function b64decode(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  }

  function makeBackup() {
    var data = {
      v: 1,
      exportedAt: Date.now(),
      accounts: store.accounts.map(function (a) {
        return {
          name: a.name, provider: a.provider, token: a.token,
          authMode: a.authMode, org: a.org, project: a.project, planType: a.planType
        };
      })
    };
    return BACKUP_PREFIX + b64encode(JSON.stringify(data));
  }

  function setBackupMsg(msg, type) {
    var el = document.getElementById('backupWarn');
    el.textContent = msg || '';
    el.className = 'warn-box' + (type ? ' ' + type : '');
    el.hidden = !msg;
  }

  function openBackup(mode) {
    var isExport = (mode === 'export');
    document.getElementById('backupTitle').textContent = isExport ? '导出备份' : '导入备份';
    document.getElementById('backupSubtitle').textContent = isExport ? '复制到安全的地方保存' : '粘贴之前保存的备份内容';
    document.getElementById('backupLabel').innerHTML = isExport
      ? '备份内容 <em>请完整复制保存</em>'
      : '备份内容 <em>粘贴到此处</em>';
    document.getElementById('backupActionsExport').hidden = !isExport;
    document.getElementById('backupActionsImport').hidden = isExport;
    var ta = document.getElementById('backupText');
    ta.value = isExport ? makeBackup() : '';
    ta.readOnly = isExport;
    // 导出/复制的是自己的备份，记入忽略名单，避免回前台时误触发自动导入
    if (isExport) dismissedBackup = ta.value;
    setBackupMsg(isExport
      ? '这段内容包含你的全部凭证，请存到备忘录、网盘或文件里。切勿发给他人。'
      : '', isExport ? '' : '');
    if (isExport && !store.accounts.length) {
      setBackupMsg('当前还没有任何账号，导出的是空备份。', 'err');
    }
    openSheet('backupSheet');
  }

  function plugin(name) {
    try {
      var C = window.Capacitor;
      if (!C || !C.Plugins) return null;
      return C.Plugins[name] || null;
    } catch (e) { return null; }
  }

  async function copyBackup() {
    var text = document.getElementById('backupText').value.trim();
    if (!text) { setBackupMsg('备份内容为空。', 'err'); return; }
    var Clipboard = plugin('Clipboard');
    if (Clipboard && typeof Clipboard.write === 'function') {
      try {
        await Clipboard.write({ string: text });
        dismissedBackup = text;
        setBackupMsg('已复制到剪贴板，去备忘录或微信粘贴保存即可。', 'ok');
        return;
      } catch (e) { /* 插件失败，走降级 */ }
    }
    // 降级：选中文本让用户手动复制
    var ta = document.getElementById('backupText');
    ta.focus(); ta.select();
    setBackupMsg('自动复制不可用，请长按下方文本全选后手动复制。', 'err');
  }

  async function shareBackup() {
    var text = document.getElementById('backupText').value.trim();
    if (!text) { setBackupMsg('备份内容为空。', 'err'); return; }
    var Share = plugin('Share');
    if (Share && typeof Share.share === 'function') {
      try {
        await Share.share({ title: '额度看板凭证备份', text: text, dialogTitle: '保存凭证备份' });
        setBackupMsg('已调起分享，选择保存到文件或发送到备忘录/网盘。', 'ok');
        return;
      } catch (e) {
        if (e && String(e.message).indexOf('cancel') === -1) {
          setBackupMsg('分享失败：' + e.message + '。可改用"复制备份内容"。', 'err');
        }
        return;
      }
    }
    setBackupMsg('此环境不支持系统分享，请用"复制备份内容"。', 'err');
  }

  function parseBackup(text) {
    text = (text || '').trim();
    if (text.indexOf(BACKUP_PREFIX) !== 0) throw new Error('不是有效的备份内容（缺少标识头）');
    var json = b64decode(text.slice(BACKUP_PREFIX.length));
    var data = JSON.parse(json);
    if (!data || !Array.isArray(data.accounts)) throw new Error('备份格式损坏');
    return data;
  }

  function incomingFromData(data) {
    return data.accounts.filter(function (a) {
      return a && a.name && a.provider && a.token;
    }).map(function (a) {
      return {
        id: uid(), name: a.name, provider: a.provider, token: a.token,
        authMode: a.authMode || 'auto', org: a.org || '', project: a.project || '',
        planType: a.planType || '', result: null, lastAt: 0
      };
    });
  }

  // mode: 'replace' | 'merge'；quiet=true 时不弹确认、不关弹层（供自动导入用）
  function applyIncoming(incoming, mode, quiet) {
    if (mode === 'replace') {
      if (!quiet && !confirm('覆盖会删除当前所有 ' + store.accounts.length + ' 个账号，确定继续吗？')) return false;
      store.accounts = incoming;
    } else {
      // 合并：按 平台+名称+凭证 去重
      var seen = {};
      store.accounts.forEach(function (a) { seen[a.provider + '|' + a.name + '|' + a.token] = 1; });
      var added = 0;
      incoming.forEach(function (a) {
        var k = a.provider + '|' + a.name + '|' + a.token;
        if (!seen[k]) { store.accounts.push(a); seen[k] = 1; added++; }
      });
      if (!added) {
        if (quiet) showTip('备份中的账号都已存在，无需重复导入。');
        else setBackupMsg('备份中的账号都已存在，无需重复导入。', 'ok');
        return false;
      }
    }
    save(); render();
    if (!quiet) closeSheets();
    showTip('已恢复 ' + incoming.length + ' 个账号');
    refreshAll(true);
    return true;
  }

  function importBackup(mode) {
    var text = document.getElementById('backupText').value.trim();
    if (!text) { setBackupMsg('请先粘贴备份内容。', 'err'); return; }
    var data;
    try { data = parseBackup(text); }
    catch (e) { setBackupMsg('导入失败：' + e.message, 'err'); return; }
    var incoming = incomingFromData(data);
    if (!incoming.length) { setBackupMsg('备份里没有有效的账号。', 'err'); return; }
    applyIncoming(incoming, mode, false);
  }

  // ---------------- 剪贴板自动导入备份 ----------------
  var dismissedBackup = '';   // 用户已忽略 / 自己导出的备份，不再自动提示
  var autoImportBusy = false;

  async function readClipboardText() {
    var Clipboard = plugin('Clipboard');
    if (!Clipboard || typeof Clipboard.read !== 'function') return '';
    try {
      var r = await Clipboard.read();
      return (r && r.value) ? String(r.value) : '';
    } catch (e) { return ''; }  // 无焦点/无权限时静默跳过
  }

  async function autoImportFromClipboard() {
    if (autoImportBusy) return;
    if (store.settings.autoImport === false) return;
    autoImportBusy = true;
    try {
      var text = (await readClipboardText()).trim();
      if (!text || text.indexOf(BACKUP_PREFIX) !== 0) return;
      if (text === dismissedBackup) return;
      var data;
      try { data = parseBackup(text); } catch (e) { return; }
      var incoming = incomingFromData(data);
      if (!incoming.length) return;

      if (!store.accounts.length) {
        // 空账号（清数据/重装场景）：静默恢复，无需任何操作
        dismissedBackup = text;
        applyIncoming(incoming, 'replace', true);
        showTip('已从剪贴板自动恢复 ' + incoming.length + ' 个账号');
      } else {
        // 已有账号：询问，默认合并（安全，不删现有）
        if (confirm('检测到剪贴板中有凭证备份（' + incoming.length + ' 个账号）。\n\n点「确定」合并导入（保留现有账号），点「取消」忽略。')) {
          dismissedBackup = text;
          applyIncoming(incoming, 'merge', true);
        } else {
          dismissedBackup = text;  // 忽略后同一份备份不再重复提示
        }
      }
    } finally { autoImportBusy = false; }
  }

  // ---------------- 一键登录自动抓取凭证 ----------------
  function loginPlugin() {
    try {
      var C = window.Capacitor;
      if (!C || !C.Plugins || !C.Plugins.LoginCapture) return null;
      return C.Plugins.LoginCapture;
    } catch (e) { return null; }
  }
  function setLoginStatus(msg, kind) {
    var el = document.getElementById('loginStatus');
    if (!msg) { el.hidden = true; return; }
    el.hidden = false;
    el.className = 'login-status ' + (kind || 'loading');
    el.textContent = msg;
  }
  async function autoLogin() {
    var plugin = loginPlugin();
    if (!plugin) {
      setLoginStatus('当前环境不支持内嵌登录（仅 Android APK 可用），请手动粘贴凭证。', 'err');
      return;
    }
    var provider = document.getElementById('fProvider').value;
    var btn = document.getElementById('btnAutoLogin');
    btn.disabled = true;
    setLoginStatus('正在打开登录页，请在弹出的页面中登录…', 'loading');
    try {
      var res = await plugin.openLogin({ provider: provider });
      if (res && res.cancelled) {
        setLoginStatus('已取消登录。', 'err');
      } else if (res && res.token) {
        document.getElementById('fToken').value = res.token;
        if (res.authMode) document.getElementById('fAuthMode').value = res.authMode;
        if (res.org) document.getElementById('fOrg').value = res.org;
        if (res.project) document.getElementById('fProject').value = res.project;
        if (res.planType) document.getElementById('fPlanType').value = res.planType;
        var hint = '凭证已自动填入';
        if (res.org || res.project) hint += '（含组织/项目 ID）';
        setLoginStatus(hint + '，点保存即可。', 'ok');
      } else {
        setLoginStatus('没有读取到凭证。请确认已完成登录，或改用手动粘贴。', 'err');
      }
    } catch (e) {
      setLoginStatus('登录失败：' + ((e && e.message) || '未知错误'), 'err');
    } finally {
      btn.disabled = false;
    }
  }

  // ---------------- 刷新 ----------------
  async function refreshOne(acc) {
    if (!acc.token) { acc.result = { ok: false, error: '未填写凭证' }; acc.lastAt = Date.now(); return; }
    acc.result = acc.provider === 'deepseek' ? await fetchDeepSeek(acc) : await fetchGLM(acc);
    acc.lastAt = Date.now();
  }
  async function refreshAll(manual) {
    var btn = document.getElementById('btnRefresh');
    btn.classList.add('spinning');
    var targets = store.accounts.filter(function (a) { return a.token; });
    if (!targets.length && manual) { showTip('请先给至少一个账号填写凭证'); btn.classList.remove('spinning'); return; }
    await Promise.all(targets.map(refreshOne));
    save(); render();
    btn.classList.remove('spinning');
  }
  function setupAutoRefresh() {
    if (refreshTimer) clearInterval(refreshTimer);
    if (!store.settings.autoRefresh) return;
    var ms = Math.max(1, Number(store.settings.interval) || 5) * 60000;
    refreshTimer = setInterval(function () {
      if (document.visibilityState === 'visible') refreshAll(false);
    }, ms);
  }

  // ---------------- 事件绑定 ----------------
  function bind() {
    document.getElementById('overlay').addEventListener('click', closeSheets);
    document.getElementById('detailClose').addEventListener('click', closeSheets);
    document.getElementById('editClose').addEventListener('click', closeSheets);
    document.getElementById('settingsClose').addEventListener('click', closeSheets);
    document.getElementById('btnCancel').addEventListener('click', closeSheets);
    document.getElementById('btnRefresh').addEventListener('click', function () { refreshAll(true); });
    document.getElementById('btnAdd').addEventListener('click', function () { openEdit(null); });
    document.getElementById('btnSettings').addEventListener('click', function () {
      document.getElementById('fAutoRefresh').checked = !!store.settings.autoRefresh;
      document.getElementById('fInterval').value = String(store.settings.interval || 5);
      document.getElementById('fAutoImport').checked = store.settings.autoImport !== false;
      openSheet('settingsSheet');
    });
    document.getElementById('detailEdit').addEventListener('click', function () { var id = detailId; openEdit(id); });
    document.getElementById('fProvider').addEventListener('change', function () { updateFormHint(); setLoginStatus(''); });
    document.getElementById('btnAutoLogin').addEventListener('click', autoLogin);

    document.getElementById('editForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var name = document.getElementById('fName').value.trim();
      var token = document.getElementById('fToken').value.trim();
      if (!name || !token) {
        setLoginStatus('请先填写名称，并通过一键登录或手动粘贴获取凭证。', 'err');
        return;
      }
      var data = {
        name: name,
        provider: document.getElementById('fProvider').value,
        token: token,
        authMode: document.getElementById('fAuthMode').value,
        org: document.getElementById('fOrg').value.trim(),
        project: document.getElementById('fProject').value.trim(),
        planType: document.getElementById('fPlanType').value
      };
      var acc = editingId ? find(editingId) : null;
      if (acc) {
        var tokenChanged = acc.token !== data.token;
        Object.keys(data).forEach(function (k) { acc[k] = data[k]; });
        if (tokenChanged || acc.provider !== data.provider) { acc.result = null; acc.lastAt = 0; }
      } else {
        var na = defaultAccount(data.name, data.provider);
        Object.keys(data).forEach(function (k) { na[k] = data[k]; });
        store.accounts.push(na);
      }
      save(); closeSheets(); render();
      refreshAll(true);
    });

    document.getElementById('btnDelete').addEventListener('click', function () {
      if (!editingId) return;
      if (!confirm('确定删除这个账号及其凭证吗？')) return;
      store.accounts = store.accounts.filter(function (a) { return a.id !== editingId; });
      save(); closeSheets(); render();
    });

    document.getElementById('fAutoRefresh').addEventListener('change', function () {
      store.settings.autoRefresh = this.checked; save(); setupAutoRefresh();
    });
    document.getElementById('fInterval').addEventListener('change', function () {
      store.settings.interval = Number(this.value); save(); setupAutoRefresh();
    });
    document.getElementById('fAutoImport').addEventListener('change', function () {
      store.settings.autoImport = this.checked; save();
      showTip(this.checked ? '已开启剪贴板自动导入' : '已关闭剪贴板自动导入');
    });
    document.getElementById('btnClearAll').addEventListener('click', function () {
      if (store.accounts.length) {
        if (!confirm('建议先"导出备份"保存凭证！清空后无法恢复。\n\n仍要清空吗？')) return;
      }
      if (!confirm('再确认一次：真的要清空全部数据吗？')) return;
      store.accounts = []; save(); closeSheets(); render();
    });

    // 备份 / 恢复
    document.getElementById('btnExport').addEventListener('click', function () { openBackup('export'); });
    document.getElementById('btnImport').addEventListener('click', function () { openBackup('import'); });
    document.getElementById('backupClose').addEventListener('click', closeSheets);
    document.getElementById('btnCopyBackup').addEventListener('click', copyBackup);
    document.getElementById('btnShareBackup').addEventListener('click', shareBackup);
    document.getElementById('btnImportMerge').addEventListener('click', function () { importBackup('merge'); });
    document.getElementById('btnImportReplace').addEventListener('click', function () { importBackup('replace'); });

    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') {
        autoImportFromClipboard();   // 回前台时检测剪贴板备份（冷启动读不到时靠这里兜底）
        if (store.settings.autoRefresh) {
          var last = Math.max.apply(null, [0].concat(store.accounts.map(function (a) { return a.lastAt || 0; })));
          if (Date.now() - last > 60000) refreshAll(false);
        }
      }
    });

    // 下拉刷新（简单实现）
    var startY = 0;
    document.addEventListener('touchstart', function (e) { startY = e.touches[0].clientY; }, { passive: true });
    document.addEventListener('touchend', function (e) {
      if (window.scrollY <= 0 && e.changedTouches[0].clientY - startY > 90) refreshAll(true);
    }, { passive: true });
  }

  bind();
  render();
  setupAutoRefresh();
  if (store.accounts.some(function (a) { return a.token; })) refreshAll(false);
  // 启动时延时检测一次剪贴板（等 WebView 获得焦点，Android 后台读剪贴板会失败）
  setTimeout(autoImportFromClipboard, 1200);

  // Capacitor 资源随 APK 打包在本地，无需 Service Worker 缓存；
  // 反而会导致改了前端后真机一直读到旧缓存。这里主动注销旧 SW 并清空其缓存。
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        regs.forEach(function (r) { r.unregister(); });
      }).catch(function () {});
      if (window.caches && caches.keys) {
        caches.keys().then(function (keys) {
          keys.forEach(function (k) { caches.delete(k); });
        }).catch(function () {});
      }
    });
  }
})();
