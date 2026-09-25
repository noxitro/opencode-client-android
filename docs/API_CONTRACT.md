# API契約 — opencode serve

**ピン留めバージョン: opencode 1.18.21**(2026-08-23に実機 `GET /doc` のOpenAPI 3.1 specから採取)。
opencodeを更新したら本書を作り直す。**実装は推測でフィールド名を書かないこと。**

## 認証

- HTTP Basic。ユーザー名既定 `opencode`、パスワードはサーバー側環境変数 `OPENCODE_SERVER_PASSWORD`。
- 未認証は `401`。

## エンドポイント(MVP使用分)

| メソッド | パス | リクエスト | レスポンス |
|---|---|---|---|
| GET | `/global/health` | - | `{healthy: boolean, version: string}` |
| GET | `/session` | - | `Session[]` |
| POST | `/session` | `{parentID?, title?, agent?, model?{id,providerID,variant?}, metadata?, permission?, workspaceID?}` | `Session` |
| DELETE | `/session/{sessionID}` | - | (成功判定) |
| GET | `/session/{sessionID}/message` | query: `limit?` | `{info: Message, parts: Part[]}[]` |
| POST | `/session/{sessionID}/prompt_async` | `PromptInput`(下記) | `204 No Content` |
| POST | `/session/{sessionID}/abort` | - | 成功判定 |
| POST | `/session/{sessionID}/permissions/{permissionID}` | `{response: "once"\|"always"\|"reject"}` | 成功判定 |
| POST | `/permission/{requestID}/reply` | `{reply: "once"\|"always"\|"reject", message?}` | 成功判定(v2経路) |
| GET | `/event` | - | SSEストリーム(下記) |

注: permission応答は2経路ある。SSEの `permission.asked` で受けた `id`(接頭辞 `per`)は
v2経路 `/permission/{requestID}/reply` と互換。MVPは **legacy経路
(`/session/{sid}/permissions/{pid}`)** を主に使う(両方ともspec上存在)。

## 主要オブジェクト

### Session (必須フィールドのみ)
```json
{
  "id": "ses...", "slug": "...", "projectID": "...",
  "directory": "...", "title": "...", "version": "...",
  "time": {"created": 0, "updated": 0},
  // 任意: parentID, summary{additions,deletions,files,diffs}, cost, tokens,
  //        share{url}, agent, model{id,providerID,variant?}, revert{messageID,...}
}
```

### Message = UserMessage | AssistantMessage (anyOf)

2026-08-23に実機 `/doc` で確認済み。両バリアント共通の必須フィールド(実装が依存してよい範囲):

```json
{
  "id": "msg...",          // 必須 (UserMessage/AssistantMessageともrequired)
  "sessionID": "ses...",   // 必須
  "role": "user" | "assistant"  // 必須。UserMessage=enum["user"], AssistantMessage=enum["assistant"]
}
```

他のフィールド(time/agent/model/parentID/cost/tokens等)はバリアントごとに必須性が異なるため、
MVPのパースでは上記3つのみに依存し、残りは ignoreUnknownKeys で捨てる。

### Part (anyOf — type判別)
- `TextPart`: `{id:"prt..", sessionID, messageID, type:"text", text, synthetic?, time{start,end?}}`
- `ToolPart`: `{..., type:"tool", callID, tool, state:{status:"pending"|"running"|"completed"|"error", input, raw,...}}`
- 他: reasoning / file / step-start / step-finish / patch / agent / retry / compaction / subtask / snapshot

### PromptInput (POST prompt_async のbody)
```json
{"parts": [{"type": "text", "text": "..."}]}
// parts必須。任意: messageID?, model{providerID,modelID}?, agent?, noReply?, tools?, system?, variant?
```

## SSEイベント (`GET /event`)

各イベントのenvelope:
```json
{"id": "evt_...", "type": "<event-name>", "properties": {...}}
```

MVPで処理するイベント:

| type | properties | 用途 |
|---|---|---|
| `message.part.updated` | `{sessionID, part: Part, time}` | チャット逐次描画 |
| `message.updated` | `{sessionID, info: Message}` | メッセージ状態更新 |
| `permission.asked` | `{id:"per..", sessionID, permission, patterns[], metadata, always[], tool?{messageID,callID}}` | 承認ダイアログ |
| `permission.replied` | (同系) | ダイアログ解消 |
| `session.idle` | `{sessionID}` | 実行完了→入力欄復帰 |
| `session.error` | - | エラー表示 |

注: `session.error` のpropertiesはspec上必須項目なし(任意の `sessionID`, `error`)。
実装は sessionID をベストエフォートで参照し(無ければ全体エラー扱い)、error本体には依存しない。
| `server.connected` | - | 接続確立の目印 |

注意: specにはv2系(`session.next.*` / `EventSessionNextTextDelta` 等)も多数あるが、
MVPは上表のレガシー系イベントのみ扱う。未知のtypeは**無視して破棄する**(パース失敗で落とさない)。

## エラーレスポンス

effect_HttpApiError_* 系(400/401/403/404/409/429/500)。409=SessionBusyError(prompt送信時に
実行中セッションへ再送した場合等)。ボディshapeは未ピン留めのため、MVPはステータスコードのみで分岐する。

---

## Q1で使用する分(2026-08-27 追記)

**採取方法**: 実機 `opencode serve` 1.18.21(`scripts/serve.ps1 -Port 4097`、cwd=本リポジトリ)を起動し、
`GET /doc` の OpenAPI をその場で取得したうえで、**実際にエンドポイントを叩いた応答**と
**実際に流れた SSE フレーム**を根拠にした。この回の `/doc` は
`docs/spec/opencode-1.18.21-openapi.json`(2026-08-25採取)と **SHA-256 が一致**した
(`c3a9f94af0c3324d97b482b14c692e810ce7ccac3136319ba46334de972b4cf1`)ので、
スナップショットは採取時点から変化していないことも同時に確認できている。
**推測で書いたフィールドはこの節に無い。** spec由来か実測由来かを各項目に明記する。

### エンドポイント

| メソッド | パス | リクエスト | レスポンス |
|---|---|---|---|
| GET | `/session` | query: `start?`(number) `limit?`(number) `search?`(string) `directory?` `workspace?` `scope?`("project") `path?` `roots?` | `Session[]`(`time.updated` 降順) |
| GET | `/session/status` | query: `directory?` `workspace?` | `{"<sessionID>": SessionStatus}` |
| PATCH | `/session/{sessionID}` | `{title?, metadata?, permission?, time?{archived?}}`(全て任意・`additionalProperties:false`) | `Session` |
| DELETE | `/session/{sessionID}` | - | `200` + ボディ `true` / 存在しなければ `404 NotFoundError` |

`sessionID` のパスパラメータは spec 上 `pattern: ^ses`。

### SessionStatus (spec: `components.schemas.SessionStatus`、anyOf 3種)

```json
{"type": "idle"}
{"type": "busy"}
{"type": "retry", "attempt": 0, "message": "...", "next": 0,
 "action": {"reason","provider","title","message","label","link"?}}
```

- `idle` / `busy` の required は `type` のみ。
- `retry` の required は **`type` `attempt` `message` `next`**(`action` は任意、`action` 自身の
  required は `reason` `provider` `title` `message` `label`)。**フィクスチャはこの required を満たすこと。**

### `GET /session/status` の実測(2026-08-27)

- 何も走っていないとき: `{}`(空オブジェクト)
- 実行中セッションがあるとき: `{"ses_fc024073bffe20ZIBZ6z2Ei56t": {"type":"busy"}}`
- **idle のセッションはマップに現れない**。「キーが無い = idle」として扱ってよい
  (実測でそうなっており、`session.status` の `{"type":"idle"}` イベントは別途届く)。

### SSEイベント(追加分)

| type | properties | 実測(2026-08-27) |
|---|---|---|
| `session.status` | `{sessionID, status: SessionStatus}`(両方required) | `{"sessionID":"ses_…","status":{"type":"busy"}}` → 完了時 `{"type":"idle"}` |
| `session.created` | `{sessionID, info: Session}`(両方required) | POST /session で発火。`info` は Session 全体 |
| `session.updated` | `{sessionID, info: Session}`(両方required) | PATCH /session/{id} で発火。`info.title` が新タイトル |
| `session.deleted` | `{sessionID, info: Session}`(両方required) | DELETE /session/{id} で発火。`info` は**削除前**の Session |

実際に流れたフレーム(`GET /event` を開いたまま create → patch → delete した記録):

```json
{"id":"evt_03fdb8dc1001JsSRVG0lNg089b","type":"session.updated","properties":{
  "sessionID":"ses_fc0247634ffeGddA7Mw2hp2KCh",
  "info":{"id":"ses_fc0247634ffeGddA7Mw2hp2KCh","slug":"stellar-sailor",
          "projectID":"c1316…","directory":"E:\github\opencode-android","path":"",
          "title":"Q1-sse-probe-renamed","version":"1.18.21","cost":0,
          "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
          "time":{"created":1787777747403,"updated":1787777747403}}}}
```

### `time.updated` の単位 — **エポックミリ秒**(実測)

spec は `{type: integer, minimum: 0}` としか言わないので、実測で決めた。
2026-08-27 の測定で、実サーバーの最新セッションが `time.updated = 1787756365333`、
同時刻のホストの `DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()` が `1787777635176`。
**同じ桁数・同じ時刻帯**なのでミリ秒。秒だと解釈すると 56000 年後、
ミリ秒を秒として渡すと 1970 年になる(この取り違えは日付グループを全滅させる)。

`PATCH /session/{id}` で title を変えても **`time.updated` は変化しなかった**(実測。
create=updated=1787777747403 のまま `session.updated` が届いた)。
改名で一覧の並び順は動かない。

### `start` / `limit` / `search` の意味 — **実測でのみ決まる**

opencode 1.18.21 の実サーバー(セッション423件)に対する測定:

| クエリ | 返却件数 | 先頭3件 |
|---|---|---|
| (なし) | 100 | fc16ab, fc19ce, fc16cc |
| `limit=5` | 5 | fc16ab, fc19ce, fc16cc |
| `limit=200` | 200 | 同上 |
| `limit=100000` | **423**(全件) | 同上 |
| `limit=3&start=1` / `=2` / `=3` / `=10` / `=50` | 3 | **すべて** fc16ab, fc19ce, fc16cc |
| `start=400`(既定limit) | 100 | 同上 |
| `start=1787756351568` | **2** | fc16ab(updated=1787756365333), fc19ce(updated=1787756351568) |
| `start=1787756351568&limit=3` | 2 | 同上 |
| `search=probe&limit=5` | 5 | title に `probe` を含むもの |

読み取れること:

1. **`start` はオフセットではない。`time.updated` に対する下限フィルタ(エポックミリ秒、`>=` で境界含む)である。**
   `start=1`〜`start=50` が全部同じ先頭を返したのは「1〜50ミリ秒以降に更新されたもの」を
   全部返していたからで、**読み飛ばしは起きていない**。
   `start=1787756351568` がちょうど2件(updated がその値以上の2件)を返したのが決定的な証拠。
2. **`start` は「より新しい方向」へしか効かない。** 無限スクロールが要求する
   「より古い方を続けて読む」には使えない。
3. **R2 の「直近100件の窓」は `limit` の既定値が100だというだけ**で、`limit` を上げれば越えられる。
   423件のサーバーで `limit=100000` が423件全部を返した。
4. `search` は title の**部分一致・大文字小文字を無視**する
   (`search=CONTRACT-PROBE-RENAMED` が title `Q1-contract-probe-renamed` の1件にヒット)。

**したがってページングは `limit` を伸ばす方式で実装する。**
QUALITY_PLAN §5 Q1 スコープ5 の「スクロール末尾で `start` 続行」は、この実測と矛盾する
(`start` で続けると同じ先頭が返り続け、末尾へ到達できない)。実測を採った。
計画書側は 2026-08-27 に訂正済み。

**伸ばし方は倍々**(50 → 100 → 200 → …、上限2000)。この方式はページごとに**全件を
取り直す**ので、50刻みで伸ばすと n 件目まで到達するのに 50+100+150+… = O(n²) バイトを
転送する(1セッション約640バイト・423件で約1.4MB)。倍々なら合計は 2n に収まる。
上限に達したら「これ以上は追わない」ことを画面に出す。**`start` が使えない以上、
全件取り直しそのものは避けられない**ので、避けているのは増え方だけである。

### PATCH / DELETE の実測(2026-08-27、使い捨てセッションで往復)

```
POST   /session {"title":"Q1-contract-probe"}          -> 200 {"id":"ses_fc02518c…","title":"Q1-contract-probe",…}
PATCH  /session/ses_fc02518c… {"title":"…-renamed"}    -> 200 {"…","title":"Q1-contract-probe-renamed",…}
GET    /session?search=CONTRACT-PROBE-RENAMED&limit=5  -> 1件
DELETE /session/ses_fc02518c…                          -> 200  body: true
DELETE /session/ses_fc02518c…(2回目)                  -> 404  {"name":"NotFoundError","data":{"message":"Session not found: ses_fc02518c…"}}
```

削除の失敗は 404 で返る。楽観更新のロールバックはこのステータスで判定する。

## Q2で使用する分(2026-08-27 追記)

**採取方法**: 実機 `opencode serve` 1.18.21(ポート4097)を起動して `GET /doc` を取得し、
`docs/spec/opencode-1.18.21-openapi.json` と **SHA-256 が再び一致**することを確認した
(`C3A9F94AF0C3324D97B482B14C692E810CE7CCAC3136319BA46334DE972B4CF1`、2026-08-27 Q2着手時)。
したがってこの節の「spec由来」はすべて**実機の現在値**と同一である。
加えて、abort の挙動だけは spec に書かれていないので**使い捨てセッションで実測**した(後述)。

### ToolPart(spec: `components.schemas.ToolPart`)

```
required: id, sessionID, messageID, type("tool"), callID, tool, state
任意    : metadata
```

`state` は `ToolState` = 4バリアントの anyOf。**required がバリアントごとに違う**。
**任意プロパティまで含めて書く**(2026-08-27 訂正: 初版は任意側を落としており、
同じ差分の `Q2ContractParsingTest.toolRunningFrame()` が running に `title` を載せているのと
食い違っていた。契約文書がフィクスチャと食い違ったら、直すのは契約文書のほうである):

