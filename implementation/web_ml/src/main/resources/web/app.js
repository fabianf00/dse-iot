// WEB ML: a thin UI over the MS ML REST API.
// Rules (planning/10 §2): all filtering, paging and counting happen in MS ML; the UI keeps no
// data (state lives in the URL only, no browser storage); API data is rendered with textContent.

const PAGE_SIZE = 25;
const CHART_LIMIT = 500;
const LIVE_PERIOD_MS = 60 * 60 * 1000;
const HISTORY_DEFAULT_PERIOD_MS = 24 * 60 * 60 * 1000;
const UNITS = { CELSIUS: '°C', PPM: 'ppm', PERCENT: '%', HECTOPASCAL: 'hPa' };

const app = {
  config: null,      // /config.json
  endpoint: null,    // selected {name, restUrl}
  meta: null,        // GET /meta of the selected endpoint
  charts: new Map(), // key -> Chart instance
  timer: null,       // next refresh
  nextRefreshAt: 0,
  unreachable: false,
  lastTotal: 0,      // total of the last history search, shown before "delete all matching"
  renderId: 0,       // drops results of outdated renders
};

// ---------------------------------------------------------------- DOM helpers

const $ = (id) => document.getElementById(id);

/** Creates an element. Text is always set via textContent (no HTML parsing of API data). */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function show(node, visible) {
  node.hidden = !visible;
}

let messageTimer = null;
function message(text) {
  $('message').textContent = text;
  show($('message'), true);
  clearTimeout(messageTimer);
  messageTimer = setTimeout(() => show($('message'), false), 6000);
}

// ---------------------------------------------------------------- formatting

function fmt(value, digits = 2) {
  return typeof value === 'number' && Number.isFinite(value)
    ? value.toLocaleString(undefined, { maximumFractionDigits: digits })
    : '–';
}

function unit(name) {
  return name ? (UNITS[name] ?? name.toLowerCase()) : '';
}

function time(iso) {
  return iso ? new Date(iso).toLocaleString() : '–';
}

function shortTime(ms) {
  const date = new Date(ms);
  const sameDay = date.toDateString() === new Date().toDateString();
  return sameDay ? date.toLocaleTimeString() : date.toLocaleString();
}

function percent(value) {
  return typeof value === 'number' ? `${Math.round(value * 100)} %` : '–';
}

function range(item) {
  return `${fmt(item.binLower)} – ${fmt(item.binUpper)} ${unit(item.unit)}`;
}

function summary(item) {
  if (item.kind === 'ANOMALY') {
    return `${item.chainId}: observed ${fmt(item.observedValue)} ${unit(item.unit)}, expected ${item.expected}`;
  }
  return `${range(item)}, most likely ${fmt(item.mostLikelyValue)}, confidence ${percent(item.confidence)}`;
}

/** ISO instant (UTC) → value of a datetime-local input (browser time zone). */
function toLocalInput(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

/** datetime-local value (browser time zone) → ISO instant (UTC). */
function fromLocalInput(value) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
}

// ---------------------------------------------------------------- REST API

class ApiError extends Error {
  constructor(status, detail) {
    super(detail);
    this.status = status;
  }
}

function unavailable(error) {
  return error instanceof ApiError && (error.status === 0 || error.status === 503);
}

