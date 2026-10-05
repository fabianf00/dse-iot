# 09 – MS ML: Historic Storage (ORM) and REST API

## 1. Responsibilities added to MS ML

- **Record** every anomaly and every prediction change in a database, with metadata:
  timestamps and manually added comments.
- **Manage** the stored results through a REST API: load, filter, delete and comment.
- **Serve live data** over REST too (current predictions, status), so WEB ML needs nothing else.
- Unchanged: UDP fog client, jobs/workers, behaviour chains, histograms, in-memory `EventLog`,
  RMI API for CLNT ML.

## 2. Storage

### 2.1 Technology and rules

- Jakarta Persistence 3.2, Hibernate ORM 7.4, H2 2.5 embedded (file database per ML instance).
- **No explicit SQL:** no SQL or JPQL strings in the code. Single objects go through
  `EntityManager.find/persist/merge/remove`. Lists, counts and bulk deletes go through the
  **Criteria API** (`CriteriaBuilder`, `CriteriaQuery`, `CriteriaDelete`). The schema is
  generated from the entities (`hibernate.hbm2ddl.auto=update`). No `persistence.xml`: the
  `EntityManagerFactory` is built in code from the ML config (Hibernate `Configuration` /
  JPA `PersistenceConfiguration`).
- One `EntityManager` and one transaction per unit of work (`Transactions.inTransaction(...)`
  helper). The `EntityManagerFactory` is shared and thread-safe.

### 2.2 Entities

Single-table inheritance, so comments can reference "a result" no matter its kind, and lists
across both kinds stay one query.

```
MlResultEntity  (abstract, @Entity, @Inheritance(SINGLE_TABLE), discriminator "kind")
  id              Long       generated
  kind            ANOMALY | PREDICTION
  mlInstance      String     serviceId of the ML instance that produced it
  serverEpoch     long       ┐ unique together with mlInstance: recording is idempotent
  eventSequence   long       ┘ (same event never stored twice)
  dataType        String
  occurredAt      Instant    when ML detected the anomaly / changed the prediction
  recordedAt      Instant    when it was written to the database
  comments        List<CommentEntity>  (OneToMany, cascade ALL, orphanRemoval, ordered by createdAt)

AnomalyEntity extends MlResultEntity
  chainId, chainDescription, expected (e.g. "temperature > 20.0"), observedValue, unit,
  observedAt, expectedUntil,
  past    PhaseObservationEmbeddable (condition, from, to, samples)   @Embedded, column prefix past_
  current PhaseObservationEmbeddable                                   @Embedded, column prefix current_

PredictionEntity extends MlResultEntity
  unit, binLower, binUpper, mostLikelyValue, confidence, sampleCount,
  previousBinLower, previousBinUpper   (null for the first prediction of a data type)

CommentEntity
  id          Long generated
  result      MlResultEntity (ManyToOne, not null)
  text        String (1..2000 chars)
  author      String (optional, ≤ 100 chars)
  createdAt   Instant
  updatedAt   Instant (null until edited)
```

Indexes: `(kind, dataType, occurredAt)`, `(chainId)`, unique `(mlInstance, serverEpoch, eventSequence)`.

### 2.3 Recording path

```
worker thread: EventLog.append(...) ──► EventLog listeners (new, synchronous, must not block)
                                          └─► ResultRecorder.onEvent(event)
                                                └─ offer to bounded queue (10 000)
                                                       ▼
                               "ml-1-recorder" thread: map event → entity → repository.save
```

- `EventLog` gets `addListener(Consumer<MlEvent>)`; listeners are called after the append,
  outside the lock.
- `ResultRecorder` maps `AnomalyEvent` → `AnomalyEntity` and `PredictionChangedEvent` →
  `PredictionEntity`, and writes them on its own thread (F8).
- Failures: a full queue or a DB error is logged and counted (`storage.failedWrites` in the
  status). A violated unique constraint (event already stored) is ignored.
