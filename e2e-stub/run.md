# e2e-stub 起動手順(P3 チャットストリーミング検証)

opencode serve のREST+SSEを模擬するローカルスタブ。Node.js 単ファイル・依存ゼロ。
**LLMコストゼロ**でP3ゲート(逐次描画/idle復帰/回転耐性)を検証するために使う。

## 起動

```powershell
# 既定ポート4098、既定パスワード stub-pass
node e2e-stub/server.mjs

# 逐次描画の観測間隔を広げたい場合(uiautomator dumpが1〜2秒かかるため推奨)
$env:STUB_CHUNK_MS = "1500"; node e2e-stub/server.mjs
```

環境変数:

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_PORT` | `4098` | リッスンポート(実物serveの4097と被らないこと) |
| `STUB_PASSWORD` | `stub-pass` | Basic認証パスワード(**スタブ専用ダミー値。実物のOPENCODE_SERVER_PASSWORDとは無関係**) |
| `STUB_CHUNK_MS` | `500` | テキストチャンク送信間隔(ms) |
| `STUB_CHUNKS` | `チャンク1\|チャンク2\|チャンク3` | `\|`区切りのチャンク列(同一part.idに累積更新される) |
| `STUB_PERMISSION` | (未設定) | `1` で `permission.asked` を出す(P4) |
| `STUB_EMPTY_ASSISTANT` | (未設定) | `1` で「描くものが無い assistant」を履歴に混ぜる(Q0/R1) |
| `STUB_STEP_START` | (未設定) | `1` でテキストより先に `step-start` part を流す(Q0/R1) |

### Q1 で追加した変数(**すべて既定オフ/既定値は従来の挙動**)

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_SESSIONS` | `0` | 追加生成するセッション件数。`time.updated` を今日/昨日/今週/先週/以前へ巡回配置する。**ページング検証は `120`** |
| `STUB_WEEK_START` | `0`(日曜) | 日付グループ期待値の計算に使う週の起点。`Locale.JAPAN` / `Locale.US` とも SUNDAY |
| `STUB_PATCH_DELAY_MS` | `0` | PATCH(改名)の応答をこの ms だけ遅らせる。**観測のためだけの口** —— 改名の往復中に出る行内スピナー(`session-card:<id>:pending`、申し送り Q5-1)は、往復が数msで終わると dump の窓に入らない |
| `STUB_DELETE_FAIL` | `0`(失敗させない) | 非0のとき DELETE をそのHTTPステータスで失敗させる。**楽観削除のロールバック観測用**(例: `500`) |
| — | — | ページングの観測: アプリは `limit` を **50 → 100 → 200 …(倍々、上限2000)** で要求する。`__stub/stats` の `listQueries` / `maxLimitSeen` で確認できる |
| `STUB_LIST_DEFAULT_LIMIT` | `100` | `GET /session` の `limit` 既定値(実サーバー実測値と同じ) |
| `STUB_STATUS_ON_PROMPT` | (未設定) | `1` で prompt_async の演出中に `session.status` busy→idle を流す。**既定オフ**——有効にすると `eventsSent` が増え、P3/P4 の証跡が引用している件数が変わるため |

### Q2 で追加した変数(**すべて既定オフ/既定値は従来の挙動**)

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_MARKDOWN` | (未設定) | `1` で応答本文を markdown フィクスチャにする(見出し/箇条書き/番号付き/太字/インラインコード/言語つきコードブロック/リンク)。**有効時は `STUB_CHUNKS` を使わない**ので「チャンク1…」は出なくなる |
| `STUB_TOOL_PART` | (未設定) | `1` でツール活動 part を **同一 part.id のまま** running → completed で演出する。shape は spec の `ToolPart` / `ToolStateRunning` / `ToolStateCompleted` の required を満たす |
| `STUB_REASONING` | (未設定) | `1` で `type:"reasoning"` の part を流す(`ReasoningPart` の required 準拠) |
| `STUB_RETRY` | (未設定) | `1` で本文の前に `session.status{retry}` を1回流し、`STUB_RETRY_MS` 後に busy へ戻す。`action`(required 5項目)付き |
| `STUB_RETRY_MS` | `8000` | 上の retry がどれだけ続くか(ms)。`next` はこの分だけ未来になる |
| (stats) `permissionListCalls` | — | **Q6 で追加**。`GET /permission` を受けた回数。「アプリが取りに来たこと」を `__stub/stats` から引用するためのもの(復元が偶然でないことの証拠) |
| (endpoint) `GET /permission` | — | **Q6 で追加**。未応答 permission を `PermissionRequest[]` で返す(実物 serve 1.18.21 に存在することを実測: 200 `[]`)。申し送り Q5-2「チャット再入場で permission が復帰しない」の観測に使う。`GET /__stub/stats` の `permissionsPending` と同じ集合を、アプリが実際に叩く形で見せる |
| `STUB_ABORT_IDLE` | (未設定) | `1` で `POST /abort` に対し `session.status{idle}` + `session.idle` を流す。**実サーバーはこれを流す**(API_CONTRACT.md「abort が流すもの」の実測)。既定オフなのは P4ゲート②/Q0 の証跡が「abort後 `eventsSent` 不変」を引用しているため |
| `STUB_MSG_SEQ_SEED` | `0` | `msgSeq` の初期値。既定のままスタブを再起動すると `msg_u1` / `msg_a2` が**再利用**され、アプリのメモリに残る同じIDへ part が合流する(申し送り Q1-2 の「二重描画」の正体)。実サーバーのIDは再利用されないので、この人工条件を避けたい回はここをずらす |

`POST /__stub/session-status` は `action` を受け取れるようになった(渡したときだけ載る。既定は従来どおり `action` 無し):

```powershell
curl.exe -s -X POST -H "Authorization: Basic $tok" -H "content-type: application/json" `
  -d '{"sessionID":"ses_stub_0001","type":"retry","attempt":2,"next":1787785030000,"action":{}}' `
  http://localhost:4098/__stub/session-status