/** Query string from an object; arrays become repeated parameters, empty values are left out. */
function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    for (const v of Array.isArray(value) ? value : [value]) {
      if (v !== null && v !== undefined && v !== '') search.append(key, v);
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

async function api(method, path, body) {
  let response;
  try {
    response = await fetch(app.endpoint.restUrl + path, {
      method,
      cache: 'no-store',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, `${app.endpoint.name} is unreachable`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!response.ok) {
    throw new ApiError(response.status, json?.detail ?? `${response.status} ${response.statusText}`);
  }
  return json;
}

// ---------------------------------------------------------------- URL state

function readUrl() {
  const p = new URLSearchParams(location.search);
  return {
    view: p.get('view') === 'history' ? 'history' : 'live',
    endpoint: p.get('endpoint') ?? '',
    kind: p.get('kind') === 'predictions' ? 'predictions' : 'anomalies',
    dataType: p.getAll('dataType'),
    chainId: p.get('chainId') ?? '',
    from: p.get('from') ?? '',
    to: p.get('to') ?? '',
    commented: ['true', 'false'].includes(p.get('commented')) ? p.get('commented') : '',
    sort: p.get('sort') === 'asc' ? 'asc' : 'desc',
    offset: Math.max(0, Number.parseInt(p.get('offset') ?? '0', 10) || 0),
    id: p.get('id') ?? '',
  };
}

function urlOf(state) {
  const p = new URLSearchParams();
  p.set('view', state.view);
  if (state.endpoint) p.set('endpoint', state.endpoint);
  if (state.view === 'history') {
    p.set('kind', state.kind);
    state.dataType.forEach((type) => p.append('dataType', type));
    if (state.kind === 'anomalies' && state.chainId) p.set('chainId', state.chainId);
    if (state.from) p.set('from', state.from);
    if (state.to) p.set('to', state.to);
    if (state.commented) p.set('commented', state.commented);
    if (state.sort === 'asc') p.set('sort', 'asc');
    if (state.offset > 0) p.set('offset', String(state.offset));
    if (state.id) p.set('id', state.id);
  }
  return `?${p}`;
}

/** Changes the URL (new history entry unless replace) and renders it. */
function navigate(changes, replace = false) {
  const state = { ...readUrl(), endpoint: app.endpoint?.name ?? '', ...changes };
  history[replace ? 'replaceState' : 'pushState'](null, '', urlOf(state));
  render();
}

/** Filter parameters of the API from the URL state. */
function apiFilter(state) {
  return {
    dataType: state.dataType,
    chainId: state.kind === 'anomalies' ? state.chainId : '',
    from: state.from,
    to: state.to,
    commented: state.commented,
  };
}

function hasFilter(state) {
  return Object.values(apiFilter(state)).some((v) => (Array.isArray(v) ? v.length > 0 : v !== ''));
}

// ---------------------------------------------------------------- errors and refresh

function showUnreachable(error) {
  app.unreachable = true;
  const seconds = Math.max(0, Math.ceil((app.nextRefreshAt - Date.now()) / 1000));
  $('banner').textContent = `${error.message}, retrying in ${seconds} s. Data shown may be outdated.`;
  show($('banner'), true);
  $('status').textContent = 'unreachable';
  $('status').className = 'badge down';
}

let lastUnreachableError = null;
setInterval(() => {
  if (app.unreachable && lastUnreachableError) showUnreachable(lastUnreachableError);
}, 1000);

/** Shows an error where it belongs. Returns true if it was handled. */
function handleError(error, target) {
  if (unavailable(error)) {
    lastUnreachableError = error;
    showUnreachable(error);
    return true;
  }
  if (error instanceof ApiError && target) {
    target.textContent = error.message;
    show(target, true);
    return true;
  }
  message(error instanceof ApiError ? error.message : `Unexpected error: ${error.message}`);
  if (!(error instanceof ApiError)) console.error(error);
  return true;
}

function recovered() {
  const wasUnreachable = app.unreachable;
  app.unreachable = false;
  show($('banner'), false);
  return wasUnreachable;
}

function scheduleRefresh() {
  clearTimeout(app.timer);
  app.nextRefreshAt = Date.now() + app.config.refreshIntervalMs;
  app.timer = setTimeout(refresh, app.config.refreshIntervalMs);
}

/** Periodic refresh: status always, live data in the live view, the history after an outage. */
async function refresh() {
  scheduleRefresh();
  const state = readUrl();
  try {
    await refreshStatus();
    if (recovered() && state.view === 'history') {
      await renderHistory(state, app.renderId);
    }
    if (state.view === 'live') {
      await refreshLive(app.renderId);
    }
  } catch (error) {
    handleError(error);
  }
}

async function refreshStatus() {
  const status = await api('GET', '/status');
  const fogs = Object.entries(status.fogStatus ?? {});
  const active = fogs.filter(([, s]) => s.startsWith('ACTIVE')).length;
  const failed = status.storage?.failedWrites ?? 0;
  const parts = [
    `${status.serviceId}: fogs ${active}/${fogs.length} active`,
    `last event #${status.lastEventSequence}`,
  ];
  if (failed > 0) parts.push(`${failed} failed DB writes`);
  $('status').textContent = parts.join(' · ');
  $('status').className = `badge ${active === fogs.length && failed === 0 ? 'ok' : 'warn'}`;
  $('status').title = [
    ...fogs.map(([fog, s]) => `${fog}: ${s}`),
    `stored: ${status.storage?.storedAnomalies ?? '–'} anomalies, ${status.storage?.storedPredictions ?? '–'} predictions`,
    `job queue: ${status.queueSize}, dropped jobs: ${status.droppedJobs}`,
    `running since ${time(status.startedAt)}`,
  ].join('\n');
}

async function ensureMeta() {
  if (!app.meta) {
    app.meta = await api('GET', '/meta');
  }
  return app.meta;
}

// ---------------------------------------------------------------- charts

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/**
 * Loads the chart data of one data type for a period: the prediction changes in the period,
 * the last change before it (where the line starts) and the anomalies.
 */
async function loadChartData(dataType, from, to, chainId) {
  const [predictions, before, anomalies] = await Promise.all([
    api('GET', `/predictions${query({ dataType, from, to, sort: 'occurredAt,asc', limit: CHART_LIMIT })}`),
    api('GET', `/predictions${query({ dataType, to: from, limit: 1 })}`),
    api('GET', `/anomalies${query({ dataType, chainId, from, to, limit: CHART_LIMIT })}`),
  ]);
  return { predictions: predictions.items, before: before.items[0], anomalies: anomalies.items };
}

/** Step line of the most likely value with the bin as band; anomalies as markers. */
function chartDatasets(data, fromMs, toMs) {
  const steps = [];
  if (data.before) steps.push({ x: fromMs, item: data.before });
  for (const item of data.predictions) steps.push({ x: Date.parse(item.occurredAt), item });
  if (steps.length > 0) steps.push({ x: toMs, item: steps[steps.length - 1].item });
  const line = cssVar('--chart-line');
  const common = { stepped: true, pointRadius: 0, pointHitRadius: 0, tension: 0 };
  return [
    { ...common, label: 'bin upper', data: steps.map((s) => ({ x: s.x, y: s.item.binUpper })),
      borderWidth: 0, backgroundColor: cssVar('--chart-band'), fill: '+1' },
    { ...common, label: 'bin lower', data: steps.map((s) => ({ x: s.x, y: s.item.binLower })),
      borderWidth: 0, fill: false },
    { ...common, label: 'most likely', data: steps.map((s) => ({ x: s.x, y: s.item.mostLikelyValue, item: s.item })),
      borderColor: line, backgroundColor: line, borderWidth: 2, fill: false },
    { label: 'anomaly', showLine: false, pointStyle: 'crossRot', pointRadius: 7, pointBorderWidth: 2,
      borderColor: cssVar('--chart-anomaly'), backgroundColor: cssVar('--chart-anomaly'),
      data: data.anomalies.map((a) => ({ x: Date.parse(a.occurredAt), y: a.observedValue, item: a })) },
  ];
}

function tooltipLabel(context) {
  const item = context.raw?.item;
  if (!item) return null;
  if (context.dataset.label === 'anomaly') {
    return [`${item.chainId}`, `expected ${item.expected}`, `observed ${fmt(item.observedValue)} ${unit(item.unit)}`];
  }
  if (context.dataset.label === 'most likely') {
    return [`most likely ${fmt(item.mostLikelyValue)} ${unit(item.unit)}`, `bin ${range(item)}`,
      `confidence ${percent(item.confidence)}`];
  }
  return null;
}

/** Draws or updates the chart of one data type inside container. */
function drawChart(container, key, dataType, data, fromMs, toMs) {
  if (!window.Chart) return;
  const datasets = chartDatasets(data, fromMs, toMs);
  const existing = app.charts.get(key);
  if (existing) {
    existing.data.datasets = datasets;
    existing.options.scales.x.min = fromMs;
    existing.options.scales.x.max = toMs;
    existing.update('none');
    return;
  }
  const canvas = el('canvas', { 'aria-label': `Prediction history of ${dataType}`, role: 'img' });
  const card = el('div', { class: 'chart', 'data-chart': key },
    el('h3', { text: dataType }), el('div', { class: 'chart-box' }, canvas));
  container.append(card);
  const grid = cssVar('--chart-grid');
  const textColor = cssVar('--muted');
  const chart = new window.Chart(canvas, {
    type: 'line',
    data: { datasets },
    options: {
      animation: false,
      maintainAspectRatio: false,
      parsing: false,
      normalized: true,
      interaction: { mode: 'nearest', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          filter: (item) => item.dataset.label === 'anomaly' || item.dataset.label === 'most likely',
          callbacks: { title: (items) => (items.length ? shortTime(items[0].parsed.x) : ''), label: tooltipLabel },
        },
      },
      scales: {
        x: { type: 'linear', min: fromMs, max: toMs, grid: { color: grid },
          ticks: { color: textColor, maxTicksLimit: 6, callback: (value) => shortTime(value) } },
        y: { grid: { color: grid }, ticks: { color: textColor } },
      },
    },
  });
  app.charts.set(key, chart);
}

/** Removes the charts in container whose key is not in keep. */
function pruneCharts(container, keep) {
  for (const card of [...container.querySelectorAll('[data-chart]')]) {
    const key = card.getAttribute('data-chart');
    if (!keep.has(key)) {
      app.charts.get(key)?.destroy();
      app.charts.delete(key);
      card.remove();
    }
  }
}

function destroyAllCharts() {
  for (const chart of app.charts.values()) chart.destroy();
  app.charts.clear();
  $('live-charts').replaceChildren();
  $('history-charts').replaceChildren();
}

// ---------------------------------------------------------------- live view

async function refreshLive(renderId) {
  const toMs = Date.now();
  const fromMs = toMs - LIVE_PERIOD_MS;
  const from = new Date(fromMs).toISOString();
  const [meta, current, latest] = await Promise.all([
    ensureMeta(),
    api('GET', '/predictions/current'),
    api('GET', `/anomalies${query({ limit: 10 })}`),
  ]);
  const dataTypes = [...new Set([...meta.dataTypes, ...current.map((p) => p.dataType)])].sort();
  const chartData = await Promise.all(dataTypes.map((type) => loadChartData(type, from, null, null)));
  if (renderId !== app.renderId) return;

  $('cards').replaceChildren(...(current.length === 0
    ? [el('p', { class: 'muted', text: 'No predictions yet.' })]
    : current.map((p) => el('div', { class: 'card' },
      el('h3', { text: p.dataType }),
      el('div', { class: 'value', text: `${fmt(p.mostLikelyValue)} ${unit(p.unit)}` }),
      el('div', { text: `range ${range(p)}` }),
      el('div', { class: 'muted', text: `confidence ${percent(p.confidence)} · ${p.sampleCount} samples` })))));

  const container = $('live-charts');
  dataTypes.forEach((type, i) => drawChart(container, `live:${type}`, type, chartData[i], fromMs, toMs));
  pruneCharts(container, new Set(dataTypes.map((type) => `live:${type}`)));

  $('latest-anomalies').replaceChildren(...(latest.items.length === 0
    ? [el('li', { class: 'muted', text: 'No anomalies stored.' })]
    : latest.items.map((a) => el('li', {},
      el('a', { href: urlOf({ ...readUrl(), view: 'history', kind: 'anomalies', dataType: [], chainId: '', from: '',
        to: '', commented: '', offset: 0, id: String(a.id) }), 'data-nav': true, text: time(a.occurredAt) }),
      ` ${a.dataType} – ${summary(a)}`))));
}

// ---------------------------------------------------------------- history view

function renderFilterForm(state, meta) {
  const form = $('filter');
  form.elements.kind.value = state.kind;
  form.elements.commented.value = state.commented;
  form.elements.sort.value = state.sort;
  form.elements.from.value = toLocalInput(state.from);
  form.elements.to.value = toLocalInput(state.to);

  const types = [...new Set([...meta.dataTypes, ...state.dataType])].sort();
  $('data-types').replaceChildren(el('legend', { text: 'Data types' }), ...types.map((type) => el('label', {},
    el('input', { type: 'checkbox', name: 'dataType', value: type, checked: state.dataType.includes(type) }), type)));

  const chains = meta.chains.map((c) => c.id);
  if (state.chainId && !chains.includes(state.chainId)) chains.push(state.chainId);
  $('chain').replaceChildren(el('option', { value: '', text: 'any' }),
    ...chains.map((id) => el('option', { value: id, text: id,
      title: meta.chains.find((c) => c.id === id)?.description })));
  $('chain').value = state.chainId;
  $('chain').disabled = state.kind !== 'anomalies';
}

async function renderHistory(state, renderId) {
  show($('filter-error'), false);
  const meta = await ensureMeta();
  if (renderId !== app.renderId) return;
  renderFilterForm(state, meta);

  const filter = apiFilter(state);
  const page = await api('GET', `/${state.kind}${query({ ...filter, limit: PAGE_SIZE, offset: state.offset,
    sort: `occurredAt,${state.sort}` })}`);
  if (renderId !== app.renderId) return;
  app.lastTotal = page.total;
  renderTable(state, page);
  await renderHistoryCharts(state, meta, renderId);
}

function renderTable(state, page) {
  const what = state.kind === 'anomalies' ? 'anomalies' : 'predictions';
  $('result-count').textContent = `${page.total} ${what}${hasFilter(state) ? ' match the filter' : ' stored'}`;
  $('delete-matching').disabled = !hasFilter(state) || page.total === 0;
  $('delete-matching').title = hasFilter(state) ? '' : 'Set at least one filter first';

  const rows = page.items.map((item) => el('tr', { class: String(item.id) === state.id ? 'selected' : null },
    el('td', { text: time(item.occurredAt) }),
    el('td', { text: item.dataType }),
    el('td', { text: summary(item) }),
    el('td', { text: String(item.comments.length) }),
    el('td', { class: 'actions' },
      el('button', { type: 'button', text: 'Details', onclick: () => navigate({ id: String(item.id) }) }),
      el('button', { type: 'button', class: 'danger', text: 'Delete', onclick: () => deleteResult(state.kind, item) }))));
  $('results').tBodies[0].replaceChildren(...(rows.length > 0 ? rows
    : [el('tr', {}, el('td', { colspan: 5, class: 'muted', text: 'Nothing found.' }))]));

  const first = page.total === 0 ? 0 : page.offset + 1;
  const last = page.offset + page.items.length;
  $('page-info').textContent = `${first}–${last} of ${page.total}`;
  $('prev').disabled = page.offset === 0;
  $('next').disabled = last >= page.total;
}

async function renderHistoryCharts(state, meta, renderId) {
  const toMs = state.to ? Date.parse(state.to) : Date.now();
  const fromMs = state.from ? Date.parse(state.from) : toMs - HISTORY_DEFAULT_PERIOD_MS;
  const types = state.dataType.length > 0 ? state.dataType : meta.dataTypes;
  const chainId = state.kind === 'anomalies' ? state.chainId : '';
  const from = new Date(fromMs).toISOString();
  const to = state.to ? new Date(toMs).toISOString() : null;
  const data = await Promise.all(types.map((type) => loadChartData(type, from, to, chainId)));
  if (renderId !== app.renderId) return;
  $('chart-period').textContent = `Charts: ${time(from)} – ${state.to ? time(to) : 'now'}`
    + (state.from ? '' : ' (last 24 h; set "From" for another period)');
  const container = $('history-charts');
  types.forEach((type, i) => drawChart(container, `history:${type}`, type, data[i], fromMs, toMs));
  pruneCharts(container, new Set(types.map((type) => `history:${type}`)));
}

async function deleteResult(kind, item) {
  const name = kind === 'anomalies' ? 'anomaly' : 'prediction';
  if (!confirm(`Delete ${name} #${item.id} (${time(item.occurredAt)}) and its comments?`)) return;
  try {
    await api('DELETE', `/${kind}/${item.id}`);
    message(`Deleted ${name} #${item.id}.`);
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) {
      handleError(error);
      return;
    }
    message(`${name} #${item.id} was already deleted.`);
  }
  const state = readUrl();
  navigate({ id: state.id === String(item.id) ? '' : state.id }, true);
}

async function deleteMatching() {
  const state = readUrl();
  if (!hasFilter(state)) return;
  if (!confirm(`Delete all ${app.lastTotal} ${state.kind} that match the filter (and their comments)? `
    + 'Results stored after this moment are kept.')) return;
  try {
    const result = await api('DELETE', `/${state.kind}${query(apiFilter(state))}`);
    message(`Deleted ${result.deleted} ${state.kind}.`);
    navigate({ offset: 0, id: '' }, true);
  } catch (error) {
    handleError(error, $('filter-error'));
  }
}

function applyFilter(event) {
  event.preventDefault();
  const form = new FormData($('filter'));
  const kind = form.get('kind') === 'predictions' ? 'predictions' : 'anomalies';
  navigate({
    view: 'history',
    kind,
    dataType: form.getAll('dataType'),
    chainId: kind === 'anomalies' ? (form.get('chainId') ?? '') : '',
    from: fromLocalInput(form.get('from')),
    to: fromLocalInput(form.get('to')),
    commented: form.get('commented') ?? '',
    sort: form.get('sort') === 'asc' ? 'asc' : 'desc',
    offset: 0,
    id: '',
  });
}

// ---------------------------------------------------------------- details and comments

async function renderDetails(state, renderId) {
  const name = state.kind === 'anomalies' ? 'anomaly' : 'prediction';
  let item;
  try {
    item = await api('GET', `/${state.kind}/${encodeURIComponent(state.id)}`);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
      message(error.status === 404 ? `${name} #${state.id} was deleted.` : error.message);
      navigate({ id: '' }, true);
      return;
    }
    throw error;
  }
  if (renderId !== app.renderId) return;
  $('details-title').textContent = `${name} #${item.id}`;
  $('details-fields').replaceChildren(...Object.entries(item)
    .filter(([key]) => key !== 'comments')
    .flatMap(([key, value]) => [el('dt', { text: key }), el('dd', { text: fieldText(key, value) })]));
  $('details-delete').onclick = () => deleteResult(state.kind, item);
  renderComments(state, item.comments);
  show($('details'), true);
  document.querySelector('main').classList.add('with-details');
}