- On shutdown the recorder drains its queue (up to 5 s) before the `EntityManagerFactory` is closed.

### 2.4 Repository and use cases

```java
public interface ResultRepository {
    <T extends MlResultEntity> T save(T result);
    Optional<MlResultEntity> find(ResultKind kind, long id);
    Page<MlResultEntity> search(ResultFilter filter, PageRequest page);   // Criteria API
    long count(ResultFilter filter);
    boolean delete(ResultKind kind, long id);
    long deleteMatching(ResultFilter filter);                              // CriteriaDelete
    CommentEntity addComment(long resultId, String text, String author);
    Optional<CommentEntity> updateComment(long commentId, String text);
    boolean deleteComment(long commentId);
}

public record ResultFilter(ResultKind kind, Set<String> dataTypes, String chainId,
        Instant from, Instant to, Boolean commented, String mlInstance) { }
public record PageRequest(int limit, int offset, SortOrder order) { }   // limit 1..500, default 50, newest first
```

- `JpaResultRepository` implements it with the Criteria API. `commented=true/false` becomes an
  `exists` / `not exists` subquery on comments. The bulk delete removes the comments first
  (Criteria delete with a subquery), then the results, in one transaction.
- `ResultService` (package `msml.results`) holds the use cases for the REST layer: validates
  input (comment length, filter combinations, refusal of an unfiltered bulk delete) and maps
  "not found" to empty results. The REST controllers stay thin.

### 2.5 Config (new block in `config/ml-1.json`)

```json
"storage": {
  "jdbcUrl": "jdbc:h2:file:./run/data/ml-1",
  "user": "sa",
  "password": "",
  "recorderQueueCapacity": 10000
}
```

`jdbcUrl` is relative to the working directory; the scripts start every process in
`implementation/`. Tests use `jdbc:h2:mem:<random>` or a temp directory.

## 3. REST API ("ML REST API v1")

- **Technology:** HTTP/1.1, JSON (UTF-8), Javalin 7 on Jetty 12, served by MS ML.
- **Base URL:** `http://<rest.host>:<rest.port>/api/v1`, by default `http://127.0.0.1:50303/api/v1`.
- **Time values:** ISO-8601 instants (`2021-11-20T14:03:05Z`). **IDs:** numbers.
- **Errors:** RFC 9457 problem details (`application/problem+json`):
  `{"type":"about:blank","title":"Not Found","status":404,"detail":"anomaly 17 does not exist"}`.
- **CORS:** origins from `rest.allowedOrigins` (default the WEB ML origins
  `http://127.0.0.1:50400` and `http://localhost:50400`). Methods GET, POST, PUT, DELETE.
- **Auth:** none (local or trusted network; out of scope).

### 3.1 Endpoints

| Method & path | Purpose | Success |
|---|---|---|
| `GET /anomalies` | stored anomalies, filtered and paged | 200 page |
| `GET /anomalies/{id}` | one anomaly with its comments | 200 / 404 |
| `DELETE /anomalies/{id}` | delete one anomaly and its comments | 204 / 404 |
| `DELETE /anomalies?<filter>` | delete all anomalies that match; at least one filter is required | 200 `{"deleted": n}` / 400 |
| `GET /predictions` | stored prediction changes, filtered and paged | 200 page |
| `GET /predictions/{id}` | one prediction change with its comments | 200 / 404 |
| `DELETE /predictions/{id}` | delete one | 204 / 404 |
| `DELETE /predictions?<filter>` | delete all that match (at least one filter) | 200 `{"deleted": n}` / 400 |
| `GET /predictions/current` | live: current prediction per data type (not stored, from the histograms) | 200 list |
| `GET /{anomalies\|predictions}/{id}/comments` | comments of a result | 200 list / 404 |
| `POST /{anomalies\|predictions}/{id}/comments` | add a comment `{"text": "...", "author": "..."}` | 201 + `Location: /api/v1/comments/{commentId}` / 400 / 404 |
| `PUT /comments/{commentId}` | edit the text `{"text": "..."}` | 200 comment / 400 / 404 |
| `DELETE /comments/{commentId}` | delete a comment | 204 / 404 |
| `GET /status` | live: same content as RMI `getStatus()` + storage counters | 200 |
| `GET /meta` | data types and behaviour chains (id, description), e.g. for filter drop-downs | 200 |