```

**既定挙動を変えていないことの確認**: `STUB_*` を何も設定しなければ `GET /session` は
従来どおり固定2件を返し(`limit` 既定100 > 2件)、prompt_async の SSE 列も変わらない。
唯一の差は `DELETE /session/:id` の成功応答が **204 → 200 + ボディ `true`** になったこと。
実サーバーの実測に合わせた変更で、既存のE2E(P3/P4/Q0)は DELETE を使っていない。

## アプリ側の接続先

エミュレータからは `http://10.0.2.2:4098`(ホストのループバックへNAT)。
アプリの接続設定画面で URL=`10.0.2.2:4098`、パスワード=`stub-pass` を入力する。

## エンドポイント

| メソッド | パス | 応答 |
|---|---|---|
| GET | `/global/health` | `{healthy:true, version:"1.18.21-stub"}` |
| GET | `/session` | 既定は固定2件(`ses_stub_0001` / `ses_stub_0002`)。query `start`/`limit`/`search` に対応(意味は実サーバー実測どおり: `start` は `time.updated >= start` の下限フィルタで**オフセットではない**、`limit` 既定100、`search` は title 部分一致・大小無視) |
| GET | `/session/status` | `{"<sessionID>": {"type":"busy"\|"retry"}}`。**idle は載せない**(実サーバーと同じ)。既定 `{}` |
| POST | `/session` | メモリ内に作成したSession + SSE `session.created` |
| PATCH | `/session/:id` | `{title}` で改名 → 200 + 更新後Session + SSE `session.updated`。`time.updated` は動かさない(実測に合わせる) |
| DELETE | `/session/:id` | 200 + ボディ `true` + SSE `session.deleted`。存在しなければ404 |
| GET | `/session/:id/message` | 履歴(`ses_stub_0001`=ユーザー+assistantの1往復) |
| POST | `/session/:id/prompt_async` | 204 → SSEで演出開始 |
| GET | `/event` | SSE(envelope `{id,type,properties}`) |
| POST | `/__stub/drop-events` | SSE接続全切断(再接続観測用) |
| GET | `/__stub/stats` | 内部状態(SSE接続数/プロンプト数/**`listQueries`=実際に受け取った `GET /session` のクエリ**/`patchCount`/`deleteCount`/`sessionStatus`) |
| GET | `/__stub/session-groups` | 生成セッションの「スタブ側が意図した日付グループ」と `time.updated`。アプリの見出しと突き合わせる期待値 |
| POST | `/__stub/session-status` | `{sessionID,type:"busy"\|"retry"\|"idle",attempt?,message?,next?}` で実行状態を設定し `session.status` を流す |

## SSE演出(prompt_async 1回分)

1. `message.part.updated`: ユーザーメッセージのエコー(part新規)
2. `message.updated`: assistantメッセージinfo
3. `message.part.updated` × N: **同一part.id** のtextを `STUB_CHUNKS` 順に累積更新
   (500ms/1500ms間隔)。この差分で「逐次描画」を観測する
4. 3チャンク目の直後に未知type(`session.next.text_delta`, `stub.unknown_event`)を混入
   → アプリがストリームを落とさないことの陽性試験
5. `session.idle` → 入力欄復帰
6. 完了した1往復はメモリ履歴へ追記(プロセス死→再取得=ゲート③検証用)

## 動作確認コマンド例