| status | required | 任意 |
|---|---|---|
| `pending` | `status` `input` `raw` | — |
| `running` | `status` `input` `time{start}` | `title` `metadata` |
| `completed` | `status` `input` `output` `title` `metadata` `time{start,end}` | `attachments`(`FilePart[]`) `time.compacted` |
| `error` | `status` `input` `error` `time{start,end}` | `metadata` |

- `input` は `type: object`。**中身の形はツールごとに違い、spec は何も決めていない。**
  したがってアプリ側は `JsonObject` のまま受けて表示直前に文字列化する。
  `String` と宣言すると `SerializationException` になり、**ツールを見せるための変更が
  ツールを見せなくする**(L3 の `jsonPrimitive` 欠陥と同じ形)。
- したがって `title` は **`running` でも `completed` でも来うる**。`error` に `output` と
  `title` は無い。`attachments` はアプリでは未使用(Q7 のファイル表示で使う可能性がある)。
- アプリの `ToolStateDto` は全項目を任意で受ける(1つの型で4バリアントを吸収する)。
- **`output` の長さに上限は無い**。実物 serve 4097 の実セッションでは `tool:"read"` の
  `output` がファイル全文だった。アプリは表示とコピーの両方で
  `TOOL_OUTPUT_SUMMARY_MAX`(1000文字)で切り、切ったことと元の長さを本文に書く。

### ReasoningPart(spec: `components.schemas.ReasoningPart`)

```
required: id, sessionID, messageID, type("reasoning"), text, time{start}
任意    : metadata, time.end
```

**`text` を持つ**。`type=="text"` のときだけ本文を拾う実装だと、
「思考」の折り畳みを開いても中身が空になる。

### SessionStatus の `retry.action`(Q1 申し送り minor-7 の回収)

```json
{"type": "retry", "attempt": 0, "message": "...", "next": 0,
 "action": {"reason": "...", "provider": "...", "title": "...",
            "message": "...", "label": "...", "link": "..."}}
```

- `retry` の required は `type` `attempt` `message` `next`。**`action` は任意。**
- `action` があるなら、その required は `reason` `provider` `title` `message` `label`。
  `link` だけ任意。**フィクスチャはこの required を満たすこと。**
- `SessionStatusDto` に `action` を足した(Q1 では持っておらず、`ignoreUnknownKeys` で
  黙って捨てていた)。retry バナーの本文は `action.title` を優先し、無ければ `message` を使う。

### abort が流すもの — **実測(2026-08-27、使い捨てセッション1件)**

`GET /event` を開いたまま、使い捨てセッションを作って `prompt_async` → `POST /abort` した記録
(相対時刻はSSE接続からの経過):

```
+ 1556ms SSE session.status {"type":"busy"}
+ 2033ms SSE session.error  {"name":"APIError","data":{"message":"Payment Required: …","statusCode":402,"isRetryable":false, …}}
+ 2033ms SSE session.status {"type":"idle"}
+ 2033ms SSE session.idle
+ 6592ms HTTP POST /session/{id}/abort -> 200 body=true
+ 6592ms SSE session.status {"type":"idle"}      <- abort に対して流れた
+ 6592ms SSE session.idle                        <- abort に対して流れた
+26609ms HTTP GET /session/status -> 当該セッションは**現れない**(= idle)
```

読み取れること:

1. **`POST /abort` は `session.status{idle}` と `session.idle` を流す。**
   既に idle のセッションに対しても流れた(上の記録は、モデルが 402 で即死した後の abort)。
   したがって**実サーバーでは abort 後に `session.idle` が来る**。
2. **`permission.replied` は流れない。** abort しても未応答の承認要求は解決されない。
   承認ダイアログを畳むのはクライアントの責務である。
3. `session.error` の `error` は**オブジェクト**(`{name, data:{message, statusCode, …}}`)で、
   L3 で確認した形と同じだった。文字列と決め打たないこと。

**`e2e-stub` は既定では abort に対して何も流さない**(シーケンスを世代番号で黙って止めるだけ)。
これは実サーバーと食い違っている。申し送り Q0-1「abort 後もUIが応答中のまま」は
**この食い違いを見ていた**もので、実サーバー相当の挙動は `STUB_ABORT_IDLE=1` で得られる
(既定オフ。既定を変えると P4ゲート②/Q0 の証跡が引用している `eventsSent` 不変が崩れるため)。

## Q3で使用する分(2026-08-27 追記)

**採取方法**: 実機 `opencode serve` 1.18.21(ポート4097、稼働中・セッション423件)から
`GET /doc` を取得し、`docs/spec/opencode-1.18.21-openapi.json` と **SHA-256 が3度目の一致**を
確認した(`C3A9F94AF0C3324D97B482B14C692E810CE7CCAC3136319BA46334DE972B4CF1`、2026-08-27 Q3着手時)。
加えて **todo は実物のレスポンスを実測**した(後述)。
`question.*` は**実物では発火させられなかった**(アカウントが 402 Payment Required を返し推論が回らない)。
**発火を観測していないものは「spec由来」と明記する**。

### エンドポイント

| メソッド | パス | リクエスト | レスポンス |
|---|---|---|---|
| GET | `/session/{sessionID}/todo` | query: `directory?` `workspace?` | `Todo[]` |
| GET | `/question` | query: `directory?` `workspace?` | `QuestionRequest[]`(**全セッション横断の未応答質問**) |
| POST | `/question/{requestID}/reply` | `{answers: string[][]}`(required: `answers`) | `200` + ボディ `true` / `404 QuestionNotFoundError` |
| POST | `/question/{requestID}/reject` | (ボディなし) | `200` + ボディ `true` / `404 QuestionNotFoundError` |

`requestID` のパスパラメータは spec 上 `pattern: ^que`。

**`GET /question` が Q3 の「切断中に変化した分」を埋める**(RUN_PLAN 決定2 と同じ問いへの答え)。
`question.asked` は SSE でしか来ないので、購読者ゼロの窓や再接続をまたぐと質問カードが出ない。
未応答の質問はサーバーが保持していて**この GET で取り直せる**ので、
一覧が `GET /session/status` でやったのと同じことを、質問は `GET /question`、todo は
`GET /session/{id}/todo` で行う。**入室時と再接続のたびに取り直す。**

### `Todo`(spec: `components.schemas.Todo`)

```
required: content, status, priority   （additionalProperties: false）
```

- `status` は **`type: string` であって enum ではない**。spec の description は
  `"Current status of the task: pending, in_progress, completed, cancelled"` と書いており、
  **`cancelled` を含む4値**である。QUALITY_PLAN §5 Q3 スコープ1 の「3値(pending/in_progress/completed)」は
  spec より狭い。**アプリは未知文字列でも落ちない形で受ける**(enum で decode すると
  `cancelled` 1件で todo リストごと消える。L3 `jsonPrimitive` と同じ形)。
- `priority` も `type: string`(description: `high, medium, low`)。**required なので欠かせない**が、
  アプリの表示には現在使っていない。

**実測(2026-08-27、実物 serve 4097 の実セッション)**: 423件を走査して非空の todo を持つ
セッションを 21件目までに3件見つけた。返ってきた形は spec どおり3キーちょうどだった:

```json
[{"content":"Confirm Theme.kt exists and read current content","status":"completed","priority":"high"},
 {"content":"Draft work specification JSON","status":"completed","priority":"high"}]
```

todo が無いセッションは `[]`(404 ではない)。`GET /question` は未応答が無ければ `[]`。

### `QuestionRequest` / `QuestionInfo` / `QuestionOption`(spec)

```
QuestionRequest required: id(^que), sessionID(^ses), questions        任意: tool{messageID, callID}
QuestionInfo    required: question, header, options                   任意: multiple(bool), custom(bool)
QuestionOption  required: label, description                          ← description も **required**
QuestionAnswer  = string[]        （answers は QuestionAnswer[] なので string[][]）
```

- `QuestionOption.description` が required なのは見落としやすい。**フィクスチャは description を持つこと。**
- `multiple` / `custom` は任意なので**欠けたら false 扱い**。
- `tool` は任意。`QuestionTool` の required は `messageID` `callID`。

### `answers` の形 — 根拠は spec の3か所が一致していること

計画書は「質問ごとの選択ラベル配列の配列」と書いていた。**そのとおりだった**が、
推測ではなく次の3点が一致することで確定させた(実物での往復は 402 のため不可):

1. `POST /question/{requestID}/reply` の requestBody:
   `{answers: QuestionAnswer[]}`、required `["answers"]`、description は
   **"User answers in order of questions (each answer is an array of selected labels)"**
2. `QuestionAnswer` = `{"type":"array","items":{"type":"string"}}` → **`string[]`**
3. v2 の `QuestionV2Reply` も同一形(同じ description)

したがって `answers[i]` は **i番目の質問への回答**であり、中身は**選択したラベルの配列**。
読み取れる規則:

- **質問と同じ順序・同じ個数**で並べる(「in order of questions」)。
- 中身は `label` であって index でも `description` でもない。
- 単一選択は要素1、`multiple: true` は複数要素。
- `custom: true` の自由入力は**同じ配列に混ぜる**しか場所が無い(spec に別フィールドが無い)。
  アプリは自由入力を選択ラベルの**後ろ**に1要素として足す。
  **これは spec が明示していない唯一の点**であり、`answers` の型が `string[]` である以上
  他に置き場が無いという消去法の結論である。**実物で確認できていない。**

### SSEイベント(追加分)

`GET /event` が流すのは `Event` union(89メンバー)であり、`question.*` / `todo.updated` は
**`properties` を持つ `Event*` 系**が入っている(`EventTodoUpdated` / `EventQuestionAsked` /
`EventQuestionReplied` / `EventQuestionRejected`)。同じ名前で `data` を持つ
`TodoUpdated` / `QuestionAsked` … も schemas には居るが、そちらは `Event` union の外(durable 系)。
**アプリが読むのは `properties` のほう**(Q1 で実測したフレームと同じ)。

| type | properties(required) | 由来 |
|---|---|---|
| `todo.updated` | `{sessionID, todos: Todo[]}` **両方required** | spec |
| `question.asked` | `{id(^que), sessionID, questions: QuestionInfo[]}` + 任意 `tool` | spec |
| `question.replied` | `{sessionID, requestID(^que), answers: string[][]}` **3つともrequired** | spec |
| `question.rejected` | `{sessionID, requestID(^que)}` **両方required** | spec |

**`asked` は `id`、`replied`/`rejected` は `requestID`。キー名が違う。**
これは P4 で2度踏んだ形そのものである(`PermissionRepliedEvent` に `permission` を必須宣言して、
実際に届く `{id, sessionID, response}` がパースできず**応答後もダイアログが閉じなかった**)。
`asked` の `id` で作ったカードを `replied` の `requestID` で閉じるので、
**片方を書き間違えるとカードが永久に閉じない**。アプリ側の型は
`QuestionAskedEvent.id` / `QuestionResolvedEvent.requestID` と別名で持ち、
どちらも**必須にしない**(欠けてもイベントごと捨てない。欠けたときは
「このセッションの未応答の質問」を対象にフォールバックする)。

### v2 経路も存在する(採用しない)

`question.v2.asked` / `question.v2.replied` / `question.v2.rejected` と
`POST /api/session/{sessionID}/question/{requestID}/reply`(204、body は `QuestionV2Reply`)、
同 `/reject` が spec にある。**shape は v1 と完全に同一**(`QuestionV2Info` ≡ `QuestionInfo`)。
Q3 は permission と同じ理由で **v1(legacy)経路を採る**が、
**どちらの type 名で届くかは実物で観測できていない**ため、
アプリの SSE パーサは `question.asked` と `question.v2.asked` の**両方を同じイベントとして受ける**
(replied/rejected も同様)。shape が同一なので分岐は要らず、外れたときの損失だけが消える。

### 実物で確認できなかったこと(正直な記録)

- `question.asked` / `question.replied` / `question.rejected` / `todo.updated` の**発火**。
  実物 serve のアカウントが 402 Payment Required を返し、推論が1トークンも走らないため
  ツール(`question` / `todowrite`)が呼ばれる状況を作れない。QUALITY_PLAN §5 Q3 は
  この項目を「ブロックしない」としている。
- `POST /question/{requestID}/reply` の**成功応答**。未応答の質問が存在しないので
  404 しか引けない(404 の形は spec の `QuestionNotFoundError`: `_tag` `requestID` `message`)。
- したがって **`answers` が実サーバーに受理される形かどうかは未確認**である。
  spec の required と description に一致させたことが根拠のすべてである。

### `GET /question` は「未応答かどうか」の権威である(2026-08-27、Q3レビュー差し戻しで追記)

spec の summary は "Get all pending question requests across all sessions"、戻りは `QuestionRequest[]`。
ここから2つのことが**契約として**読める。どちらもアプリの状態設計に直結する。

1. **未応答の質問は同時に複数あり得る。** 配列であることがその宣言で、`minItems` も
   「1件だけ」という制約も無い。サブエージェントや2つ目のツール呼び出しで実際に起きうる。
   クライアントが1件しか保持しないと、2件目が届いた時点で1件目は画面からもログからも消え、
   **サーバーはそれを永久に待ち続ける**(応答しない限りセッションは進まない)。
2. **このエンドポイントに載っている = まだ答えられる。** 逆に、載っていない = もう答えられない。
   したがってクライアント側の「もう答えられない」という判断
   (`session.error` / `session.deleted` / abort の後始末など)は**暫定でしかなく**、
   次にこの GET を引いた時点で**サーバーの答えが上書きする**。
   片方向にしか動かない(失効させるだけで復帰させない)実装は、
   `session.error` を1回受けただけの質問を**二度と答えられなくする**。
   `session.error` は実物 serve が 402/429 で実際に流すことを Q2 が実測しており、稀な経路ではない。

**未確認**: 上記2点は spec の型と summary から読んだものであり、**実物で複数 pending を発生させた
観測はできていない**(402 でモデルが回らないため質問ツール自体が呼ばれない)。
`e2e-stub` では `POST /__stub/question` を2回叩いて2件同時 pending を作れる。

### `QuestionInfo.options` に `minItems` は無い