function fieldText(key, value) {
  if (value === null || value === undefined) return '–';
  if (typeof value === 'object') {
    return `${value.condition}, ${time(value.from)} – ${time(value.to)}, ${value.samples} samples`;
  }
  if (typeof value === 'string' && /(At|Until)$/.test(key)) return time(value);
  if (key === 'unit') return unit(value);
  if (key === 'confidence') return percent(value);
  if (Number.isInteger(value)) return String(value); // ids, epochs, sequences, counts: no grouping
  return typeof value === 'number' ? fmt(value, 4) : String(value);
}

function hideDetails() {
  show($('details'), false);
  document.querySelector('main').classList.remove('with-details');
}

function renderComments(state, comments) {
  show($('comment-error'), false);
  $('comments').replaceChildren(...(comments.length === 0
    ? [el('li', { class: 'muted', text: 'No comments yet.' })]
    : comments.map((comment) => commentItem(state, comment))));
}

function commentItem(state, comment) {
  const edited = comment.updatedAt ? ` · edited ${time(comment.updatedAt)}` : '';
  const item = el('li', {},
    el('p', { class: 'comment-text', text: comment.text }),
    el('div', { class: 'comment-meta' },
      `${comment.author ?? 'anonymous'} · ${time(comment.createdAt)}${edited}`,
      el('button', { type: 'button', text: 'Edit', onclick: () => editComment(state, item, comment) }),
      el('button', { type: 'button', class: 'danger', text: 'Delete', onclick: () => deleteComment(state, comment) })));
  return item;
}

