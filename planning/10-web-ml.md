# 10 – WEB ML: Web-Based Visualization and Management

## 1. Responsibilities

- **Graphical** visualization of current and historic ML results: charts of the prediction
  history per data type, with anomalies as markers, plus the current predictions and the
  system state.
- Management: display, filter, delete and comment anomalies and predictions, all through the
  MS ML REST API (09 §3).
- Minimal UI layer: **hardly any logic and no stored data**.

## 2. "Minimal UI layer" rules (checked in reviews and IT-WEB)

1. All filtering, sorting, paging and counting happen in MS ML (query parameters). The UI
   never filters or aggregates data itself.
2. The UI keeps no data: no `localStorage`, `sessionStorage`, IndexedDB or cookies. State
   lives in the URL (current tab and filter as query string), so reload and bookmarks work.
3. The UI code is limited to: calling the REST API, mapping JSON to DOM and chart data, and
   formatting (times, units).
4. The web server holds no business logic. It serves static files and one generated
   `config.json` (the REST base URLs from its config file).

## 3. Module `web_ml`

```
web_ml/
  src/main/java/at/ac/univie/dse/iot/webml/
    WebMlApplication.java     main
    WebMlService.java         AbstractMicroService<WebMlConfig>: JDK HttpServer, static files + config.json
    WebMlConfig.java          serviceId, bindHost, port, endpoints[{name, restUrl}], refreshIntervalMs
    StaticFiles.java          classpath resources under /web, content types, no path traversal, caching headers
  src/main/resources/web/
    index.html                layout: header (endpoint selector, status), tabs Live | History
    app.js                    REST calls, rendering, chart setup (vanilla JS, ES modules)
    style.css
    vendor/chart.umd.min.js   Chart.js 4.5.1 (MIT, license file next to it)
```

- Server: JDK `com.sun.net.httpserver.HttpServer`, no extra dependency. Only GET and HEAD;
  everything else → 405. Unknown paths → 404. Paths are normalized, and anything outside
  `/web` → 404.
- `GET /config.json`: `{"endpoints":[{"name":"ml-1","restUrl":"http://127.0.0.1:50303/api/v1"}],"refreshIntervalMs":5000}`.
- Config `config/web-ml.json`:

```json
{
  "serviceId": "web-ml",
  "port": 50400,
  "endpoints": [ { "name": "ml-1", "restUrl": "http://127.0.0.1:50303/api/v1" } ],
  "refreshIntervalMs": 5000
}
```

Deployment: `java -jar web_ml.jar config/web-ml.json`, then open `http://127.0.0.1:50400/`.
The UI depends on MS ML's REST API being reachable from the *browser* (and on MS ML allowing
the UI's origin, see `rest.allowedOrigins`).

## 4. Views

**Header:** endpoint selector (from `config.json`). Status badge from `GET /status`: fogs
ACTIVE/PENDING, last event, failed DB writes. Shows "unreachable" with the time of the next
retry when the REST call fails.

**Live** (auto-refresh every `refreshIntervalMs`):
- One card per data type with the current prediction (`GET /predictions/current`): range,
  most likely value, confidence, sample count.
- One chart per data type: prediction history of the last hour
  (`GET /predictions?dataType=…&from=now-1h&sort=occurredAt,asc&limit=500`) as a step line of
  the most likely value with a shaded bin band. Anomalies of the same period
  (`GET /anomalies?dataType=…&from=now-1h`) as red markers with a tooltip (chain, expected,
  observed).
- Latest 10 anomalies as a list with links to the history view.

**History:**
- Filter form: kind (anomalies / predictions), data types, chain (from `GET /meta`),
  from/to, commented yes/no/any. Submitting changes the URL and reloads.
- Paged table (server-side paging): time, data type, summary (chain + observed value, or
  range + confidence), comment count, actions: details, delete.
- Chart above the table for the filtered period (same chart type as in Live).
- Details panel: all fields, comments (add, edit, delete), delete result.
- "Delete all matching" (only with at least one filter): shows `total` from the last search
  and asks for confirmation, then `DELETE /anomalies?<filter>`.

## 5. Error handling and edge cases

| Case | UI behaviour |
|---|---|
| MS ML unreachable or 503 | Banner "ML unreachable, retrying in N s"; the last rendered data stays visible (on screen only, not stored) |
| 404 on details or comment (deleted meanwhile) | Message "was deleted", the list reloads |
| 400 from the API | The problem `detail` is shown next to the form |
| Comment text with HTML/JS (`<script>…`) | Rendered with `textContent` only, never `innerHTML`: no XSS. IT-WEB stores such a comment and checks that the API returns it verbatim. The UI rule is checked in review |
| Many results | Server-side paging, charts limited to 500 points per request |
| Time zones | API in UTC; the UI formats in the browser's local time zone |

## 6. Tests

- Unit (`web_ml`): `StaticFilesTest` (content types, 404, path traversal such as
  `/../config` and `%2e%2e`, HEAD); `WebMlConfigTest` (validation, defaults);
  `WebMlServiceTest` (`config.json` content).
- Integration: IT-WEB (11 §3).
- Manual check list (documented in the README): Live charts update; filter → URL → reload
  keeps the filter; add/edit/delete a comment; delete one and delete all matching; ML stopped
  → banner, ML started → recovers.