```powershell
$tok = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:stub-pass"))
curl.exe -s -H "Authorization: Basic $tok" http://localhost:4098/global/health
curl.exe -s -H "Authorization: Basic $tok" http://localhost:4098/__stub/stats
curl.exe -s -X POST -H "Authorization: Basic $tok" http://localhost:4098/__stub/drop-events
```

認証なし/誤パスワードは401を返す(陰性側)。

## 終了後

Ctrl+Cで停止。アプリの接続設定を実物serve(`http://10.0.2.2:4097`)へ戻すこと
(パスワードは `%TEMP%\opencode\pw-current.txt` の値。**ログや報告文に書かない**)。

## Q3 で足した分(2026-08-27)

**全て既定オフ。既定の挙動は1バイトも変えていない**(§4.4 スタブ拡張規約)。

| 環境変数 | 既定 | 効果 |
|---|---|---|
| `STUB_TODO` | `0`(オフ) | prompt シーケンス中に `todo.updated` を3段(pending中心 → in_progress → 全completed)で流す。3段目は **`session.idle` の直前**に流すので「idle 後も残るか」を観測できる |
| `STUB_QUESTION` | `0`(オフ) | `question.asked`(**2択 + `custom:true`**)を流し、reply / reject が来るまで実行を止める |
| `STUB_QUESTION_MULTI` | `0`(オフ) | 2問目(`multiple:true`・3択)を足す。単選 / 複数 / custom の混在を作る。`STUB_QUESTION=1` と併用 |
| `STUB_QUESTION_TIMEOUT_MS` | `120000` | 応答が来ないときの打ち切り |
| `STUB_QUESTION_V2` | `0`(オフ) | type 名を `question.v2.asked` / `.replied` / `.rejected` にする。**実物でどちらが流れるかは未観測**なので、アプリが両方を受ける主張をスタブ側から検証するための口 |

### 追加したエンドポイント(常時有効。**既存の挙動には影響しない** —— 従来は 404 だった)

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/session/:id/todo` | `Todo[]`。todo が無ければ `[]`(実物と同じ。404 ではない) |
| GET | `/question` | 未応答の `QuestionRequest[]`(**全セッション横断**)。無ければ `[]` |
| POST | `/question/:id/reply` | body `{answers: string[][]}`。**形を検査する**(`string[][]` でなければ400)。成功は `200` + `true`、不明IDは `404 QuestionNotFoundError` |
| POST | `/question/:id/reject` | `200` + `true` / `404` |
| POST | `/__stub/todo` | `{sessionID, phase:0\|1\|2}` で `todo.updated` を1回流す(prompt を回さずに3状態を作る口) |
| POST | `/__stub/question` | `{sessionID, multiple?, v2?}` で `question.asked` を1回流す(permission との同時 pending を作る口) |

### 観測方法

`GET /__stub/stats` に次のキーが増えた:

- `todoUpdates`: `todo.updated` を流した回数
- `todos`: sessionID -> 現在の `Todo[]`
- `questionsPending`: 未応答の requestID
- `questionsAnswered`: `[{requestID, answers}]` —— **`answers` を受け取った形のまま出す**。
  計画書のゲート「answersの形を `__stub/stats` で確認」はここを見る
- `questionsRejected`: 拒否された requestID

```powershell
# 質問カード単体を出す(permission と同時に pending にもできる)
curl.exe -s -X POST -H "Authorization: Basic $tok" -H "Content-Type: application/json" `
  -d '{\"sessionID\":\"ses_stub_0001\",\"multiple\":true}' http://localhost:4098/__stub/question
# アプリで回答したあと、送られた answers を引用する
curl.exe -s -H "Authorization: Basic $tok" http://localhost:4098/__stub/stats
```

**注意**: スタブは契約の定義ではない(RUN_PLAN「スタブの abort が実サーバーと違う」)。
Q3 の `question.*` は**実物 serve で発火を観測できていない**(402 Payment Required で推論が回らない)。
ここで確認できるのは「アプリが spec どおりの形を送受信する」ことだけである。

`POST /__stub/question` の body は `{sessionID, multiple?, v2?, empty?}`。
`empty:true` は **`options: []` かつ custom 無し**の質問を出す(回答手段が1つも無い形。
spec は `options` を required にしているが `minItems` は置いていない)。
**同じ口を続けて叩けば未応答の質問を複数同時に作れる** —— `GET /question` は
"all pending question requests" を返すので、複数 pending はサーバー側の一級の概念である。