**Filter parameters** (lists and bulk delete): `dataType` (repeatable), `chainId` (anomalies
only), `from`, `to` (on `occurredAt`, inclusive), `commented=true|false`. **Paging:** `limit`
(1–500, default 50), `offset` (default 0), `sort=occurredAt,desc|asc` (default desc).
Unknown parameters or invalid values → 400 with the parameter in `detail`.

### 3.2 Representations (examples)

```json
GET /api/v1/anomalies?dataType=temperature&limit=1
{
  "items": [ {
    "id": 17,
    "kind": "ANOMALY",
    "mlInstance": "ml-1",
    "dataType": "temperature",
    "occurredAt": "2021-11-20T14:03:05Z",
    "recordedAt": "2021-11-20T14:03:05.120Z",
    "chainId": "temp-sudden-drop",
    "chainDescription": "A warm room stays warm; ...",
    "expected": "temperature > 20.0",
    "observedValue": 18.86,
    "unit": "CELSIUS",
    "observedAt": "2021-11-20T14:03:04Z",
    "expectedUntil": "2021-11-20T14:04:01Z",
    "past":    { "condition": "temperature > 20.0", "from": "...", "to": "...", "samples": 6 },
    "current": { "condition": "temperature > 20.0", "from": "...", "to": "...", "samples": 6 },
    "comments": [ { "id": 3, "text": "window opened for cleaning", "author": "dispatcher",
                    "createdAt": "2021-11-20T14:10:00Z", "updatedAt": null } ]
  } ],
  "total": 4, "limit": 1, "offset": 0
}
```

```json
GET /api/v1/predictions?dataType=co2&from=2021-11-20T14:00:00Z
{ "items": [ { "id": 21, "kind": "PREDICTION", "dataType": "co2", "occurredAt": "...",
               "unit": "PPM", "binLower": 533.6, "binUpper": 545.2, "mostLikelyValue": 539.4,
               "confidence": 0.33, "sampleCount": 27, "previousBinLower": 522.0,
               "previousBinUpper": 533.6, "comments": [] } ],
  "total": 1, "limit": 50, "offset": 0 }
```

### 3.3 Use cases (good and bad)

| Case | Behaviour |
|---|---|
| Dispatcher lists today's temperature anomalies | `GET /anomalies?dataType=temperature&from=…` → newest first, paged |
| Dispatcher explains an anomaly | `POST /anomalies/17/comments` → 201; the comment appears in `GET /anomalies/17` |
| False alarm cleanup | `DELETE /anomalies/17` → 204, comments deleted too |
| Cleanup of a test period | `DELETE /predictions?to=2021-11-20T00:00:00Z` → `{"deleted": 120}` |
| Unfiltered bulk delete | `DELETE /anomalies` → 400 "at least one filter is required" (protects against wiping everything) |
| Unknown id / comment on a deleted result | 404 problem details |
| Empty or too long comment, `limit=0`, `from` not a timestamp, `chainId` on predictions | 400 problem details naming the parameter |
| Body not JSON / wrong content type | 400 / 415 |
| DB unavailable | 503 problem details; live endpoints (`/predictions/current`, `/status`) keep working |
| ML restart | Stored results and comments survive; new events are recorded with the new epoch |

### 3.4 Error case (novel, for this extension)

**Race between recording and deleting:** a dispatcher bulk-deletes "everything before now"
while the recorder is still writing an older event from its queue. That event would reappear
after the delete. Mitigation: bulk deletes filter on `recordedAt` as well as `occurredAt`
(`recordedAt <= request time`), and the recorder stamps `recordedAt` when it writes, so
results recorded after the delete started are never affected. The visible effect is
deterministic: what was in the database when the request arrived is deleted.