function editComment(state, item, comment) {
  const text = el('textarea', { maxlength: 2000, rows: 3 });
  text.value = comment.text;
  const error = el('p', { class: 'error', hidden: true });
  item.replaceChildren(text,
    el('div', { class: 'comment-meta' },
      el('button', { type: 'button', text: 'Save', onclick: async () => {
        try {
          await api('PUT', `/comments/${comment.id}`, { text: text.value });
          reloadDetails(state);
        } catch (e) {
          if (e instanceof ApiError && e.status === 404) {
            message('The comment was deleted meanwhile.');
            reloadDetails(state);
          } else {
            handleError(e, error);
          }
        }
      } }),
      el('button', { type: 'button', text: 'Cancel', onclick: () => item.replaceWith(commentItem(state, comment)) })),
    error);
  text.focus();
}

async function deleteComment(state, comment) {
  if (!confirm('Delete this comment?')) return;
  try {
    await api('DELETE', `/comments/${comment.id}`);
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) {
      handleError(error, $('comment-error'));
      return;
    }
  }
  reloadDetails(state);
}

async function addComment(event) {
  event.preventDefault();
  const state = readUrl();
  const form = $('comment-form');
  try {
    await api('POST', `/${state.kind}/${encodeURIComponent(state.id)}/comments`,
      { text: form.elements.text.value, author: form.elements.author.value || null });
    form.elements.text.value = '';
    reloadDetails(state);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      message('The result was deleted meanwhile.');
      navigate({ id: '' }, true);
    } else {
      handleError(error, $('comment-error'));
    }
  }
}