### Q4 で追加した変数(**すべて既定オフ/既定値は従来の挙動**)

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_PROVIDER_EMPTY` | (未設定) | `1` で `GET /provider` の `connected` を `[]` にする。「選べるモデルがありません」の陽性コントロール |
| `STUB_MODEL_SWITCH_FAIL` | `0` | `>0` で `POST /api/session/:id/model` をそのステータスで失敗させる(切替失敗が送信エラー帯へ出る陰性側) |
| `STUB_NO_SWITCH_EVENT` | (未設定) | `1` で `session.next.*.switched` を流さない。**イベントが来なくても `GET /session/:id` の取り直しでピルが正しくなる**ことを測るための陽性コントロール |

### Q4 で追加したエンドポイント(常時有効。**既存の挙動には影響しない** —— 従来は 404 だった)

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/provider` | `{all, default, connected}`。**`connected` は `all` の部分集合**(`anthropic` は connected に居ない)。`Model` は spec の required 11個を持つ |
| GET | `/agent` | `Agent[]` 5件。`hidden` の3通り(`true` / 明示 `null` / キーごと無し)を実物と同じように混ぜてある |
| GET | `/session/:id` | `Session`。**モデル/エージェントの権威**(実物実測 200)。不明IDは 404 |
| POST | `/api/session/:id/model` | body `{model: ModelRef}` -> **204**。**値を検証しない**(実物と同じ。存在しないモデルも保存される)。**実物が 204 を返すのは `Session.directory` が実在するときだけ**(下記) |
| POST | `/api/session/:id/agent` | body `{agent}` -> **204**。500 の前提条件も model と同一 |

**実物が 500 を返す条件をスタブは再現しない(2026-08-30 実測)**: 実物 1.18.21 は
**`Session.directory` がホストに実在しない**セッションに対して、モデル値によらず
`500 {"name":"UnknownError",…}` を返す(キー取り違えすら 400 でなく 500 になる)。
スタブは常に 204 側の挙動だけを持つ。**これは「スタブが実物と違う挙動を正にしている」
のではなく**、実物の正常系(生きた directory)と一致したうえで異常系を持たない、という意味である。
異常系が要るときは `STUB_MODEL_SWITCH_FAIL=500` で作れる。
切り分けの全文は `docs/API_CONTRACT.md`「セッションの `directory` が消えていると 500 になる」。

`POST /session` は `agent` / `model` を受理して `Session` に反映する(**キーが無ければ生やさない**。
実物も未指定のセッションでは両方欠ける)。

**切替では `session.updated` を流さない。** これは実物 serve の実測(2026-08-27)に合わせたもので、
流れるのは `session.next.model.switched` / `session.next.agent.switched` だけである。
**したがってアプリは切替後に `GET /session/:id` を引き直さなければ正しい表示にならない**
(RUN_PLAN 決定2 の Q4 における対応物)。

### 観測方法

`GET /__stub/stats` に次のキーが増えた:

- `providerCalls` / `agentCalls`: カタログを引いた回数。**「モデル選択を開くまで引かない」の検出器**
  (実物の `GET /provider` は 5.4 MiB あるので、起動時に引いていないことは数で主張する)
- `modelSwitches`: `[{at, sessionID, model}]` —— **送られた `ModelRef` をそのまま出す**。
  `id` と `modelID` の取り違えはここに載る文字列でしか主張できない
  (実サーバーは誤った値も 204 で受理して保存する)
- `agentSwitches`: 同上
- `sessionModels`: sessionID -> `{agent, model}`

```powershell
# 5.4 MiB を引く口を「開くまで引かない」ことの確認(アプリ起動直後は 0 のはず)
curl.exe -s -H "Authorization: Basic $tok" http://localhost:4098/__stub/stats
# アプリで切り替えたあと、送られた ModelRef を引用する
curl.exe -s -H "Authorization: Basic $tok" http://localhost:4098/__stub/stats
```

**注意**: スタブは契約の定義ではない(RUN_PLAN「スタブの abort が実サーバーと違う」)。
Q4 のエンドポイントは **`/provider` `/agent` `POST /session` `GET /session/:id`
`POST /api/session/:id/model` `POST /api/session/:id/agent` のすべてを実物 serve で往復して**
形を採ってある(docs/API_CONTRACT.md「Q4で使用する分」、証跡 `e2e-artifacts/Q4/probe/`)。
**実物で測れていないのは「切替後の1往復が成功すること」だけ**である(402 Payment Required)。

### Q4 レビュー差し戻し(2周目)で変えたところ

`GET /provider` のフィクスチャに **`capabilities` を実物と同じ形で載せた**うえで、
**cerebras の `default` を `whisper-stub-v3`(`toolcall:false` / `input.text:false`)にした**。

これは実物の再現である —— 実測(API_CONTRACT.md「`providers.default` は「おすすめ」ではない」)では
**接続済み8社のうち3社の `default` が `toolcall:false`** だった(groq→Whisper、
google/openrouter→画像生成)。**その形を出せないスタブでは、絞り込みが効いていることを
実機で確かめられない。**

観測点:

- モデルシートに `whisper-stub-v3` が**出ないこと**(検索しても0件)
- シート下部に `model-picker-excluded:1`(除外件数を画面に出している)
- **「既定」バッジがどこにも無いこと** —— 1周目は `default` を先頭へ固定して
  「既定」と表示しており、それが「チャットできないモデルを推薦する」欠陥だった

`stubModel(id, name, providerID, caps)` の第4引数で `{toolcall, inText, outText}` を上書きできる。
既定は全部 true(=従来どおりチャットに使える形)なので、**既存の証跡は壊れない**。

## Q5〜Q7 で足した分(**2026-08-28 追記**。申し送り Q7-5 の回収)

**Q4 までしか載っていなかった。** 3段ぶんの `STUB_*` が文書に無く、
「スタブに何ができるか」を知るにはソースを読むしかない状態だった ——
**証跡を再現するための文書が、再現に必要な変数を隠していた**。

### Q5(ドロワー+設定)

Q5 はスタブ側の新しい口を1つも足していない(ドロワー・設定・カラーモードは端末内の話で、
サーバーの応答を変える必要が無かった)。**「足していない」ことも記録である** ——
無いことを確かめるためにソースを読み直す羽目にならないように書いておく。

### Q6(仕上げ)

| 変数 / 口 | 既定 | 意味 |
|---|---|---|
| (endpoint) `GET /permission` | — | 未応答 permission を `PermissionRequest[]` で返す(実物 serve 1.18.21 に存在することを実測: 200 `[]`)。申し送り Q5-2「チャット再入場で permission が復帰しない」の観測用 |
| (stats) `permissionListCalls` | — | `GET /permission` を受けた回数。**アプリが取りに来たこと**を `__stub/stats` から引用する |

### Q7(差分 / VCS / 巻き戻し)

**patch は実物 serve 1.18.21 の `GET /vcs/diff?mode=git&context=3` が返したバイト列**である
(採取手順は `docs/API_CONTRACT.md` §Q7)。近似した文字列は書いていない。

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_VCS_BRANCH` | `stub-main` | `GET /vcs` の `branch` |
| `STUB_VCS_DEFAULT_BRANCH` | `stub-main` | 同 `default_branch`。**`STUB_VCS_BRANCH` と違えると TopAppBar の強調(`vcs-branch:...:off-default`)が出る** |
| `STUB_VCS_EMPTY` | (未設定) | `1` で `GET /vcs` が `{}` を返す(**required が1つも無い**という実測どおりの形。git 管理下でないディレクトリ) |
| `STUB_DIFF` | (未設定) | `1` で `GET /session/:id/diff` が非空を返す。**実物はこの環境で常に `[]`**(402 でエージェントがファイルを変更しない)なので、非空の描画はここでしか測れない |
| `STUB_DIFF_BIG` | (未設定) | `1` で 6000 行超のファイルを差分に混ぜる(**5000行の打ち切り注記** `diff-truncated:<総行数>` の観測) |
| `STUB_DIFF_BIG_LINES` | `6000` | 上の行数 |
| `STUB_DIFF_LONG` | (未設定) | `1` で1行が極端に長いファイルを混ぜる(**横スクロール到達** `diff-hscroll:<現在値>/<最大値>` の観測) |
| `STUB_DIFF_LONG_CHARS` | `400` | その1行の文字数 |
| `STUB_DIFF_NO_PATCH` | (未設定) | `1` で3件目の要素から **`patch` を落とす**(契約上 `patch` は任意)。`diff-patch-missing`(「バイナリとは限りません」)の観測 |
| `STUB_REVERT_FAIL` | `0` | 非0で `POST /revert` をそのHTTPステータスで失敗させる(409=`SessionBusyError` の観測) |
| (stats) `vcsInfoCalls` / `vcsStatusCalls` | — | `GET /vcs` / `GET /vcs/status` を受けた回数 |
| (stats) `vcsDiffQueries` / `sessionDiffQueries` | — | **撃たれたクエリそのもの**。`?messageID=` を `?messageId=` と書き間違える変異は、ここでしか見えない(実サーバーは未知のクエリキーを黙って無視する) |
| (stats) `revertCount` / `unrevertCount` / `revertFailCount` / `reverts` / `sessionReverts` | — | 巻き戻しの受信記録 |

## Q8 で足した分(2026-08-28)

**全て既定オフ/既定値は従来の挙動**(§4.4 スタブ拡張規約)。
新しい口(`/file` `/file/content` `/file/status` `/find` `/find/file` `/find/symbol`)は
**既存の口を1つも変えていない**ので、Q0〜Q7 の証跡はそのまま再現できる。

### セパレータを実物に合わせてある(**近似した形を書かない**)

実測(Windows の serve 1.18.21):

```
GET /file?path=app
[{"name":"build","path":"app\\build\\",...,"type":"directory","ignored":true}, ...]
```

**`\` 区切りで、ディレクトリは末尾に区切りが付く。** スタブが `/` 区切りで返すと、
アプリの正規化(`normalizeServerPath`)が**一度も試されないまま実機で初めて壊れる**。
`GET /find/file` の応答も実物と同じく**セパレータが混在**する。

### 変数

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_FILE_BIG` | (未設定) | `1` で `GET /file/content` が **512 KiB を超える**応答を返す。受信打ち切り(`file-truncated:<受信バイト>`)の観測用。**閾値は `FILE_CONTENT_MAX_BYTES = 524288`** |
| `STUB_FILE_DIFF` | (未設定) | `1` で `GET /file/content` が `diff`(unified diff の**文字列**)を載せる。**実物 1.18.21 は一度も返さない**(変更済みファイルでも実測でキーは `type`/`content` の2つだけ)ので、§5b スコープ2 の「変更あり」トグルと Q7 差分ビューアの再利用は**ここでしか測れない** |
| `STUB_SYMBOLS` | (未設定) | `1` で `GET /find/symbol` が結果を返す(= **索引が使える側**)。**既定オフ = 実物と同じ「常に空」**。アプリは0件のとき校正クエリ(`?query=a`)をもう1本撃ち、それも0件なら「シンボル索引が使えません」と出す。**陽性側(索引が在る)は実物では測れない**(LSP が動く serve が無い) |
| `STUB_FIND_MANY` | (未設定) | `1` で `GET /find` が**ちょうど10件**返す。実物はどんな語でも10件で打ち切り、`limit` を指定する口が spec に無い ——「これ以上あるかもしれない」の注記(`find-capped:10`)の観測用 |