`options` は required だが**空配列を禁じていない**。`custom` も無ければ、その質問には
**入力手段が1つも無い**。アプリは「回答不能」と画面に書いて拒否だけを残す
(回答ボタンを無効のまま置くと、押せない理由がどこにも出ない)。

## Q4で使用する分(2026-08-27 追記)

**採取方法**: 実機 `opencode serve` 1.18.21(ポート4097、稼働中)から `GET /doc` を取得し、
`docs/spec/opencode-1.18.21-openapi.json` と **SHA-256 が4度目の一致**を確認した
(`C3A9F94AF0C3324D97B482B14C692E810CE7CCAC3136319BA46334DE972B4CF1`、2026-08-27 Q4着手時。
証跡 `e2e-artifacts/Q4/probe/doc-sha256.txt`)。
**Q4 は spec だけで書いていない** —— 下記の「実測」印のものは実物 serve に対して往復して確かめた。

**この serve は認証を要求する。** P5 の記録は「認証は不要と判明」だが、**現在の 4097 は
無認証だと 401 を返す**(`www-authenticate: Basic realm="Secure Area"`)。
`OPENCODE_SERVER_PASSWORD`(scope=Process/User、指紋 `B97B270F`)で Basic 認証すると 200。
契約の「認証」節(HTTP Basic / ユーザー名 `opencode`)のほうが正しく、P5 の但し書きは
**その時の起動条件の記録**であって契約ではない。

#### 認証の挙動は起動条件で決まる(2026-08-30、両条件を並べて実測)

P5(200)と Q4 以降(401)の食い違いは**挙動の変化ではない**。同じ 1.18.21 のバイナリを
2条件で同時に起こし、同一ホストから並べて叩いて確定させた。

| 起動条件 | `GET /global/health` 無認証 | 誤パスワード | 正しいパスワード |
|---|---|---|---|
| `OPENCODE_SERVER_PASSWORD` **設定あり**(`scripts/serve.ps1 -Port 4097`) | **401** `www-authenticate: Basic realm="Secure Area"` | **401** 同上 | **200** `{"healthy":true,"version":"1.18.21"}` |
| `OPENCODE_SERVER_PASSWORD` **3スコープとも未設定**(P5 の条件、port 4098) | **200** | **200**(ヘッダは無視される) | — |

`GET /provider` も同じ(未設定 serve では**無認証でプロバイダのAPIキーごと 200 で出る**)。
未設定側の起動ログには `Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured.` が出る。

**したがって 1.18.21 で無認証アクセスが厳しくなった事実は無い。**
`scripts/serve.ps1` はパスワードが無ければ起動を拒否する(exit 2)ので、
**このリポジトリの手順で起こした serve は必ず 401 側**になる。「serve.ps1 で起こしたら
無認証で 401 だった」は AGENTS.md の記述への反証ではなく、条件が違うだけである。

アプリ側の設計判断(パスワード入力欄、401 を「パスワードが違います」と出す `describe()`)は
**401 側の条件に合わせてある**ので、この切り分けによる変更は無い。

### エンドポイント

| メソッド | パス | リクエスト | レスポンス |
|---|---|---|---|
| GET | `/provider` | query: `directory?` `workspace?` | `{all: Provider[], default: {providerID: modelID}, connected: string[]}`(3つとも**required**) |
| GET | `/agent` | query: `directory?` `workspace?` | `Agent[]` |
| POST | `/session` | body に `agent?: string` / `model?: ModelRef` を**追加できる**(実測) | `Session`(`agent` と `model` が反映される) |
| POST | `/api/session/{sessionID}/model` | `{model: ModelRef}`(required `model`) | **204**(実測) / 400 / 401 / 404 `SessionNotFoundError` / **500 `UnknownError`**(下記「セッションの `directory`」参照) |
| POST | `/api/session/{sessionID}/agent` | `{agent: string}`(required `agent`) | **204**(実測)。**500 の条件も model と同一** |
| POST | `/session/{sessionID}/prompt_async` | body に `model?: {providerID, modelID}` / `agent?` / `variant?` を**追加できる**(実測) | 204 |

`sessionID` のパスパラメータは spec 上 `pattern: ^ses`。

### **モデル参照の形が3種類ある。キー名が違う。**