/** After a comment change: details and table (comment counts) again. */
function reloadDetails() {
  render();
}

// ---------------------------------------------------------------- render

function selectEndpoint(name) {
  const endpoint = app.config.endpoints.find((e) => e.name === name) ?? app.config.endpoints[0];
  if (endpoint !== app.endpoint) {
    app.endpoint = endpoint;
    app.meta = null;
    destroyAllCharts();
    $('endpoint').value = endpoint.name;
  }
}

async function render() {
  const renderId = ++app.renderId;
  const state = readUrl();
  selectEndpoint(state.endpoint);
  for (const view of ['live', 'history']) {
    show($(view), state.view === view);
    const tab = $(`tab-${view}`);
    tab.href = urlOf({ ...state, view, offset: 0, id: '' });
    if (state.view === view) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  }
  $('reset-filter').href = urlOf({ view: 'history', endpoint: app.endpoint.name, kind: state.kind, dataType: [],
    chainId: '', from: '', to: '', commented: '', sort: 'desc', offset: 0, id: '' });
  if (!(state.view === 'history' && state.id)) hideDetails();

  try {
    await refreshStatus();
    recovered();
    if (state.view === 'live') {
      await refreshLive(renderId);
    } else {
      await renderHistory(state, renderId);
      if (state.id) await renderDetails(state, renderId);
    }
  } catch (error) {
    handleError(error, state.view === 'history' ? $('filter-error') : null);
  }
  scheduleRefresh();
}