### 口

| メソッド | パス | 応答 |
|---|---|---|
| GET | `/file?path=` | `FileNode[]`。**`path` を落とすと 400**(実物と同じ本文)。ツリーは `.` / `app` / `app/src` / `build` の4階層ぶん。`build` は**中身が全部 `ignored:true`**(「空」と「全部隠した」の区別を測る) |
| GET | `/file/content?path=` | `FileContent`。`image.bin` が `type:"binary"` + `encoding:"base64"` + `mimeType:"image/png"`(**base64 が画面に出ないこと**の陰性側ゲート用)。`empty.txt` は0バイト。**存在しないパスも 200 `{"type":"text","content":""}`** —— 実物と同じ(下の訂正)。ディレクトリ指定は模擬していない |
| GET | `/file/status` | **常に `[]`**。実物 1.18.21 が変更があっても `[]` を返すのに合わせた —— **食い違ったスタブ挙動を正にしない**(RUN_PLAN の一般則)。アプリはこの口を判断材料にせず、変更バッジは `GET /vcs/status` から取る |
| GET | `/find?pattern=` | ripgrep 形式の `Match[]`。**`pattern` を落とすと 400**。フィクスチャは「同じ行に2回」と「日本語を含む行」を含む —— **`start`/`end` はバイトオフセット**なので、文字として切ると日本語の行だけがずれて塗られる |
| GET | `/find/file?query=&limit=` | `string[]`。**`query` を落とすと 400**、**`limit>200` で 400**(実物と同じ本文)。セパレータは混在 |
| GET | `/find/symbol?query=` | `Symbol[]`。既定は `[]`。`STUB_SYMBOLS=1` で `Dark` / `Light` を返す |
| (stats) | `fileListQueries` / `fileContentQueries` / `findQueries` / `findFileQueries` / `findSymbolQueries` | **撃たれたクエリそのもの**。`directory` のような**任意キーは落としても 200 が返る**ので、落ちたことはここでしか見えない |

### 動作確認コマンド例(Q8)

```powershell
$tok = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:stub-pass"))
curl.exe -s -H "Authorization: Basic $tok" "http://localhost:4098/file?path=."
curl.exe -s -H "Authorization: Basic $tok" "http://localhost:4098/file"            # -> 400 Missing key at ["path"]
curl.exe -s -H "Authorization: Basic $tok" "http://localhost:4098/file/content?path=image.bin"
curl.exe -s -H "Authorization: Basic $tok" "http://localhost:4098/find?pattern=stub"
curl.exe -s -H "Authorization: Basic $tok" "http://localhost:4098/find/file?query=Theme&limit=999"  # -> 400
curl.exe -s -H "Authorization: Basic $tok" http://localhost:4098/__stub/stats
```

### 追記(Q8 の実機実測で足りないと分かった2件)