これは P4 の `permission.asked{id}` / `permission.replied{requestID}` と同じ形の罠で、
**1か所書き間違えるとモデル指定が黙って無視される**(400 にすらならない。後述の #4)。

| 使う場所 | 形 | required |
|---|---|---|
| `ModelRef` —— `POST /session` の `model`、`Session.model`、`POST /api/session/{id}/model` の `model`、`session.next.model.switched` の `model` | `{id, providerID, variant?}` | `id` `providerID` |
| `prompt_async` の `model`、`UserMessage.model` | **`{providerID, modelID}`** | 両方 |
| `Agent.model` | **`{modelID, providerID}`** | 両方 |
| `AssistantMessage` | **フラットな `providerID` / `modelID` の2フィールド**(オブジェクトではない) | — |

`ModelRef` だけが `id`、他はすべて `modelID` である。**`variant` を持てるのは `ModelRef` だけ。**

### `Provider` / `Model` / `Agent`(spec)

```
Provider required: id, name, source(enum env|config|custom|api), env(string[]), options, models
          任意:   key
          models: { "<modelID>": Model }
Model    required: id, providerID, api, name, capabilities, cost, limit, status, options, headers, release_date
          任意:   family, variants, ...
Agent    required: name, mode(enum subagent|primary|all), permission, options
          任意:   description, native, hidden, topP, temperature, color, model{modelID,providerID}, variant, prompt, steps
```

### `Model.cost` — **正本はピン留めした spec**(2026-08-30 追記 / 2026-09-01 訂正、Q10)

> **訂正(2026-09-01、Q10 レビュー blocker-1)。**
> この節はもともと「serve が `models.dev` の `Cost` を**そのままプロキシする**」と書き、
> `models.dev` 側の生形（`cache_read` / `cache_write` / `context_over_200k`）を契約として載せていた。
> **これは誤りである。** serve は models.dev のデータを**変換した形**で返す。ピン留め済みの
> `docs/spec/opencode-1.18.21-openapi.json` の `components.schemas.Model.properties.cost` は
> `cache` を**ネストしたオブジェクト**として持ち、200K 超は `experimentalOver200K` という
> 別のキー名である（`additionalProperties: false`、required は `input` `output` `cache`）。
> この誤記のまま実装した `CostDto` は `@SerialName("cache_read")` 等で宣言していたため、
> **実応答のどのキーにも当たらず黙って全部 null** になっていた。

正しい形（**ピン留めした spec からの引用**。`GET /provider` の `Provider.models.<id>.cost`）:

```ts
Cost = { input: number, output: number,
         cache: { read: number, write: number },            // required・ネスト・additionalProperties:false
         tiers?: { input, output, cache:{read,write},
                   tier: { type: "context", size: number } }[],
         experimentalOver200K?: { input, output, cache:{read,write} } }
```

値の出どころは `models.dev`（`https://models.dev/api.json` /
`https://models.opencode.ai/api.json`）だが、**キー名は serve が変換したもの**であり
models.dev の生形ではない。Android は serve 経由の値だけを読む
（直接 `models.dev` を叩かない — TTL 5min + snapshot フォールバックの整合は serve が持つ）。

* 価格は **per 1M tokens の USD**（`zen.mdx` "per 1M tokens"）。`input==0 && output==0` は `Free`
  （**cache は Free 判定に使わない**）。
* `cache.read` / `cache.write` が 0 なら表示しない。実測（下記）では**全モデルが `0/0`** だった。
* `tiers[]` は段階料金、`experimentalOver200K` は 200K 超の単発。Android は
  `tiers` を優先し、無ければ `experimentalOver200K` を使う（`CostDto`）。
  **閾値の見出しは `tiers[].tier.size` の実数から作る**（`>128K` / `>200K` / `>1M`）。
  `tier` が無い `experimentalOver200K` は `>200K`（フィールド名がそう言っている）。
* **`tier.size` は `number` であって `integer` ではない**（spec の原文がそう書いてある）。
  Android 側も `Double` で受ける（`CostTierRefDto.size`）。1周目は「トークン数だから整数」と
  `Long` で宣言していたが、E2E ゲートが実機で対照実験して確認したとおり、
  **`200000.5` が1件あるだけで `GET /provider` の応答全体（200件超）のデコードが失敗し、
  モデル一覧が丸ごと `catalog-failed` になる**（`200000`に戻すと復帰）。表示のときだけ
  四捨五入する（`tierThresholdLabel`。`200000.5` → `>200001`）。
* **デコード失敗は1モデルに閉じ込める**（`TolerantModelMapSerializer`）。`Provider.models` を
  `{modelID: JsonElement}` で受け、**各モデルを独立にデコード**する。失敗したモデルは
  まず `cost` / `capabilities` の部分木を落として読み直し（`cost=null`＝未取得、
  `capabilities=null`＝分からない、で**一覧には残る**）、それでも読めなければ**その1件だけ**捨てる。
  他のモデルは 7,337 件すべて残る。
  1周目の手当ては `cost` 専用だったが、**3件目の欠陥は `capabilities` に来た**
  （E2E ゲートが実機で確認）。壊れるフィールドは毎回違い、壊れ方だけが同じなので、
  フィールド単位ではなく**増幅器そのもの**を塞ぐ。
  メモリ: materialize されるのは1プロバイダの `models` サブツリーだけで、プロバイダごとに
  使い捨てられる（応答全体が同時にツリーとして生きることはない）。1モデルの JSON は
  実測 **566〜596 バイト**（`e2e-artifacts/Q10Q11/provider.json` の5件）、
  5.4 MiB / 7,338 モデルからの平均でも **約 770 バイト**である。
* 表示は `"$3.00 / $15.00 per 1M · cached $0.30 · >200K $6.00 / $22.50"` の1行（`formatCost()`）。
  `cost==null` は非表示（「無い」を「無料」に化けさせない）。サブセント価格は
  `%.2f` だと `$0.00` になって無料に見えるので、**0 でないのに2桁で潰れる値だけ4桁**にする。
* `experimental.modes[].cost` は別口（通常の `cost` とは別オブジェクト）。今回は対象外（`ignoreUnknownKeys` で破棄）。

**採取証跡**: `e2e-artifacts/Q10Q11/provider.json`(E2E スタブ serve の `GET /provider` 応答)。
cost を持つ全5モデルが `{"input":..,"output":..,"cache":{"read":0,"write":0}}` の形で、
`cache_read` / `context_over_200k` は**1件も出現しなかった**。
`tiers` / `experimentalOver200K` を含む実応答はこのリポジトリ内に**採取できていない**
（スタブが返さない）。それらの形はピン留め済み spec を根拠にしている。
検出器は `Q10CostTest`(アプリの実経路 `contractJson` でデコードする)。

**`Agent.hidden` は任意**なので、欠けたら false。**`description` も任意**である。
**`Model.name` は required** なので表示ラベルに使ってよいが、`Provider.models` の**キー**が
`modelID` であり `Model.id` と一致する(実測: `mistral-medium-2508`)。

### `GET /provider` の実測(2026-08-27、実物 serve 4097)

証跡 `e2e-artifacts/Q4/probe/provider-summary.json`。

```
HTTP 200 / 応答 5,669,524 バイト(5.4 MiB)/ 0.39 秒(ホスト)
all: 203 プロバイダ / モデル総数 7,338
connected: ["nvidia","deepseek","google","openrouter","opencode","mistral","cerebras","groq"] (8件)
connected 分のモデル数合計: 600
  nvidia 100 / deepseek 3 / google 38 / openrouter 355 / opencode 61 / mistral 26 / cerebras 2 / groq 15
default: 203件すべてのプロバイダについて既定modelIDを持つ(connected だけではない)
```

**これは Q4 の設計を決める測定値である。**

- **`all` をそのまま画面に出してはならない。** 203プロバイダ・7,338モデルは選べる量ではない。
  計画書が「`connected` を使う」と指定しているのはこのためで、**8プロバイダ・600モデル**に落ちる。
- **5.4 MiB を毎回引かない。** 起動時には引かず、モデル選択を開いたときに1回だけ引いて
  プロセス内にキャッシュする。DTO は必要なフィールドだけ宣言する
  (`ignoreUnknownKeys` があるので `headers` 等は materialize されない。`capabilities` / `cost` は表示に使うので宣言する)。
- `connected` に載っているIDは実測では**すべて `all` にも居た**が、契約上そう書かれてはいない。
  実装は「`all` に無い `connected` は無視」とだけ決める。

**v2 経路は等価ではない(採用しない)**: `GET /api/provider` は 818 バイトで4件
(`google` `openrouter` `opencode` `groq`)しか返さず、**`connected` の8件と一致しない**。
`GET /api/model` は 262 KB / 438件。どちらも `/provider` の部分集合であり、
**`connected` の権威は `GET /provider`** である。

### **`Provider.key` は APIキーそのもの。アプリは宣言しない**(2026-08-30 追記)

spec の `Provider` は任意フィールド `key: string` を持つ(上の required 表の「任意: key」)。
**同じ `Provider` スキーマを `GET /provider` と `GET /config/providers` の両方が返す。**

**実測済み(2026-08-30、E2Eゲート)。** 接続済みプロバイダ8社(`auth.json` に実APIキーが
設定された環境)に対し実物 serve 1.18.21 の `GET /provider`(**アプリが実際に叩く方**)を
叩いたところ、**8/8 全社の `key` フィールドに実APIキーがバイト単位でそのまま平文で載っていた**
(値は報告に転記していない。長さのみ確認: 32〜73文字)。`GET /config/providers` でも同じ
8/8 が確認できた。**「事前調査での申告」段階だった記述は、この実測で確定に格上げされた。**
`options` は全社に存在するが、この測定環境では `HTTP-Referer` 等のブランディング系ヘッダのみで
秘密は入っていなかった(器としては引き続き `{"apiKey": …}` を運びうる自由形オブジェクト)。

**同じ器が1段下にもある。** spec の `Model`(= `Provider.models.<id>`)は required に
`options`(自由形オブジェクト)と `headers`(string map)を持つ。`headers` はカスタム
プロバイダの `Authorization` を、`options` は `apiKey` を運びうる —— つまり
**`Provider.key` / `Provider.options` と同種の秘密の器が、同じ `/provider` 応答の
`Provider.models.<id>` に存在する**。**実測(2026-08-30)**: 上記の8社・601モデル全件で
この器自体は存在したが、この環境では中身は全モデルで空だった(器はあるが実データでは
発火しなかった。ユニットテストのフィクスチャで固定しているのはそのため)。

サーバーが平文で返すこと自体はアプリの制御外である。**アプリ側の契約はこう決める**:

- **`ProviderDto` は `key` も `options` も `env` も宣言しない**(宣言は `id` `name` `source` `models` の4つだけ)。
  同様に **`ProviderModelDto` は `options` も `headers` も宣言しない**
  (宣言は `id` `providerID` `name` `family` `status` `capabilities` の6つだけ)。
  `contractJson` の `ignoreUnknownKeys = true` が残りを黙って捨てるので、
  **キーの値はオブジェクトグラフに一度も materialize されない** ——
  data class の `toString()` 経由でログ・例外・クラッシュレポートに載る経路が最初から無い。
- **失敗経路にもボディを載せない。** `OpenCodeApi.call` は `SerializationException` を
  `catch (_: …)` で捨てて固定文言に置き換え、HTTP 異常はステータスコードだけを運ぶ
  (`ApiError.Http`)。kotlinx.serialization の例外メッセージは**ボディの断片を引用する**ため、
  ここを「親切なエラー表示」に変えると秘密が漏れる。
  **検出器が押さえているのは `OpenCodeApi.call`(JSON 応答の共通経路 = `/provider` が通る方)だけ**である。
  本体には同じ潰し方をする箇所がもう1つ(`readFileContent` 内で `decodeFileContent` を囲む
  `catch (_: SerializationException)`)あるが、**プロバイダ応答はそこを通らない**ので対象外にしてある。
- **OkHttp の logging-interceptor を依存に置かない**(`app/build.gradle.kts` から削除済み)。
  Basic認証ヘッダと上記のキーを logcat に流す経路のうち、**コードから最も容易に開くものがこれ**であり、
  「付けない」を注釈ではなく**クラスパスに存在しないこと**で守る。
  **これで塞がるのは「唯一の経路」ではない**: デバッグビルドでは Android Studio の
  Network Inspector がコードとは無関係に応答全文を見られるし、将来 `Log.d` 等が
  足されれば経路は開く。塞いだのは**コードから容易に開く経路の1つ**である。
- **永続化しない。** DataStore に書くのは `base_url` / `password`(接続設定)と
  `color_mode` / `haptics_enabled` だけで、プロバイダ情報は一切保存しない。

これらは「書かなかったこと」で成立する防御なので、**変異で簡単に壊れる**。
検出器は `app/src/test/java/dev/opencode/android/ProviderSecretTest.kt`(11本)。
陽性コントロールを取ってある(実測 2026-08-30):

| 変異 | 落ちた検出器 |
|---|---|
| `ProviderDto` に `val key: String? = null` を1行足す | 3本 |
| `catch (_: SerializationException)` → `e.message` を返す | 1本 |
| `ProviderModelDto` に `options: JsonObject?` と `headers: Map<String, String>?` を足す | 3本 |

**この最後の行が当初は 0本だった**(モデル階層に検出器が1本も無く、変異が全テストを素通りした)。
`Provider` だけを見て `Provider.models.<id>` を見ていなかったのが穴の形である。

**測っていないこと**: 実物 serve の `GET /provider`(アプリが叩く方)の応答に
実際に `key` が載るかは、この作業では測れていない(サーバー不在)。
`e2e-artifacts/Q4/probe/provider-summary.json` は採取時に抽出したフィールドだけの要約で、
`key` の有無の証拠にならない。**spec は両エンドポイントに同じ `Provider` を割り当てている**ので、
アプリ側は「載っている」前提で扱う。

### `GET /agent` の実測(2026-08-27)

証跡 `e2e-artifacts/Q4/probe/agent.json`(80,656 バイト、17件)。

```
primary  : build, compaction*, implementer, orchestrator, plan, summary*, title*, wiki, wiki-ingest   (*=hidden:true)
subagent : explore, explorer, general, reviewer, runner, tester, verifier, worker
mode:"all" のエージェントは1件も無かった
```

`orchestrator` だけが `model` を持ち、その形は **`{"modelID":"nemotron-3.5-lightning-free","providerID":"opencode"}`**
(= `modelID` キー。`ModelRef` ではない)。他16件は `model` 無し。

**新規セッションで選べるのは `mode` が `primary` または `all` かつ `hidden != true` のもの**
(= 実測の 6件: build / implementer / orchestrator / plan / wiki / wiki-ingest)。
`subagent` はエージェントが内部で呼ぶもので、セッションの主エージェントにはならない。

### 実機検証タスクの結果(**計画書 §5 Q4 が実装前に要求した3項目**)

使い捨てセッション `ses_fbebc2a78ffeMMjWyj0mBPzBKu`(title `Q4-model-probe`)1件で往復した。
既存423件には触っていない。

#### 1. 既存セッションのモデルは作成時に固定か → **固定ではない。切り替えられる。**

P5 の記録「セッションのモデルは作成時に固定される」は、**`opencode.json` の設定を変えても
既存セッションに効かない**という観測であって、**専用エンドポイントを試した記録ではない**。
両立する。

#### 2. `POST /api/session/{sessionID}/model` は実物で機能するか → **する(204 + 反映を実測)**

```
POST /session {"title":"Q4-model-probe","agent":"plan","model":{"id":"mistral-medium-latest","providerID":"mistral"}}
  -> 200 {"agent":"plan","model":{"id":"mistral-medium-latest","providerID":"mistral"}, ...}   ← 作成時指定が反映される

POST /api/session/{id}/model {"model":{"id":"mistral-small-latest","providerID":"mistral"}}
  -> 204
GET  /session/{id}
  -> {"model":{"id":"mistral-small-latest","providerID":"mistral"}, "time":{"updated":+12s}}   ← 反映され time.updated も動く

POST /api/session/{id}/model {"model":{"id":"mistral-medium-latest","providerID":"mistral","variant":"thinking"}}
  -> 204、GET は variant:"thinking" まで反映
POST /api/session/{id}/agent {"agent":"build"}
  -> 204、GET は agent:"build"
```

**認証は必要である。** spec は当該パスに `"security": []` と書いているが、
無認証で叩くと `401 {"_tag":"UnauthorizedError","message":"Authentication required"}` が返った。
**spec の security は実物と食い違っている。**

#### 3. `prompt_async` の `model` 指定は per-prompt で効くか → **効くが、per-prompt ではない**

```
セッションの model = mistral-medium-latest の状態で
POST /session/{id}/prompt_async {"model":{"providerID":"mistral","modelID":"mistral-small-latest"},
                                 "agent":"build","parts":[{"type":"text","text":"say PONG"}]}
  -> 204
GET /session/{id}/message の AssistantMessage
  -> providerID="mistral"  modelID="mistral-small-latest"   ← 指定どおりのモデルで実行された
GET /session/{id}
  -> model = {"id":"mistral-small-latest","providerID":"mistral","variant":"default"}
     agent = "build"
```

**1往復ぶんの上書きではなく、セッションのモデルが恒久的に書き換わった。** `variant` も
`"default"` へ正規化された。`session.updated` も流れ、その `info.model` は新しい値だった。

したがって計画書の分岐「per-prompt model が効く → 送信時の上書きは行わず**表示のみ**に使う
(誤送信防止)」は、**理由がより強い形で成立する**: 送信時に model を載せると、
ユーザーが「今回だけ」のつもりでもセッションの既定が変わる。
**アプリは `prompt_async` に `model` も `agent` も載せない。**

#### 4. **サーバーはモデル名を検証しない**(陰性側の実測。計画書に無いが重要)

```
POST /api/session/{id}/model {"model":{"id":"no-such-model-xyz","providerID":"mistral"}}      -> 204
POST /api/session/{id}/model {"model":{"id":"x","providerID":"no-such-provider"}}             -> 204
POST /api/session/{id}/agent {"agent":"no-such-agent"}                                        -> 204
GET  /session/{id} -> agent="no-such-agent", model={"id":"x","providerID":"no-such-provider"}
POST /api/session/ses_000000000000000000000000/model                                          -> 404 SessionNotFoundError
```

**存在しないモデル/エージェントが 204 で受理され、そのまま保存される。**
失敗は次の推論まで表面化しない。**クライアント側が `GET /provider` / `GET /agent` から
得た候補以外を送らないことが唯一の防波堤**であり、自由入力の口を作ってはならない。
404 になるのはセッションが無いときだけ。

### SSEイベント(追加分)

| type | properties(spec required) | 実測 |
|---|---|---|
| `session.next.model.switched` | `{timestamp, sessionID, messageID, model: ModelRef}` | **発火を実測**(下記フレーム) |
| `session.next.agent.switched` | `{timestamp, sessionID, messageID, agent: string}` | **発火を実測** |
| `session.next.context.updated` | `{timestamp, sessionID, messageID, text}` | 未使用 |

```
data: {"id":"evt_...","type":"session.next.model.switched","properties":{
  "sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu","messageID":"msg_...",
  "timestamp":"2026-08-27T03:30:48.615Z",
  "model":{"id":"mistral-medium-latest","providerID":"mistral","variant":"thinking"}}}

data: {"id":"evt_...","type":"session.next.agent.switched","properties":{
  "sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu","messageID":"msg_...",
  "timestamp":"2026-08-27T03:30:10.664Z","agent":"build"}}
```

**`session.updated` は切替では流れない**(実測: 切替2回で `session.next.model.switched` が2件、
`session.updated` は0件)。一方 `prompt_async` では `session.updated` が流れ、その `info.model` は
新しい値だった。したがって **一覧は切替を知らされない** —— チャットで切り替えても、
戻った一覧の表示は次の `session.updated` まで古いままである。

**「サーバーが状態の権威」(RUN_PLAN 決定2)を Q4 も守る**: チャット入室時と**再接続のたびに**
`GET /session/{id}` を引き直してモデル/エージェントを取り直す。イベント列だけを信じない。
Q1 が `GET /session/status`、Q3 が `GET /question` / `GET /session/{id}/todo` でやったことの、
Q4 における対応物がこれである。

### `GET /session/{sessionID}`(単体取得。Q4 で初めて使う)

`GET /session/{sessionID}` -> `Session`(実測: 200)。`Session` の `agent` / `model` は
**両方とも任意**(required は `id` `slug` `projectID` `directory` `title` `version` `time`)。
未設定のセッションは両方欠ける —— **欠けている = 「サーバー既定に従う」**であって、
「モデルが無い」ではない。表示は「既定」と書く。

### `AssistantMessage` のメタ(実測。§5 Q4 スコープ4 の材料)

`GET /session/{id}/message` の `info`(role=assistant)に**実際に載っていたキー**:

```
agent, cost, error, id, mode, modelID, parentID, path, providerID, role, sessionID, time, tokens
providerID="mistral"  modelID="mistral-small-latest"  agent="build"  mode="build"
cost=0  tokens={"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}
```

`UserMessage` 側は `{id, sessionID, role, time, summary, agent, model:{providerID, modelID}}`。
**assistant はフラット、user はネスト**である(前述の3形の話)。

`error` の形(実測、402):

```json
{"name":"APIError","data":{"message":"Payment Required: {\"detail\":\"...\"}",
 "statusCode":402,"isRetryable":false,"responseHeaders":{},"responseBody":"...",
 "metadata":{"url":"https://api.mistral.ai/v1/chat/completions"}}}
```

**`isRetryable: false` と `statusCode` がある。** これが R3(「そのモデルが落ちている」)の
一次証拠であり、`session.error` の `properties.error` にも**同じオブジェクトがそのまま載る**
(実測。Q2 の `errorMessageOf` が読んでいる `data.message` はこの中身)。
`isRetryable` が false、または `statusCode` が 401/402/403/404 のときは再試行しても同じ結果になるので、
**「再試行」ではなく「モデルを変更」を出す**根拠に使える。

### **402 で測れなかったこと(正直な記録)**

実物 serve のアカウントは推論時に **402 Payment Required** を返す(mistral の
サブスクリプション期限切れ)。Q3 に続き Q4 でも、**推論の成功を要する項目は測れていない**。

1. **切替後の1往復が成功すること**(計画書ゲート「切替可能な経路を採用した場合」)。
   切替そのもの(204 + `GET /session` 反映 + SSE)は測れたが、**新しいモデルで応答が返るところ**は
   1トークンも走らないので見ていない。
2. **レート制限中モデルからの切替成功シナリオ**。計画書が「ブロックしない」としている項目。
3. **`GET /provider` の `connected` が「実際に呼べる」ことを意味するか。** 8件が connected と
   報告されたが、実際に推論が通ったプロバイダは0件である。`connected` は
   「資格情報がある」であって「今使える」ではない可能性が残る。**これは R3 の中心にある問い**なので、
   アプリは `connected` を「候補の絞り込み」にだけ使い、「使える保証」として表示しない。
4. **`variant` の意味**。`variant:"thinking"` は 204 で受理され `GET` にも反映されたが、
   `prompt_async` 経由では `"default"` に正規化された。**どちらが正か、どう効くかは未確認**。
   アプリは variant を**表示のみ**に使い、選ばせない。
5. **存在しないモデルを設定したセッションが、次の推論でどう失敗するか。** 204 で受理されることは
   測ったが、その後の症状(400 なのか `session.error` なのか)は見ていない。

**なお計画書ゲート「モデル指定付きでセッション作成 → 1往復し、AssistantMessage の
`providerID`/`modelID` が指定と一致することをサーバー側で確認」は、402 でも測れた。**
402 は推論に到達してから返るので、**AssistantMessage は生成され `providerID`/`modelID` が載る**。
一致は #3 に引用したとおり確認済みである。**「往復が成功する」ことだけが測れていない。**

### `providers.default` は「おすすめ」ではない(2026-08-27、Q4レビュー major-2 の実測)

1周目の実装は `providers.default` に一致するモデルを各プロバイダの**先頭に固定**し、
**「既定」バッジ**を付けていた。レビューの差し戻しを受けて実データを数えた結果、
**その並べ替えは誤誘導だった**。

`GET /provider` の `connected` 8社について、`default` が指すモデルの実体:

| provider | `default` | 実体(`name`) | `toolcall` | `input.text` |
|---|---|---|---|---|
| nvidia | `z-ai/glm-5.2` | GLM-5.2 | true | true |
| deepseek | `deepseek-v4-pro` | DeepSeek V4 Pro | true | true |
| **google** | `gemini-3-pro-image-preview` | **Nano Banana Pro(画像生成)** | **false** | true |
| **openrouter** | `google/gemini-3-pro-image-preview` | 同上 | **false** | true |
| opencode | `big-pickle` | Big Pickle | true | true |
| **mistral** | `voxtral-small-latest` | **Voxtral Small(音声)** | true | true |
| cerebras | `gpt-oss-120b` | GPT OSS 120B | true | true |
| **groq** | `whisper-large-v3-turbo` | **Whisper Large V3 Turbo(音声認識)** | **false** | **false** |

**接続済み8社のうち3社で `default` は `toolcall:false`** である。opencode はツール実行が本体なので、
これらを選んだセッションは実用にならない。しかも**サーバーはモデルを検証せず 204 で受理する**
(上記 実測 #4)ので、選んだ時点では成功に見え、次の推論まで失敗が出ない。

つまり「モデルが落ちたので替えたい」ユーザー(= R3 の当事者)の目の前に、
**チャットできないモデルを推薦として最上位に置いていた**。
**`default` を優先順位にも表示にも使わないことにした。** 契約としては読み続ける(DTO は保持する)が、
何を意味するのかを実データで説明できるまで推薦にはしない。

### `Model.capabilities` — 選択肢を絞る唯一の材料

```
capabilities required: temperature, reasoning, attachment, toolcall, input, output, interleaved
input / output required: text, audio, image, video, pdf   （いずれも boolean）
```

アプリが読むのは3つだけ: `toolcall` / `output.text` / `input.text`。

**実測(2026-08-27、connected 8社600モデル)**:

```
connected models total = 600
  toolcall == true                                 464
  output.text == true                              580
  input.text == false                               10   （うち toolcall&output.text を満たすもの 0）
  toolcall && output.text && input.text            464   ← 採用した条件
除外されるのは 136件
プロバイダ別（モデル数 -> 残る数）:
  nvidia 100->61 / deepseek 3->3 / google 38->21 / openrouter 355->287
  opencode 61->61 / mistral 26->23 / cerebras 2->2 / groq 15->6
```

**判定できないときは落とさない。** `capabilities` そのものや `toolcall` が欠けている場合は
**通す**(`null` は「分からない」であって「できない」ではない)。逆に倒すと、
capabilities を返さないサーバーで**選べるモデルが1件も出なくなり、R3 の導線が丸ごと閉じる**。
これは `session.error` の `isRetryable == null` を false と読まないのと同じ規則である。

**限界(正直な記録)**: mistral の `default` である `voxtral-small-latest`(音声モデル)は
`toolcall` も `input.text` も `output.text` も **true** なので、**この条件では落ちない**。
capabilities だけでは「音声向け」を言い当てられない。絞り込みで安全になったのではなく、
**明らかに不適な136件が消えただけ**である。だからこそ `default` を推薦に使わない。

**除外した件数は画面に出す**(「ツール実行に対応しないモデル N件は表示していません」)。
黙って短くしたリストは「そのモデルが無い」と「出さないことにした」の区別が付かない。

### キーの取り違えは 400、値の誤りは 204(2026-08-27、レビューの追試)

レビューが実物で追加測定した陰性側:

```
POST /api/session/{id}/model {"model":{"modelID":"...","providerID":"mistral"}}  -> 400
POST /api/session/{id}/model {"model":{"id":"no-such-model","providerID":"mistral"}} -> 204
```

### セッションの `directory` が消えていると 500 になる(2026-08-30、切り分け実測)

**セキュリティE2Eゲートが「`POST /api/session/{id}/model` は実物 1.18.21 で常に 500 を返す」と
報告したが、常にではなかった。** 分岐しているのは**モデルの値でもセッションの状態でもなく、
`Session.directory` がホストのファイルシステムに実在するかどうか**である。

```
# 同一 serve(1.18.21, port 4097)、同一 projectID(c131653a…)、違うのは directory だけ
sid_A  directory = E:\dev\github.com\noxitro\opencode-android  (実在)
sid_B  directory = E:\github\opencode-android                  (**移設前の旧パス。もう無い**)

POST /api/session/{sid_A}/model {"model":{"id":"mistral-small-latest","providerID":"mistral"}} -> 204
POST /api/session/{sid_A}/agent {"agent":"build"}                                              -> 204

POST /api/session/{sid_B}/model {"model":{"id":"mistral-small-latest","providerID":"mistral"}} -> 500
POST /api/session/{sid_B}/model {"model":{"id":"zzz","providerID":"zzz"}}                       -> 500
POST /api/session/{sid_B}/model {"model":{"modelID":"m","providerID":"mistral"}}                -> 500   ← 本来 400 のはずの形も 500
POST /api/session/{sid_B}/agent {"agent":"build"}                                               -> 500
   500 body: {"name":"UnknownError","data":{"message":"Unexpected server error. Check server logs for details.","ref":"err_…"}}
```

- **決定的**(同じ要求を3回繰り返して3回とも 500。`ref` だけ毎回変わる)
- **モデル値と無関係**。存在するモデルでも存在しないモデルでも同じ 500
- **キー取り違え(`modelID`)が 400 ではなく 500 になる** ——
  `directory` の解決が**ペイロード検証より前**に走っていることを意味する
- `GET /session/{sid_B}` は **200 で返る**。壊れているのは書き込み側だけで、
  一覧・閲覧からはこのセッションが「切替できない」ことが分からない
- **spec は 500 を宣言していない**(`docs/spec/opencode-1.18.21-openapi.json` の当該パスは
  204/400/401/404 のみ)。**未宣言の応答**である

**このホストで踏みやすい理由**: `GET /session` の直近100件のうち **98件が旧パス
`E:\github\opencode-android`** のセッションである(HANDOFF §0-4 のリポジトリ移設の残骸)。
一覧から適当に1件選ぶと 98% で 500 側を引く。ゲートが「常に 500」と読んだのはこのため。
**新規に `POST /session` で作ったセッションは serve の cwd に紐づくので 204 側**になる。

**スタブの 204 は実物と食い違っていない。** 実物も「生きている `directory` のセッション」には
204 を返す。したがって `e2e-stub/server.mjs` は変更しない(HANDOFF §4 欠陥形#9 の
「スタブが実サーバーと違う挙動を正にする」には**該当しない**)。スタブが再現していないのは
**この 500 の前提条件のほう**であり、それは `STUB_MODEL_SWITCH_FAIL=500` で再現できる。

**アプリ側**: `switchSessionModel` は失敗を `ApiError.Http(500)` として返し、
`AppRoot.describe()` が **「サーバーエラー(HTTP 500)」だけ**を出す(応答ボディも `ref` も
画面に出さない)。ゲートの実測と一致しており、**シークレット漏洩ガードの観点では陽性証拠**。
一方で「切替に失敗した」としか言えず、**原因(セッションの作業ディレクトリが消えている)は
ユーザーに伝わらない** —— 中核原則「無いと取れなかったを区別する」の未回収項目として記録する。
サーバーが `UnknownError` としか言わない以上、アプリ側だけでは区別できない。

**キー名を間違えると大声で失敗し、静かに壊れるのは値だけ**である。
`ModelRefDto` / `PromptModelRefDto` / `AgentModelDto` を別の型に分けてあるのはキー側の対策で、
値側(存在しないモデルID)に効くのは**候補を `GET /provider` からしか作らないこと**だけである。
したがって **カタログが古いままだと防波堤は抜ける** —— 接続先が変わったらカタログを捨てる
(サーバーAのモデルIDをサーバーBへ送れば 204 で保存される)。

### 機能の当て先(計画書の分岐に対する裁定)

実測 #2 により **セッションモデル切替は効く**ので、計画書の分岐は
「**チャット TopAppBar にモデルピル + 切替シート**」を採る。加えて:

- **作成時指定も実装する**(#2 で `POST /session` の `agent`/`model` 反映を実測済み)。
- **`prompt_async` には `model`/`agent` を載せない**(#3。載せるとセッション既定が黙って変わる)。
- **切替後はサーバーから引き直す**(`session.updated` が流れないため。上記SSE節)。
- **候補は `GET /provider` の `connected` と `GET /agent` からのみ**(#4。自由入力を作らない)。
- **候補はさらに `capabilities` で絞る**(レビュー major-2。600 → 464、除外件数は画面に出す)。
- **`providers.default` を推薦に使わない**(同。connected 8社中3社の `default` が `toolcall:false`)。
- **接続先が変わったらカタログを捨てる**(レビュー major-1。古い候補は「候補以外を送らない」という
  防波堤をそのまま抜け、別サーバーは 204 で受理して保存する)。

---

## Q6 で使用する分(2026-08-27 実機確認)

### `GET /permission` — 未応答 permission の権威

| メソッド | パス | リクエスト | レスポンス |
|---|---|---|---|
| GET | `/permission` | query: `directory?` `workspace?` | `PermissionRequest[]`(**全セッション横断の未応答 permission**) |

**実測(2026-08-27、実物 serve 1.18.21 / ポート4097 / Basic認証)**:

```
GET /permission  -> 200  []
GET /question    -> 200  []
```

`PermissionRequest` の required は `id`(`^per`)/ `sessionID`(`^ses`)/ `permission` /
`patterns` / `metadata` / `always`。`tool`(`{messageID, callID}`、両方 required)は任意。
`additionalProperties: false`。**これは `permission.asked` の properties と同じ形**であり、
`GET /question` と `question.asked` の関係(Q3)と対称である。

**`metadata` は `String` ではない。** P4 が `String?` と宣言して
「承認ダイアログが一度も出ない」を起こした箇所そのものなので、`JsonElement?` で受ける。

**測れていないこと**: 非空の応答は観測していない。402 Payment Required により推論が走らず、
ツール実行の承認要求を実物で発生させられないため。**要素の形は spec スナップショットと
P4 が実物から採取した `permission.asked` の properties に依拠している**(推測ではないが、
`GET /permission` の応答としては未観測)。スタブ(`e2e-stub`)には Q6 で同名の口を足し、
非空の経路はそちらで実測した。

### なぜ足したか(申し送り Q5-2)

`permission.asked` は SSE でしか来ない。取りこぼす(あるいは画面を出る/プロセスが死ぬ)と
**戻す口が1つも無く、サーバーは応答を待ったまま止まり続ける**。
Q3 が question について採った「**サーバーを権威にする**」を、permission にも同じ形で当てる:

- **入室時**と**再接続のたび**に引き直す(RUN_PLAN 決定2 と同じ問いへの答え)
- `session.idle` / `session.error` でダイアログを畳むのは**楽観的な推測**にすぎないので、
  畳んだ直後に引き直す(Q3 の F4「失効は推測であって事実ではない」と同じ扱い)
- `GET /session/status` 由来の「実行中でない」では**畳まない** —— そのポーリングは
  「この承認要求がもう応答できないか」を知らない
- 取れなかったときは**何もしない**(読めなかったことを「未応答は無い」に化けさせない)

## Q7 で使用する分(2026-08-27 実機確認)

**採取元**: 実物 serve 1.18.21(`127.0.0.1:4097`、Basic 認証あり、pid 33152)の `GET /doc`。
取得した JSON の **SHA-256 は `docs/spec/opencode-1.18.21-openapi.json` と一致**した:

```
live : C3A9F94AF0C3324D97B482B14C692E810CE7CCAC3136319BA46334DE972B4CF1  (478,747 bytes)
snap : C3A9F94AF0C3324D97B482B14C692E810CE7CCAC3136319BA46334DE972B4CF1  (478,747 bytes)
```

つまりスナップショットは**現在の実機と同一**である。以下は実機 `/doc` から読み、
**エンドポイントは実際に叩いて応答を採った**(叩けなかったものは明記する)。

### エンドポイント

| メソッド | パス | 応答 | 実測 |
|---|---|---|---|
| GET | `/vcs?directory=&workspace=` | `VcsInfo` | ✅ 200 `{"branch":"master","default_branch":"master"}` |
| GET | `/vcs/status?directory=&workspace=` | `VcsFileStatus[]` | ✅ 200(変更なしで `[]`) |
| GET | `/vcs/diff?mode=git\|branch&context=N&directory=` | `VcsFileDiff[]` | ✅ 200 / **`mode` 省略は 400** |
| GET | `/vcs/diff/raw?directory=` | `text/x-diff` の生パッチ | ➖ 未使用(採用しない。理由は下記) |
| GET | `/session/{sessionID}/diff?messageID=&directory=` | `SnapshotFileDiff[]` | ✅ 200 だが**既存200セッションすべてで `[]`** |
| POST | `/session/{sessionID}/revert` `{messageID, partID?}` | `Session` | ➖ **実物では実行していない**(§5b の指示) |
| POST | `/session/{sessionID}/unrevert` | `Session` | ➖ 同上 |
| POST | `/vcs/apply` | — | ⛔ **実装しない**(§5b が明示的に除外) |

`/vcs/diff/raw` を使わないのは、1本の文字列に全ファイルが入るため
「**展開したファイルだけハンクを組む**」(§5b Q7 スコープ2)ができないからである。
パーサ側は複数ファイルを含む patch も解けるので、必要になれば Q8 で使える。

### **計画書 §5b の記述と spec の required が食い違う**(実測が計画書を覆した件)

QUALITY_PLAN §5b は形をこう書いている:

```
SnapshotFileDiff = {file, patch, additions, deletions, status}
VcsFileStatus    = {file, additions, deletions, status}
VcsFileDiff      = {file, patch, additions, deletions, status}
VcsInfo          = {branch, default_branch}
```

**これは「観測されたフィールドの一覧」であって required ではない。** 実機 `/doc` の required:

| スキーマ | required | 任意 |
|---|---|---|
| `SnapshotFileDiff` | **`additions` / `deletions` だけ** | `file` `patch` `status` |
| `VcsFileStatus` | `file` `additions` `deletions` `status` | — |
| `VcsFileDiff` | `file` `additions` `deletions` | `patch` `status` |
| `VcsInfo` | **無し**(1つも required でない) | `branch` `default_branch` |

いずれも `additionalProperties: false`。`status` の enum は `added` / `deleted` / `modified`。

**アプリは全部 nullable で受ける。** required でないものを必須宣言する形は、このリポジトリが
3回踏んでいる(P4 の `metadata`、P4 の `PermissionRepliedEvent`、L3 の `error`)。
どれも**テストは全緑のまま画面が黙って壊れた**。`status` を Kotlin の enum にしないのも同じ理由で、
サーバーが `renamed` を1件返した瞬間に応答全体が decode 失敗する。

### `mode` は required(実測)

```
GET /vcs/diff                       -> 400
{"name":"BadRequest","data":{"message":"Missing key\n  at [\"mode\"]","kind":"Query"}}
GET /vcs/diff?mode=git              -> 200
GET /vcs/diff?mode=branch           -> 200 []（既定ブランチと同じ枝に居るため）
```

§5b は `?mode=git|branch` と書いているが**必須とは書いていない**。必須である。

### `context` の既定は「ファイルほぼ全体」(実測)

同じ57行のファイルに対して:

```
context 未指定 -> @@ -1,57 +1,57 @@ の1ハンク（ファイル全体が1つのハンクになる）
context=3     -> @@ -1,5 +1,5 @@ と @@ -50,7 +50,7 @@ def f17():  の2ハンク
context=0     -> @@ -2 +2 @@ def f1():  と @@ -53 +53 @@ def f18():
```

**既定に任せない。** 1行の変更でもファイル全部が端末へ来る。アプリは常に `context=3`
(`VCS_DIFF_CONTEXT`)を送る。`git diff` の既定と同じ値。

### `patch` は **`diff --git` から始まる完全な git patch** である

ハンクだけではない。実測した形(採取手順は下記):

```
diff --git a/added2.txt b/added2.txt      新規: new file mode + --- /dev/null
diff --git a/modified.txt b/modified.txt  削除: deleted file mode + +++ /dev/null
diff --git a/image.bin b/image.bin        バイナリ: Binary files a/x and b/x differ の1行だけ
@@ -1,4 +1,5 @@ ... -d / \ No newline at end of file / +d / +e / \ No newline at end of file
```

- **`\ No newline at end of file` は直前の行に掛かる**。実応答では**2回出る**
  (削除側の最終行と追加側の最終行)。行として数えると行番号が全部ずれる
- **`@@` の件数は省略されうる**(`@@ -2 +2 @@` = 1行)。`context=0` で実際に出る
- **`@@ ... @@` の後ろに関数名の見出しが付く**ことがある(`@@ -50,7 +50,7 @@ def f17():`)
- バイナリは**ハンクを1つも持たない**。「差分が無い」と区別すること

パーサはアプリ側(`ui/UnifiedDiff.kt`)。フィクスチャは
`app/src/test/java/dev/opencode/android/Q7DiffFixtures.kt` に**サーバーが返したバイト列のまま**置いた。

### 採取手順(再現できること)

`?directory=` が効くので、**本リポジトリの作業ツリーを汚さずに**差分を作れる:

1. 使い捨てディレクトリに `git init` し、5つの形の変更を作る
   (追加 / 削除 / 通常の変更 / 末尾改行なし / バイナリ)
2. 本リポジトリを cwd に起動済みの serve に対して
   `GET /vcs/diff?mode=git&context=3&directory=<そのディレクトリ>` を発行する
3. 応答の `patch` をそのまま貼る

**実測**: `/vcs?directory=` も `/vcs/status?directory=` も同じディレクトリを見る。
未追跡ファイルも `status:"added"` として `/vcs/status` と `/vcs/diff` の両方に載る。
バイナリの `additions` / `deletions` は **0**(git が行を数えられないため)。
「0 だから変更なし」と読まないこと。

### `Session.revert` — 巻き戻しの権威

```
Session.revert = {messageID (required), partID?, snapshot?, diff?}
```

`POST /session/{id}/revert` も `unrevert` も **`Session` を返す**ので、
「このセッションは今巻き戻されているか」の権威は**サーバー**である。
`GET /session/{id}` にも載るので、入室と再接続で引き直せば他クライアント(TUI/CLI)の
revert にも追随する(RUN_PLAN 決定2 の Q7 における対応物)。

**`session.revert` に相当する SSE イベントは実機 spec に存在しない。**
`/vcs` の変化を伝えるイベントも無い。したがって **ブランチも巻き戻し位置も、
イベント列からは原理的に復元できない** —— 取り直さなければ画面は永久に古い。

### `POST /session/{sessionID}/revert` のボディ

```json
{"messageID": "msg_...", "partID": "prt_..."}
```

spec: `messageID` required(pattern `^msg`)、`partID` 任意(pattern `^prt`)、
`additionalProperties: false`。**`"partID": null` を送らないこと** ——
`additionalProperties:false` のスキーマに明示 null を送ると 400 になる形を
Q4 が `POST /session` で実測している。`contractJson` の `explicitNulls = false` が防ぐ。

応答: 200 `Session` / 400 BadRequest / 404 NotFoundError / **409 SessionBusyError**
(実行中のセッションは巻き戻せない)。

### `PatchPart` — 「N ファイル変更」チップの材料

```
PatchPart: required = id, sessionID, messageID, type("patch"), hash, files[]
SnapshotPart: required = id, sessionID, messageID, type("snapshot"), snapshot
```

**変更したファイル名はメッセージ自身が持っている。** したがってチップは
**通信を1つも起こさずに**出せる。`GET /session/{id}/diff` を撃つのは押されたときだけ。

### `messageID` は「user メッセージ」と書かれている(未検証)

`/doc` の説明文:

> Get the file changes (diff) that resulted from a specific **user** message in the session.

`messageID` の pattern は `^msg` で、user / assistant の区別は型では表れない。
**どのメッセージIDを渡すと非空になるかは実物で検証できていない**(下記)。
アプリはチップが属するメッセージの ID を渡し、空が返ったら
**「差分の本文をサーバーが返しませんでした」+ `PatchPart.files` のファイル名**を出す。
「変更はありません」とは言わない —— チップが「3 ファイル変更」と言っている以上それは嘘になる。

### **402 で測れなかったこと(正直な記録)**

「測れない」と「測っていない」を区別する(RUN_PLAN の常設ルール)。
**推論を伴わない `/vcs` `/vcs/status` `/vcs/diff` は全部測った**(上表 ✅)。
測れなかったのは次の4つだけで、いずれも**エージェントが実際にファイルを書き換えないと発生しない**:

1. **`GET /session/{id}/diff` の非空応答。** 実物 serve の**既存200セッション全部**に対して
   発行し、**200件すべてが `[]`** だった。402 Payment Required でどのセッションも
   ファイルを1つも変更していないため。非空の描画はスタブ(`e2e-stub`)でのみ測っている
2. **`messageID` に何を渡せば非空になるか**(上記の user/assistant の別)
3. **`SnapshotFileDiff.status` の実値**。`VcsFileStatus` / `VcsFileDiff` では
   `added`/`deleted`/`modified` の3つを実測したが、snapshot 側の応答は空だった
4. **`revert` / `unrevert` の実物応答。** これは 402 のせいではなく、
   **§5b が本リポジトリに対する revert を禁じている**ため(ファイルシステムを書き換える)。
   使い捨ての一時リポジトリでの1回の実測は E2E 担当に委ねる

### `POST /vcs/apply` を実装しない(§5b の除外を守る)

**`DiffGateway` に口ごと存在しない。** 「使わないが口だけ開けておく」をしないのは、
開いた口は必ず誰かが繋ぐからである。端末からの任意パッチ適用は入力手段が無く、
誤爆の被害がリポジトリ全体に及ぶ。必要になったら独立フェーズで合意を取る。

### `patch` が任意であることの帰結(2026-08-28、Q7 レビュー minor-1)

`VcsFileDiff` の required は `file` / `additions` / `deletions`、
`SnapshotFileDiff` は `additions` / `deletions` だけである(上表)。つまり

```json
{"file":"a.txt","additions":1,"deletions":0}
```

は**合法な応答**で、`patch` も `status` も無い。**このときそれはバイナリではない** ——
サーバーは「バイナリかどうか」を何も言っていない。

現行 serve 1.18.21 はバイナリにも `patch`(`Binary files a/x and b/x differ` の1行)を返すので
この形は実測できていないが、**契約が許す以上クライアントは区別しなければならない**。
アプリは4つに分けて表示する(`ui/DiffController.kt` の `DiffFileBody`):

| 状態 | 判定 | 画面 / `content-desc` |
|---|---|---|
| `patch` が来ていない | `patch` が null か空 | 「差分の本文がサーバーから届いていません(バイナリとは限りません)」/ `diff-patch-missing` |
| 解釈できない | パースして0ファイル | 「差分の形を解釈できませんでした」/ `diff-unreadable` |
| バイナリ | `Binary files …` を検出 | 「バイナリファイル(差分を表示できません)」/ `diff-binary` |
| 変更行なし | ハンク0(モード変更のみ等) | 「変更行はありません」/ `diff-no-hunks` |

「**読めない**」と「**来なかった**」を1つの文言にまとめると、Q0 の R1(空バブル)や
Q4 の除外件数表示で閉じたのと同じ欠陥が戻る。

### `directory` は**データ層が最初から通せる**(2026-08-28、Q7 レビュー minor-5)

QUALITY_PLAN §6 の指示どおり、Q7 の全エンドポイントが `directory` クエリを受け取れる。
**UI は出さない**(Q7 では常に null = サーバーの cwd)。

```
GET  /vcs?directory=
GET  /vcs/status?directory=
GET  /vcs/diff?mode=&context=&directory=
GET  /session/{id}/diff?messageID=&directory=
POST /session/{id}/revert?directory=
POST /session/{id}/unrevert?directory=
```

`DiffGateway` / `ChatGateway`(revert・unrevert)と `DiffController` / `ChatController` が
`directory` を持ち、`OpenCodeApi` の `queryString` が **null のキーを丸ごと落とす**。
`?directory=` を空値で送らないことは `Q7HttpContractTest` が URL 文字列で固定している。

**実測**: `?directory=<使い捨てリポジトリ>` を付けると、本リポジトリを cwd に起動した serve が
そのディレクトリの git 状態を返す。フィクスチャの採取はこの性質を使っている。

### 未知のクエリキーは**黙って無視される**(2026-08-28、レビュー実測)

`GET /session/{id}/diff?messageId=msg_x`(小文字 d)は **200** を返す。
`messageID` を取り違えても**エラーにならず、セッション全体の差分が返る**。

これは「キーの取り違えは 400 で弾かれる」という Q4 の実測(`POST /session` のボディ)とは
**別の話**である —— **ボディのキーは検証されるが、クエリのキーは検証されない**。
したがってクエリ文字列は**クライアント側でしか守れない**。
`Q7HttpContractTest` が「アプリが実際に撃った `path?query`」を文字列で assert する唯一の場所である。

## Q8 で使用する分(2026-08-28 実機確認)

**採取元**: 実物 serve(`http://127.0.0.1:4097`、pid 33152、本リポジトリを cwd に起動)の `GET /doc`。

```
curl -u opencode:*** http://127.0.0.1:4097/doc -o e2e-artifacts/Q8/doc-live.json   # 200 / 478,747 bytes
sha256sum e2e-artifacts/Q8/doc-live.json docs/spec/opencode-1.18.21-openapi.json
c3a9f94af0c3324d97b482b14c692e810ce7ccac3136319ba46334de972b4cf1 *e2e-artifacts/Q8/doc-live.json
c3a9f94af0c3324d97b482b14c692e810ce7ccac3136319ba46334de972b4cf1 *docs/spec/opencode-1.18.21-openapi.json
```

**一致**(8段連続)。以下の required は **spec から機械抽出**したものであって、
QUALITY_PLAN §5b の記載を書き写したものではない(Q7 で計画書の記載が6件覆された)。

### エンドポイント

```
GET /file?path=&directory=&workspace=          -> FileNode[]      path は **required**
GET /file/content?path=&directory=&workspace=  -> FileContent     path は **required**
GET /file/status?directory=&workspace=         -> File[]
GET /find?pattern=&directory=&workspace=       -> Match[]         pattern は **required**
GET /find/file?query=&dirs=&type=&limit=&directory=&workspace= -> string[]   query は **required**
GET /find/symbol?query=&directory=&workspace=  -> Symbol[]        query は **required**
```

`path` / `pattern` / `query` を省くと **400**(実測。**クエリの必須はサーバーが検証する**):

```
GET /file        -> 400 {"name":"BadRequest","data":{"message":"Missing key\n  at [\"path\"]","kind":"Query"}}
GET /find        -> 400 {"name":"BadRequest","data":{"message":"Missing key\n  at [\"pattern\"]","kind":"Query"}}
```

一方 **未知のクエリキーは黙って無視される**(Q7 の実測がそのまま効く)。`directory` を `dir` と
書き間違えると **200 が返り、サーバーの cwd が黙って使われる**。
クエリ文字列はクライアント側でしか守れない(`Q8HttpContractTest` が撃たれた URL を文字列で固定する)。

### required(spec から機械抽出。**計画書の記載ではない**)

| スキーマ | required |
|---|---|
| `FileNode` | `name` `path` `absolute` `type` `ignored` — **5つ全部**。`type` は enum `file`\|`directory` |
| `FileContent` | **`type` `content` の2つだけ**。`diff` `patch` `encoding` `mimeType` はすべて任意 |
| `File`(`/file/status`) | `path` `added` `removed` `status` — 4つ全部。`status` は enum `added`\|`deleted`\|`modified` |
| `Match`(`/find` の要素) | `path` `lines` `line_number` `absolute_offset` `submatches` — 5つ全部 |
| `Match.submatches[]` | `match` `start` `end` |
| `Symbol` | `name` `kind` `location`。`location` は `uri` `range` 必須、`range` は `start`/`end`、各 `line`/`character` |

**`FileNode.ignored` は required である。** 「無ければ false」で受けても実害は無いが、
**欠けるのは契約違反**なので、欠けたらそれは別の問題である。

### **`FileContent.patch` は文字列ではない**(計画書の記載を実測が覆した 1)

QUALITY_PLAN §5b は `FileContent = {type, content, diff?, patch?, ...}` と書き、
`diff`/`patch` を持つときは **Q7 の差分ビューアを再利用する**と定めている。
Q7 の差分ビューアが食うのは **unified diff の文字列**である。

spec の `FileContent.patch` は**構造化オブジェクト**である:

```
patch: {oldFileName, newFileName, oldHeader?, newHeader?,
        hunks: [{oldStart,oldLines,newStart,newLines,lines[]}], index?}
       required: oldFileName / newFileName / hunks
```

`diff` だけが `type: string` である。したがって **Q7 のパーサへ渡してよいのは `diff` であって
`patch` ではない**。`patch` を文字列として宣言すると `SerializationException` になり、
**差分を出すための宣言がファイルの中身ごと表示できなくする**(L3 の `jsonPrimitive` 欠陥と同じ形)。
本実装は `diff` だけを使い、`patch` は DTO に宣言しない(`ignoreUnknownKeys` で落ちる)。

### **`diff` / `patch` は 1.18.21 では一度も返らない**(実測)

変更済みファイル(`git status` が `M` と言うファイル)に対して撃っても、
返るキーは `type` と `content` の2つだけだった:

```
# 使い捨てリポジトリ q7repo。git status: M big.py / M image.bin / D modified.txt / M nonewline.txt / ?? added2.txt
GET /file/content?path=big.py&directory=<q7repo>        -> keys=[type, content]
GET /file/content?path=nonewline.txt&directory=<q7repo> -> keys=[type, content]
GET /file/content?path=image.bin&directory=<q7repo>     -> keys=[type, content, encoding, mimeType]
GET /file/content?path=app/src/.../Theme.kt             -> keys=[type, content]
```

**「変更あり」トグル(§5b Q8 スコープ2)は、この serve では一度も出ない。**
機構はスタブで検証する(`STUB_FILE_DIFF=1`)。**到達不能であることを明記する**のは
RUN_PLAN が `DELIVERY_FAILURE` 帯について定めた扱いと同じ —— 到達不能はサーバーが返さない証拠であって、
機構が不要である証拠ではない。

### **`GET /file/status` は変更を返さない**(計画書の記載を実測が覆した 2。**最も重い1件**)

同じ瞬間に同じディレクトリへ撃った2本:

```
GET /file/status?directory=<q7repo>  -> []            (200)
GET /vcs/status?directory=<q7repo>   -> [{"file":"added2.txt","additions":2,"deletions":0,"status":"added"},
                                         {"file":"big.py","additions":2,"deletions":2,"status":"modified"},
                                         {"file":"image.bin","additions":0,"deletions":0,"status":"modified"},
                                         {"file":"modified.txt","additions":0,"deletions":6,"status":"deleted"},
                                         {"file":"nonewline.txt","additions":2,"deletions":1,"status":"modified"}]
```

本リポジトリでも同様(`/file/status` → `[]`、`/vcs/status` → 未追跡の1件を返す)。

**`/file/status` は 1.18.21 では常に `[]` である。** 口は 200 を返すので、
**「変更が無い」と「この口が機能していない」が応答からは区別できない。**
したがって **Q8 はこの口を使わない** —— 変更ファイルの権威は Q7 が既に使っている
`GET /vcs/status` である。ツリーの `M`/`A` バッジもそこから取る。

> **口が 200 を返すことは、口が動いていることではない。**
> Q7 が revert について書いた「サーバーが 200 を返しても、それは『巻き戻した』ではなく
> 『巻き戻す対象が無いので何もしなかった』である」と同じ形が、**読み取り側にも在る**。

### `FileNode.path` は **Windows のセパレータで返り、ディレクトリは末尾に区切りが付く**(実測)

```
GET /file?path=app
[{"name":"build","path":"app\\build\\","absolute":"E:\\github\\opencode-android\\app\\build","type":"directory","ignored":true},
 {"name":"src","path":"app\\src\\","absolute":"E:\\github\\opencode-android\\app\\src","type":"directory","ignored":false},
 {"name":"build.gradle.kts","path":"app\\build.gradle.kts","absolute":"E:\\...\\app\\build.gradle.kts","type":"file","ignored":false}]
```

- **`path` の区切りは `\`**(サーバーの OS 依存)。**ディレクトリは末尾に `\` が付く**
- **入力側は `/` でも `\` でも通る**(実測: `?path=app/src` と `?path=app%5Csrc` が同一の応答)
- `.` がルート(`?path=.`)

**帰結**: 応答の `path` をそのまま次の `?path=` に渡すのは動くが、パンくず・親子判定・
チャットのツールカードから来るパス(`/` 区切り)と**混ざる**。アプリは受け取り口で
`\` → `/` に正規化し末尾の区切りを落とす(`normalizeServerPath`)。**送るときは `/` で送る**。

### `GET /find` は **10件で打ち切られる。件数を指定する口が無い**(実測)

```
GET /find?pattern=the      -> 10
GET /find?pattern=a        -> 10
GET /find?pattern=e        -> 10
GET /find?pattern=opencode -> 10
GET /find?pattern=zzzzqqqxxx_nope -> []   (200。0件は 404 ではない)
```

`a` や `e` が本リポジトリに10箇所しか無いことはあり得ない。**サーバー側の固定上限**であり、
spec に `limit` パラメータは**無い**(`/find/file` にはある)。

**帰結**: 全文検索の結果は**常に「先頭10件」であって「全件」ではない**。
ちょうど `FIND_SERVER_CAP`(=10)件返ったときは「これ以上あるかもしれない」を画面に出す ——
Q6 の打ち切り注記、Q1 のページング上限、Q7 の 5000 行打ち切りと同じ原則。
**黙って10件を全件のように見せない。**

### `Match.submatches[].start` / `end` は **バイトオフセット**である(実測)

```
GET /find?pattern=差分ビューア
{"path":{"text":"docs/QUALITY_PLAN.md"},
 "lines":{"text":"1. **差分ビューア**: unified patch をパースし、..."},
 "line_number":409,"absolute_offset":30351,
 "submatches":[{"match":{"text":"差分ビューア"},"start":5,"end":23}]}
```

`差分ビューア` は **6文字 / 18バイト**。`start=5`(`1. **` の5バイト)、`end=23 = 5+18`。
**文字オフセットとして切ると別の場所を塗る**(実測: 文字で切ると `**: unified ` が出る)。
応答自体は正しい UTF-8(生バイトで確認。端末表示が化けていただけ)。

ripgrep の JSON 形式そのままなので `absolute_offset` もファイル先頭からのバイト数である。
アプリは **UTF-8 バイト → 文字インデックス**へ変換してからハイライトする
(`byteRangeToCharRange`)。変換を落とすと**日本語を含む行だけがずれて塗られる** ——
画面はどこも壊れて見えない。

**1行に同じ語が複数回**出るケースは実データで採れている(ゲートが要求している形):

```
{"path":{"text":"gradlew.bat"},"lines":{"text":"@rem Licensed under the Apache License, Version 2.0 (the \"License\");\n"},
 "line_number":4,"absolute_offset":62,
 "submatches":[{"match":{"text":"the"},"start":20,"end":23},{"match":{"text":"the"},"start":53,"end":56}]}
{"path":{"text":"scripts/q5_mutations.py"},"lines":{"text":"ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))\n"},
 "line_number":15,"absolute_offset":529,
 "submatches":[{"match":{"text":"path"},"start":10,"end":14},{"match":{"text":"path"},"start":26,"end":30},
               {"match":{"text":"path"},"start":42,"end":46},{"match":{"text":"path"},"start":50,"end":54}]}
```

`lines.text` は**末尾の改行を含む**(`\n`)。そのまま描くと行が1つ余分に見える。

### `GET /find/file` の `limit` は **1〜200**。超えると 400(実測)

```
GET /find/file?query=a&limit=3   -> ["app\\","app/src\\","AGENTS.md"]
GET /find/file?query=a&limit=999 -> 400 {"name":"BadRequest","data":{"message":"Expected a value less than or equal to 200, got 999\n  at [\"limit\"]","kind":"Query"}}
GET /find/file?query=Theme       -> ["app/src/main/res/values/themes.xml", ..., "app/src/main/java/dev/opencode/android/ui/theme\\", ...]
```

**返る文字列はセパレータが混在する**: `"app/src/main/res/values/themes.xml"`(`/`)と
`"app\\"` `"app/src\\"`(ディレクトリは末尾 `\`)が同じ配列に入る。`FileNode.path` と同じ正規化を通す。

`dirs` は **boolean ではなく文字列 enum `"true"`|`"false"`**、`type` は `"file"`|`"directory"`。
`?dirs=1` は通らない。**Q8 は `type` と `dirs` を送らない**(既定=ファイルもディレクトリも返る)。
`limit` は **必ず送る**(`FIND_FILE_LIMIT = 100`)—— §5b スコープ4 の要求そのもの。

### `GET /find/symbol` は **この環境では常に `[]`**(実測)

```
query=DiffController -> []   query=Theme -> []   query=class -> []
query=a              -> []   query=e     -> []   query=(空)  -> []
```

`/find/symbol` は LSP のワークスペースシンボルであり、**LSP が動いていなければ空**になる
(§5b がまさにこれを想定している)。**200 と `[]` からは「無い」と「索引が使えない」が区別できない。**

アプリの取る形:**校正クエリ**。ユーザーの語が0件だったとき、
**索引が在るなら何かに当たる広い語**(`SYMBOL_INDEX_PROBE = "a"`)をもう1回撃つ。

- 校正クエリも0件 → **「シンボル索引が使えません」**(`symbols-index-unavailable`)
- 校正クエリが非0 → **「該当するシンボルはありません」**(`symbols-search-empty`)

**この判定の陽性側(索引が在る側)は、この環境では測れない** —— LSP が動く serve が無い。
陰性側(索引が無い)は実物で測れている。機構は両側ともスタブで出す(`STUB_SYMBOLS`)。
**「測れない」であって「測っていない」ではない。**

### `GET /file/content` に上限は無い。**クライアントが切る**

spec にも応答にもサイズ上限が無く、`content` はファイル全文が1本の JSON 文字列で来る。
`OpenCodeApi.call` は `resp.body.string()` で**全部をメモリに載せる**ので、
数十MBのファイルを開けば素直に OOM する(§5b のリスク欄そのもの)。

本実装は **`FILE_CONTENT_MAX_BYTES = 512 KiB`(524,288 バイト)** で**受信を打ち切る**。
打ち切ると JSON として壊れるので、`content` の途中までを**救出する純関数**
(`salvageTruncatedFileContent`)を通す。**閾値の根拠と実測メモリは `e2e-artifacts/Q8/GATES.txt`**
(TEST_REPORT に Q8 節はまだ無い。1周目は存在しない節を指していた)。

**閾値は「ファイルサイズ」ではなく「応答サイズ」に効く**(2026-08-28 実測):

```
docs/spec/opencode-1.18.21-openapi.json   ディスク 478,747 → 応答 553,653 バイト(1.157倍)
app/src/main/java/.../ui/ChatController.kt ディスク  69,143 → 応答  71,771 バイト(1.038倍)
app/src/main/java/.../ui/ChatScreen.kt      ディスク  52,909 → 応答  55,199 バイト(1.043倍)
```

本文が JSON 文字列へエスケープされるぶん応答のほうが大きい。**`openapi.json` は閾値を超えるので
実際に打ち切られる**(端末で読む物ではない機械生成データなので、その扱いでよい)。

### **存在しないパスと空のファイルは区別できない**(2026-08-28 実測。最重要の1件)

```
GET /file/content?path=no/such/file.txt          -> 200 {"type":"text","content":""}   (28 bytes)
GET /file/content?path=docs/DOES_NOT_EXIST.md    -> 200 {"type":"text","content":""}   (28 bytes)
GET /file/content?path=<本物の0バイトファイル>    -> 200 {"type":"text","content":""}   (28 bytes)
```

**3つはバイト単位で同一。** 404 も 400 も返らない。したがってアプリは
「**0バイトのファイルです / 読み込みに失敗したわけではありません**」と**言ってはならない** ——
それは応答が支持しない断言である。`GET /file/status` について本文書が書いた
「**口が 200 を返すことは、口が動いていることではない**」と同じ形が、読み取り側にも在る。

画面は `file-empty-or-missing`(「中身が空のファイル」なのか「そのパスが無い」のかは区別できません)を出す。

**ディレクトリを指すと 500**(実測。これは区別が付く):

```
GET /file/content?path=docs -> 500 {"name":"UnknownError","data":{"message":"Unexpected server error. ...","ref":"err_..."}}
```

**打ち切ったことは必ず画面に出す**(`file-truncated:<受信バイト>`)。
黙って先頭だけ出すと「ファイルがそこで終わっている」と読める。

**打ち切ると `content` より後ろのキーは失われる。** 実測の並びは
`{"type":..,"content":..,"encoding":..,"mimeType":..}` なので、巨大なバイナリでは
**`mimeType` が読めない**。そのときは「不明」ではなく「**応答が大きすぎて読めていません**」と出す
(「無い」と「取れなかった」の区別。Q0 R1 以来の原則)。

### `?directory=` は Q8 の全エンドポイントが取る

Q7 と同じく**データ層は最初から通す**(UI は出さない)。実測で
`?directory=<使い捨てリポジトリ>` は `/file` `/file/content` `/file/status` `/vcs/status` に効く。

---

## Q9 で使用する分(2026-08-30 実機確認)

**採取環境**: 実物 `opencode serve` 1.18.21 / Windows 11 / ポート 4097 /
`Authorization: Basic`(`OPENCODE_SERVER_PASSWORD` は環境変数から。値は記録しない)/
`GET /global/health` → `{"healthy":true,"version":"1.18.21"}`。
serve の起動は `scripts/serve.ps1`(launcher pid 54052 / **listen pid 49660**)。

**このフェーズだけ通信方式が違う(WebSocket)。**
QUALITY_PLAN §5b Q9 が「実機検証タスク(実装の前に必ず)」として並べた5問への答えが
この節の中身である。

### 接続シーケンス(実測)

```
POST /pty  {"command":"cmd.exe","args":[],"title":"probe"}
  -> 200 {"id":"pty_04f246e3f001Pdm7dahQta7B5e","title":"probe","command":"cmd.exe","args":[],
          "cwd":"E:\\dev\\github.com\\noxitro\\opencode-android","status":"running","pid":58556}

POST /pty/{id}/connect-token                        -> 403 {"_tag":"PtyForbiddenError",
                                                            "message":"Invalid PTY connect token request"}
POST /pty/{id}/connect-token  x-opencode-ticket: 1  -> 200 {"ticket":"65ffeda9-f427-4fd0-b10b-73c241ab6b4a",
                                                            "expires_in":60}

ws://HOST/pty/{id}/connect?ticket=<ticket>[&cursor=N][&directory=...]   -> 101 Switching Protocols
```

**`x-opencode-ticket` ヘッダは spec に載っていない。** 無いと 403 で、
チケットが取れず**ターミナルが一度も開かない**。サーバーは値ではなく**存在**を見ている
(`1` で通る)。

### エンドポイント

| メソッド | パス | 使う場面 |
|---|---|---|
| GET | `/pty/shells` | 起動候補のシェル一覧 |
| GET | `/pty` | 走っている PTY の一覧 |
| GET | `/pty/{ptyID}` | 1件(**終了すると 404**) |
| POST | `/pty` | 起動(**任意コマンド実行**) |
| PUT | `/pty/{ptyID}` | **リサイズ**(`{size:{rows,cols}}`) |
| DELETE | `/pty/{ptyID}` | 終了 |
| POST | `/pty/{ptyID}/connect-token` | チケット発行(**ヘッダ必須**) |
| GET | `/pty/{ptyID}/connect` | **WebSocket アップグレード** |

SSE(`GET /event`): `pty.created` / `pty.updated` / `pty.exited` / `pty.deleted`。

### `GET /pty/shells` は**オブジェクトの配列**を返す(計画書の記載を実測が補った 1)

QUALITY_PLAN §5b は「利用可能なシェル一覧」としか書いていないが、実物は:

```
[{"path":"C:\\Program Files\\PowerShell\\7\\pwsh.EXE","name":"pwsh","acceptable":true},
 {"path":"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.EXE","name":"powershell","acceptable":true},
 {"path":"C:\\Program Files\\Git\\bin\\bash.exe","name":"bash","acceptable":true},
 {"path":"C:\\Windows\\system32\\cmd.exe","name":"cmd","acceptable":true}]
```

`POST /pty` の `command` には **`path`** を渡す(`name` は表示用)。
`acceptable:false` は「薦めていない」であって「無い」ではないので、**一覧から消さない**。

### **終了コードは SSE の `pty.exited` からしか取れない**(計画書の記載を実測が覆した 1。最重要)

QUALITY_PLAN §5b Q9 スコープ4 は「`GET /pty` の一覧、`DELETE` で終了、
`status:"exited"` と `exitCode` の表示」と書いている。**`GET /pty` からは取れない。**

実測(`cmd.exe` に `exit 7` を送った直後):

```
GET /pty       -> 200 []
GET /pty/{id}  -> 404 {"_tag":"PtyNotFoundError","ptyID":"pty_04f25b0a9001inpIll4Gaah6gp",
                       "message":"PTY session not found: pty_04f25b0a9001inpIll4Gaah6gp"}
```

**プロセスが終わると PTY は一覧から消える。** `status:"exited"` を REST で読める窓が存在しない。
同じ瞬間に SSE へ流れるのがこれ:

```
{"id":"evt_04f25bdda001kobfy4aKcsu4SM","type":"pty.exited",
 "properties":{"id":"pty_04f25b0a9001inpIll4Gaah6gp","exitCode":7}}
```

**`DELETE /pty/{id}` は `pty.deleted` を流し、`exitCode` を持たない**(実測):

```
{"id":"evt_04f2528f7001oCwGZQrhl1Nohh","type":"pty.deleted",
 "properties":{"id":"pty_04f2528ca001vnCCP68BAoLQlf"}}
```

帰結: **終了コードを持たない終了が2種類ある**(SSE が切れている間に終わった / 自分で削除した)。
どちらも **0 と書いてはならない** —— 0 は「正常終了した」という別の主張である。
アプリは3つを別の状態にする(`pty-exited:<code>` / `pty-exited-code-unknown` / `pty-deleted`)。

### **v1(`/pty`)と v2(`/api/pty`)は別の登録簿である**(指示と食い違った実測 1)

着手時の申し送りは「`exitCode` が必要なら v2 `/api/pty/{id}` を使え」と述べていたが、
**v2 は v1 の PTY を見ない**(実測):

```
POST /pty                  -> pty_04f2528ca001vnCCP68BAoLQlf(running)
GET  /pty                  -> 200 [ その1件 ]
GET  /api/pty              -> 200 {"location":{...},"data":[]}        <- 空
GET  /api/pty/{その id}    -> 404 PtyNotFoundError

POST /api/pty              -> 200 {"location":{...},"data":{"id":"pty_04f2528df00145FWv4Zsfaxqg8",...}}
GET  /pty                  -> 200 [ v1 の1件だけ ]                    <- v2 の PTY は載らない
```

同じ申し送りの「**終了済み PTY は `DELETE` しない限り v2 の一覧に残り続ける**」も再現しなかった ——
終了すると両系統から消え、`DELETE` すら 404 を返す。
したがって**アプリは v1 だけを使う**。v2 を混ぜると2つの登録簿を跨いだ一覧になる。

### WebSocket のフレーム(実測)

**判別規則**: バイナリフレームで**先頭バイトが `0x00`** ならメタ情報(残りが JSON)。
それ以外(テキストフレーム、または先頭が `0x00` でないバイナリ)は**生の PTY 出力**。

```
BIN  hex=007b22637572736f72223a307d   = \u0000{"cursor":0}
TEXT 113 bytes  "\u001b[?9001h\u001b[?1004h\u001b[?25l\u001b[2J\u001b[m\u001b[H
                 Microsoft Windows [Version 10.0.26200.9168]
                 \u001b]0;C:\\Windows\\system32\\cmd.exe\u0007\u001b[?25h"
TEXT 110 bytes  "\u001b[?25l\r\n(c) Microsoft Corporation. All rights reserved.
                 \u001b[4;1HE:\\dev\\...\\opencode-android>\u001b[?25h"
TEXT  12 bytes  "echo hello\r\n"                        <- 送った入力のエコー
TEXT  66 bytes  "\u001b[?25lhello\u001b[7;1HE:\\dev\\...>\u001b[?25h"
TEXT  45 bytes  "\r\nE:\\dev\\...\\opencode-android>"   <- U+0003(Ctrl+C)を送った直後
```

**メタが先頭に来るとは限らない。** リプレイ本文がある接続ではテキストが先に来て最後にメタ、
リプレイが無ければメタが先。**フレームの順序に依存する実装は壊れる**ので、種別で判別する。

2026-08-30 に**測り直した**(`e2e-artifacts/Q9/ptyprobe-output.txt` §3/§5/§6。
レビュー minor-2 が「順序非依存という KDoc は実態と違う」と指摘したため):

| 接続の仕方 | 実際に来た順序 | この関数が出す位置 |
|---|---|---|
| 新品へ接続(再送する物が無い) | `[メタ cursor=0]` → `[本文 338B]` | 0 → 338 |
| `cursor=338` で接続(再送あり) | `[本文 105B]` → `[メタ cursor=443]` | 338+105=443 → 443 |
| `cursor=-1`(リプレイ無し) | `[メタ cursor=443]` のみ | 443 |

3通りとも正しい位置になる。**成り立っているのは
「メタの `cursor` はそれより前に送られた本文をすべて含む」という不変条件**であって、
「順序に依存しない」ではない —— **再送する物があるときは必ずメタが最後**に来ており、
「メタが先に来てから再送が続く」順序は一度も観測されなかった。
もし来れば、アプリは再送分を**二重に数える**(位置が進みすぎ、次の再接続で出力が飛ぶ)。

### `cursor` の意味と、**メタだけでは足りないこと**(実測。一番間違えやすい所)

`cursor` は出力ストリーム先頭からの**累積バイト数**。

| 渡す値 | 挙動 |
|---|---|
| 省略 / `0` | リプレイを全部再送する |
| `-1` | リプレイ無し(以後のライブ出力だけ) |
| `N` | N バイト目以降を再送する |

**メタフレームは接続時に1回だけ流れ、以後は更新されない。** 実測:

```
接続直後のメタ  {"cursor":0}
223 バイトが流れる(113 + 110)
入力を送ってさらに出力が流れる
-> メタは 0 のまま二度と来ない
別接続を張ると、そのメタは {"cursor":223}(それまでの累計)
```

つまり**メタの値だけを保存して再接続すると、接続してから今までの出力を全部もう一度読む**。
症状は「回転するたびに画面の先頭からやり直す」で、クラッシュもエラーも出ない。
**メタは位置を置き換え、出力フレームは自分のバイト数だけ位置を進める。**

### 入力・リサイズ・多重接続・チケット

- **入力**: 同じ WebSocket に生の端末入力を書く(テキストフレームで通る)。別エンドポイントは無い。
  **Ctrl+C は `U+0003` の1文字**で足りる(実測: 直後に `\r\n` と新しいプロンプトが返った)。
- **リサイズ**: `PUT /pty/{id}` body `{"size":{"rows":24,"cols":100}}` -> 200 + `Pty`。
  WS 経由ではない。直後に PTY が `ESC[8;24;100t` と全画面の描き直しを流す。
  **`rows:0` / `cols:0` もサーバーは 200 で受理する**(弾いてくれないので、
  クライアントが下限を持つ)。
- **多重接続**: 同一 PTY に複数の WebSocket が同時接続でき、両方が同じ出力を受け取る(実測)。
- **チケットは単回使用**: 同じチケットで2本目を開くと接続に失敗する(実測)。`expires_in` は 60。
  **再接続のたびに `connect-token` を取り直す。**
- **終了時**: プロセス終了で WS が close code **1000** で閉じる。
  ただし **`1000` から終了コードは分からない**(サーバー側の切断も同じ 1000 になりうる)。
  「まだ `GET /pty` に居るか」で切断と終了を分ける。
- **終了済み PTY への接続**: `connect-token` の段階で 404 になるので、そこまで到達しない。

### **チケットは `directory` にバインドされない**(指示と食い違った実測 2)

着手時の申し送りは「`connect-token` 発行時と `connect` 時で `directory` クエリの値を
揃えないと接続失敗」と述べていたが、**3通りとも 101 で開いた**(実測):

```
token に directory 無し / connect に directory 有り  -> 101
token に directory 有り / connect に directory 無し  -> 101
token にも connect にも directory 有り               -> 101
```

無効なチケット(でたらめな UUID)では接続が失敗するので、**チケット自体の検証は効いている**。
アプリは両方に同じ `directory` を渡すが、それは**将来サーバーが束縛を効かせたときに
「再接続だけが失敗する」形で壊れないため**であって、現在の必須要件ではない。

### ANSI の扱い(**この実装の限界。仕様として明示する**)

Windows の既定シェル(`cmd.exe` / ConPTY)は**起動直後から全画面制御を出す**。
上の1フレーム目に `ESC[2J`(画面消去)・`ESC[H`(カーソル原点)・`ESC[?25l`(カーソル非表示)・
OSC 0(ウィンドウタイトル)が**バナーより先に**入っている。

QUALITY_PLAN §5b Q9 スコープ2 は「SGR だけ解釈し、カーソル移動・画面消去・代替画面は
解釈せずに落とす」と定めており、その通りに実装した。帰結:

- **プロンプト行の見た目は実物の端末と一致しない**(カーソル位置を再現しないため)
- `vim` / `top` などの全画面TUIは**動かない**

「全画面制御を検出したら警告」方式は **Windows では常に警告が出る**ので採らなかった。
代わりに **(a) 制限の説明を常設で出し、(b) 落とした制御シーケンスを種別ごとに数えて出す**。
数えるのは「そもそも来ていない」と「**来たが解釈しなかった**」を区別するためで、
Q0 R1 以来この計画が8度閉じてきた原則の9回目である。

### セキュリティ(§5b Q9 の注記の実装)

PTY は**任意コマンド実行**である。`OPENCODE_SERVER_PASSWORD` 未設定の serve が
無認証で 200 を返すことは P5 で実測済み。アプリはターミナル画面の先頭に
`pty-security-note`(「Tailscale などのプライベートネットワーク内でのみ使ってください」)を
常設で出す。`POST /pty` に **`cwd` も `env` も送らない**(端末から任意のパスや
環境変数を注入する導線を作らない)。

### 採取手順(再現できること)

```
scripts/serve.ps1 -Port 4097                        # listen pid はログの LISTENING 行に出る
node e2e-artifacts/Q9/ptyprobe.mjs --base http://127.0.0.1:4097
```

`ptyprobe.mjs` が撃つのは、この節が主張していること全部である ——
`connect-token` のヘッダ有無(403 / 200)、ws の 101、**フレームの順序**、
チケットの単回使用、`cursor` の継続、`directory` の束縛、v1 と v2 の登録簿、
SSE の `pty.exited`、終了後の REST(404)、後片付けの `DELETE`。
**生の出力は `e2e-artifacts/Q9/ptyprobe-output.txt`**(2026-08-30 採取、serve 1.18.21)。

> **1周目はここに `<scratchpad>/ptyprobe*.mjs` と書いてあった**(レビュー major-3)。
> scratchpad はセッション限りの一時ディレクトリで、**リポジトリからは再現できない**。
> スクリプトをリポジトリ内へ移し、実物 serve に対して全項目を撃ち直した。
> `ws` パッケージは足していない(WebSocket は生の TCP で握る)——
> 検証をネットワーク取得に依存させないため。

採取したバイト列は `app/src/test/java/dev/opencode/android/Q9Fixtures.kt` に貼ってある
(要約ではなく**そのまま**。§4.2)。フレーム長(113 / 110 / 12 / 66 / 45 バイト)は
`Q9FrameTest` が assert しているので、フィクスチャが実データでなくなればテストが落ちる。