async function start() {
  const response = await fetch('config.json', { cache: 'no-store' });
  app.config = await response.json();
  $('endpoint').replaceChildren(...app.config.endpoints.map((e) => el('option', { value: e.name, text: e.name,
    title: e.restUrl })));

  $('endpoint').addEventListener('change', () => navigate({ endpoint: $('endpoint').value, offset: 0, id: '' }));
  $('filter').addEventListener('submit', applyFilter);
  $('filter').elements.kind.addEventListener('change', (e) => { $('chain').disabled = e.target.value !== 'anomalies'; });
  $('delete-matching').addEventListener('click', deleteMatching);
  $('prev').addEventListener('click', () => navigate({ offset: Math.max(0, readUrl().offset - PAGE_SIZE), id: '' }));
  $('next').addEventListener('click', () => navigate({ offset: readUrl().offset + PAGE_SIZE, id: '' }));
  $('details-close').addEventListener('click', () => navigate({ id: '' }));
  $('comment-form').addEventListener('submit', addComment);
  document.addEventListener('click', (event) => {
    const link = event.target.closest('a[data-nav]');
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey) return;
    event.preventDefault();
    history.pushState(null, '', link.getAttribute('href'));
    render();
  });
  window.addEventListener('popstate', render);
  await render();
}

start().catch((error) => {
  $('banner').textContent = `WEB ML could not start: ${error.message}`;
  show($('banner'), true);
});