| 変数 | 既定 | 意味 |
|---|---|---|
| `STUB_TOOL_FILE` | (未設定) | `1` でツール活動 part の `state.input` を **`{filePath, description}`** にする(既定は従来どおり `{command, description}`)。§5b Q8 スコープ6「ツールカードのファイルパスをタップ → ビューア」の観測用。**既定を変えていない**のは Q2 の証跡が `input: command=ls -la, ...` を引用しているため。`STUB_TOOL_PART=1` と併用する |

**絶対パスを受け付けるようにした**(実機実測で必要になった):

`GET /find/symbol` の `location.uri` は**絶対パス**(`file:///C:/stub/app/src/Theme.kt`)なので、
シンボルからファイルを開く経路は `?path=C:/stub/app/src/Theme.kt` を撃つ。
**実物 serve は絶対パスを受け付ける**(実測):

```
GET /file/content?path=E%3A%2Fgithub%2Fopencode-android%2FAGENTS.md  -> 200 / 2,076 bytes
GET /file/content?path=E%3A%5Cgithub%5Copencode-android%5CAGENTS.md  -> 200 / 2,076 bytes
GET /file/content?path=AGENTS.md                                      -> 200 / 2,076 bytes  (同じ)
```

1周目のスタブは相対パスしか知らず **400** を返し、アプリは正しく
`empty-state:file-failed`(「読み込めませんでした / サーバーエラー(HTTP 400)」)を出した ——
**アプリの欠陥ではなくスタブの欠陥**である。`STUB_ROOT = 'C:/stub'` を足して
絶対パスを相対へ寄せた。**食い違ったスタブ挙動を正にしない**(RUN_PLAN の一般則)。

### 訂正(2026-08-28、Q8 レビュー blocker-1)

**1周目のスタブは `GET /file/content` の未知パスに 400 を返していた。その応答は実サーバーに存在しない。**
捏造したエラーボディで、しかもこの文書がそれを事実として記録していた
(「存在しないパスは 400」)——**その2行下で「食い違ったスタブ挙動を正にしない」と引用しながら。**

実測(実物 serve 1.18.21)。**3つはバイト単位で同一**(いずれも28バイト):

```
GET /file/content?path=no/such/file.txt         -> 200 {"type":"text","content":""}
GET /file/content?path=docs/DOES_NOT_EXIST.md   -> 200 {"type":"text","content":""}
GET /file/content?path=<本物の0バイトファイル>   -> 200 {"type":"text","content":""}
GET /file/content?path=docs                     -> 500 UnknownError   (ディレクトリ指定)
```

**スタブが現実より親切だったので、アプリが「0 バイト。読み込みに失敗したわけではありません」と
断言する欠陥が、スタブ由来の dump 全部で不可視だった。** スタブを実測に合わせた
(未知パスは 200 + 空本文)。アプリ側は `file-empty-or-missing`
(「中身が空のファイル」なのか「そのパスが無い」のかは区別できません)を出すようにした。


---

## Q9 で足した分(2026-08-30)

**このフェーズだけ WebSocket を模擬する。** スタブは Node 単ファイル・依存ゼロの方針なので、
RFC6455 のハンドシェイク(`Sec-WebSocket-Accept` の SHA-1)とフレーム化を
**この用途に要る分だけ**手で書いてある(`node:crypto` は標準モジュール)。
継続フレームも圧縮拡張も扱わない —— **扱う必要が出たらそれは検出器の仕事が増えた合図**である。

### 実物に合わせてある(捏造しない)

Q8 のレビュー blocker は「スタブが実サーバーに無い 400 を捏造し、テストがその誤りを固定していた」
ことだった。Q9 で模擬しているのは、**すべて 2026-08-30 に実物 serve 1.18.21 で実測した形**である
(生の応答は `docs/API_CONTRACT.md`「Q9 で使用する分」):

| 実物の振る舞い | スタブ |
|---|---|
| `connect-token` は **`x-opencode-ticket` が無いと 403** `PtyForbiddenError` | 同じ |
| チケットは**単回使用**(2本目は接続失敗) | 同じ(使い回しは 403 で弾く) |
| **メタフレームは先頭バイト `0x00` + JSON**、リプレイ本文があると本文が先 | 同じ |
| `cursor` は累積バイト数(省略/`0`=全再送、`-1`=ライブのみ、`N`=途中から) | 同じ |
| リサイズは `PUT /pty/{id}` `{size:{rows,cols}}`。直後に全画面の描き直しが流れる | 同じ |
| **終了すると `GET /pty` から消え、`GET /pty/{id}` は 404** | 同じ |
| 終了コードは **SSE `pty.exited{id, exitCode}` にしか出ない** | 同じ |
| **`DELETE` は `pty.deleted` を流し `exitCode` を持たない** | 同じ |
| `GET /pty/shells` は `{path, name, acceptable}` の配列 | 同じ(`acceptable:false` を1件混ぜてある) |