## 4. Package design (UML draft → `uml/ms_ml.puml`, new packages)

```plantuml
@startuml ms_ml_storage_rest
package at.ac.univie.dse.iot.msml.storage {
  abstract class MlResultEntity <<Entity>> { id : Long
    mlInstance : String
    serverEpoch : long
    eventSequence : long
    dataType : String
    occurredAt : Instant
    recordedAt : Instant }
  class AnomalyEntity <<Entity>> extends MlResultEntity
  class PredictionEntity <<Entity>> extends MlResultEntity
  class CommentEntity <<Entity>> { id : Long
    text : String
    author : String
    createdAt : Instant
    updatedAt : Instant }
  class PhaseObservationEmbeddable <<Embeddable>>
  enum ResultKind { ANOMALY
    PREDICTION }
  interface ResultRepository
  class JpaResultRepository implements ResultRepository
  class ResultFilter <<record>>
  class PageRequest <<record>>
  class Page <<record>>
  class Database { +{static} open(config : StorageConfig) : Database
    +transactions() : Transactions
    +close() : void }
  class Transactions { +inTransaction(work : Function<EntityManager, T>) : T }
  class ResultRecorder { +onEvent(event : MlEvent) : void
    +start() : void
    +stop(timeout : Duration) : void
    +failedWrites() : long }
  class StorageConfig <<record>>
}
package at.ac.univie.dse.iot.msml.results {
  class ResultService { +search(...) : Page
    +find(...) : Optional
    +delete(...) : boolean
    +deleteMatching(...) : long
    +addComment(...) : CommentEntity
    +updateComment(...) : Optional
    +deleteComment(...) : boolean }
}
package at.ac.univie.dse.iot.msml.rest {
  class RestServer { +start() : void
    +stop() : void }
  class ResultsController
  class CommentsController
  class LiveController
  class FilterParser { +parse(ctx : Context, kind : ResultKind) : ResultFilter }
  class ResourceMapper
  class ProblemDetails <<record>>
  class RestConfig <<record>>
}
MlResultEntity "1" *-- "0..*" CommentEntity
AnomalyEntity *-- PhaseObservationEmbeddable
JpaResultRepository --> Transactions
ResultRecorder --> ResultRepository
ResultService --> ResultRepository
RestServer --> ResultsController
RestServer --> CommentsController
RestServer --> LiveController
ResultsController --> ResultService
CommentsController --> ResultService
ResultsController --> FilterParser
LiveController --> HistogramPredictor
EventLog ..> ResultRecorder : listener
@enduml
```

New `rest` config block:

```json
"rest": { "host": "0.0.0.0", "port": 50303,
          "allowedOrigins": ["http://127.0.0.1:50400", "http://localhost:50400"] }
```

`MlService` lifecycle: open the database → start the recorder → (existing components) →
start the REST server. Stop in reverse order. If the database cannot be opened, the start
fails with a clear message (exit code 1).

## 5. Tests for this part

Unit tests (in `ms_ml`, H2 in-memory):
- `JpaResultRepositoryTest`: save and find both kinds; every filter alone and combined;
  paging and sort; `commented` true/false; delete cascades comments; bulk delete only
  removes matching rows; the unique constraint makes recording idempotent.
- `ResultRecorderTest`: events become entities with all fields; full queue and DB errors
  are counted, not thrown; stop drains the queue.
- `EventLogTest` (extended): listeners get every event once, outside the lock.
- `FilterParserTest`: valid and invalid parameters → filter or 400 details.
- `ResultServiceTest`: comment validation, refusal of an unfiltered bulk delete,
  not-found handling.
- `RestApiTest` (Javalin test server, in `ms_ml`): status codes, problem details, CORS
  preflight, `Location` header.

Integration tests (IT-REST, IT-PERSIST): see 11 §3.