`acceptable:false` を1件入れてあるのは、アプリが「消さずに(非推奨)と印を付ける」規則を
**両方向で**確かめられるようにするためである(印を落とす変異は、印が出る候補が
1件も無いスタブでは見えない)。

### 変数(**すべて既定オフ/既定値は従来の挙動**)

| 変数 | 既定 | 何を作るか |
|---|---|---|
| `STUB_PTY_BANNER_MS` | 50 | 接続後にバナーを流すまでの待ち |
| `STUB_PTY_EXIT_CODE` | 0 | `exit` を受けたときに流す終了コード |
| `STUB_PTY_SILENT_EXIT` | off | **`pty.exited` を流さずに**終わらせる(下記) |
| `STUB_PTY_ALTSCREEN` | off | 起動直後に代替画面(`ESC[?1049h`)へ入る |
| `STUB_PTY_DROP_MS` | 0 | >0 で接続を N ms で一方的に切る |
| `STUB_PTY_NO_META` | off | メタフレームを流さない |

**`STUB_PTY_SILENT_EXIT=1` は Q9 の中核の陽性試験である。**
プロセスは終わって `GET /pty` から消えるが `pty.exited` は流れない ——
実物で「SSE が切れている間に終わった」ときに起きることを再現する。
アプリは終了コードを **0 と書いてはならず**、`pty-exited-code-unknown`
(「0 だったという意味ではありません」)を出さねばならない。

**`STUB_PTY_ALTSCREEN=1` は §5b Q9 の陰性側ゲート**(「全画面TUI を開いたとき、
壊れた画面ではなく制限の説明が出る」)を作る。アプリは `pty-notice:pty-alt-screen:N` を出す。

**`STUB_PTY_DROP_MS=3000` は再接続と `cursor` の継続を作る。**
切られた後にアプリが `connect-token` を**取り直し**、保存した `cursor` を付けて
繋ぎ直すことを `__stub/pty-stats` の `connectQueries` で確かめられる
(`cursor` が毎回 `null` なら、**再接続のたびに画面の先頭からやり直している**)。

### 口(常時有効。**既存の挙動には影響しない** —— 従来は 404 だった)

```
GET    /pty/shells
GET    /pty
POST   /pty                       {command, args, title}
GET    /pty/{id}                  (終了後は 404)
PUT    /pty/{id}                  {size:{rows,cols}}
DELETE /pty/{id}
POST   /pty/{id}/connect-token    x-opencode-ticket 必須
ws     /pty/{id}/connect?ticket=&cursor=&directory=
GET    /__stub/pty-stats          ← Q9 の観測口
```

**シェルの中身は模擬である。** `echo X` は `X` を返し、`exit` は終了し、
それ以外は「'X' は認識されていません。」を返す。**本物のシェルではない** ——
確かめたいのはプロトコル(フレーム・cursor・終了通知)であって、コマンドの実行結果ではない。

### 観測方法

```
curl -s -u opencode:stub-pass http://127.0.0.1:4098/__stub/pty-stats
```

返るもの:

- `createCalls` / `tokenCalls` / `tokenForbidden` … **ヘッダ無しで撃たれた回数**
- `connectQueries[]` … 撃たれた `ticket` / `cursor` / `directory`(**`cursor` の継続はここで見る**)
- `ticketReuseRejected` … 使い回されたチケットを弾いた回数
- `inputs[]` … WebSocket で送られてきた**生の入力**(`"U+0003"` が Ctrl+C)
- `resizes[]` … `PUT /pty/{id}` で受けた `size`
- `deletes[]` / `exits[]` … 終わり方(`exits[].silent` が `STUB_PTY_SILENT_EXIT`)
- `alive[]` / `tickets[]` … 現在の PTY とチケットの使用状況

### 動作確認コマンド例(Q9)

```
# 1) 起動して echo → exit 7 まで
STUB_PTY_EXIT_CODE=7 node e2e-stub/server.mjs

# 2) 全画面TUI の陰性側(制限の説明が出ること)
STUB_PTY_ALTSCREEN=1 node e2e-stub/server.mjs

# 3) 終了コードが取れない経路(0 と書いていないこと)
STUB_PTY_SILENT_EXIT=1 node e2e-stub/server.mjs

# 4) 再接続と cursor の継続
STUB_PTY_DROP_MS=3000 node e2e-stub/server.mjs
```

**陽性コントロールを両方向で取ること**(HANDOFF §7)。
`STUB_PTY_SILENT_EXIT` は既定オフなので、**同じ操作で `pty-exited:7` が出ること**も
先に確かめてから、オンにして `pty-exited-code-unknown` に変わることを見る。
片方だけでは「常にそう出る画面」と区別が付かない。
