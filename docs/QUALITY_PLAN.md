# 品質向上フェーズ計画(Q0〜Q6)

MVP(P0〜P5)完走後の品質向上フェーズ。目標は **「モバイル版Claudeアプリ(コード機能)と同等の操作体験を、
opencode serve の実力で実現する」** こと。実装はHARNESS.mdのプロトコル(実装→レビュー→E2Eゲート)で回す。

- 参照UI: モバイル版Claudeアプリの実スクリーンショット3枚(セッション一覧/ドロワー/設定)。
  スクショは本文書「§2 参照UI仕様」に文章化した。**エージェントはこの文章が唯一の視覚参照**である。
- 参照spec: `docs/spec/opencode-1.18.21-openapi.json`(2026-08-25に実機 `GET /doc` から採取、162パス)。
  **契約の一次ソースは実機serveの `/doc` であり、スナップショットは採取時点の証拠にすぎない。**
- 契約文書: `docs/API_CONTRACT.md` を唯一の真実とする(MVP分のみ記載)。
  各フェーズが新エンドポイントを使う前に §4 の「契約first手順」で更新する。

## 1. 現状と残債

完了: 接続設定/セッション一覧/チャットストリーミング/permission承認/abort/実物スモーク(P0〜P5)。
L3で欠陥E/F/G回収済み。

未回収の残債(本計画のどこかで閉じる):

| ID | 内容 | 行き先 |
|---|---|---|
| R1 | partを1つも持たないassistantメッセージが空バブルで描かれる(失敗とストリーミング中の区別がつかない) | Q0 |
| R2 | `GET /session` 直近100件の窓(ページング未対応、所見1-6) | Q1 |
| R3 | モデル失敗時にユーザーができることが「再試行」しかない。モデル切替はCLIしかない(P5実測: 「そのモデルが落ちている」をUI上で扱えなかった) | Q4 |
| R4 | セッション一覧の情報量が少ない(title/id/更新時刻のみ。実行状態・グルーピング無し) | Q1 |
| R5 | 応答がプレーンテキスト描画のみ(markdown/ツール活動/reasoningが見えない) | Q2 |

## 2. 参照UI仕様(スクリーンショットの文章化)

### 2.1 セッション一覧(Claudeアプリ「コード」タブ)

- TopAppBar: 左にハンバーガー(ドロワー)、中央にタイトル、右にアクションアイコン。
- **日付グループヘッダー**: リストを「今日 / 昨日 / 今週 / 先週」で区切る小見出し。
  先週より前は「8月17日」のような日付表記のヘッダーになる。ヘッダーは小さなグレー文字。
- **セッションカード**: 角丸の暗いカード。横並びで
  - leading: 角丸正方形の中に `</>` アイコン
  - タイトル: 太字・白・1行省略
  - 未読ドット: 一部カードのタイトル左に青丸(未読表示)
  - サブ行: 状態アイコン+状態ラベル(緑のリンクアイコン+「接続済み」/切断アイコン+「切断済み」)+「リモートコントロール」のラベル
  - trailing: 相対時刻(「49分」「1日」「4日」「8月17日」)
- FAB(+)が右下。タップで新規作成。
- 全体: ほぼ黒の背景(#0f0f0f系)、カードはわずかに明るい表面色、余白はタイト。

### 2.2 ナビゲーションドロワー

- スクリム付きモーダルドロワー。ヘッダーにアプリ名「Claude」。
- メニュー項目(アイコン+ラベル): 新規チャット(+) / チャット / プロジェクト / データファイル /
  コード(**選択中は角丸ピルで強調**) / Cowork / ディスパッチ。
- 「最近の項目」セクション: 最近のチャットタイトルをそのまま列挙。
- フッター: アカウントアバター+名前+設定ギア。

### 2.3 設定

- TopAppBar: ハンバーガー+「設定」+情報アイコン。
- 先頭にアカウントカード(メールアドレス+「Max」バッジ)。
- 項目は「アイコン+ラベル+現在値(グレーの小文字)」の行リスト。区切り線でグルーピング:
  - プロファイル / 請求 / 使用状況 / 機能(「4個有効」のように現在値を表示) / コネクト / 権限
  - **カラーモード(現在値「システム」)** / フォントスタイル(「デフォルト」) / 音声 / **触覚フィードバック(トグル)**

### 2.4 opencode-androidへの写像

Claudeアプリの概念をそのまま真似せず、opencodeの実力に写像する:

| Claudeアプリ | opencode-android |
|---|---|
| 「接続済み/切断済み」(リモート接続状態) | `GET /session/status` + `session.status` SSE の `idle / busy / retry` |
| 「リモートコントロール」ラベル | セッションの `directory` 短縮表示(プロジェクト識別) |
| 未読ドット | ローカルの最終閲覧時刻(DataStore)と `time.updated` の比較 |
| モデル選択(チャット上部) | `GET /provider` / `GET /agent` + 作成時model指定 or セッションモデル切替(Q4で実機検証) |
| 質問選択肢カード(question tool) | `question.asked` SSE + `POST /question/{requestID}/reply` |
| タスクリスト(todo) | `todo.updated` SSE + `GET /session/{id}/todo` |

## 3. フェーズ一覧

| フェーズ | 名前 | 主要契約追加 | 依存 |
|---|---|---|---|
| Q0 | 設計基盤と残債回収 | なし(リファクタ+テーマ) | - |
| Q1 | セッション一覧のClaude化 | `PATCH/DELETE /session/{id}`, `GET /session/status`, `GET /session` query(start/limit/search), SSE `session.status`/`session.updated`/`session.deleted` | Q0 |
| Q2 | チャット画面のリッチ化 | なし(part語彙の表示強化)+ `session.status` retry表示 | Q0 |
| Q3 | Todo + Question | `GET /session/{id}/todo`, `POST /question/{requestID}/reply`, `POST /question/{requestID}/reject`, SSE `todo.updated`/`question.asked`/`question.replied`/`question.rejected` | Q0 |
| Q4 | モデル/エージェント選択 | `GET /provider`, `GET /agent`, `POST /session` body(agent/model), PromptInput拡張, (実機検証) `POST /api/session/{id}/model` | Q2 |
| Q5 | ドロワー+設定画面 | なし(ローカル設定) | Q1 |
| Q6 | 仕上げ(a11y/空状態/最終スモーク) | なし | Q1〜Q5 |
| Q7 | 差分表示 + VCS + 巻き戻し | `GET /session/{id}/diff`, `GET /vcs`, `GET /vcs/status`, `GET /vcs/diff`, `POST /session/{id}/revert`, `POST /session/{id}/unrevert` | Q2 |
| Q8 | ファイルブラウザ + 検索 | `GET /file`, `GET /file/content`, `GET /file/status`, `GET /find`, `GET /find/file`, `GET /find/symbol` | Q7 |
| Q9 | ターミナル(PTY) | `GET /pty/shells`, `POST /pty`, `POST /pty/{id}/connect-token`, `GET /pty/{id}/connect`(WebSocket) | Q8 |

各フェーズは独立したコミット。

**~~Q2とQ3、Q4とQ5はスコープが交わらないため並列派遣も可能~~ → 訂正(2026-08-27、実行して判明)**:
**3組とも実物ではファイルが交わっていた。** Q3 の Todo/Question カードは Q2 が作り替える
`ChatScreen.kt` の中に出る。Q4 のモデル選択は Q5 が `ModalNavigationDrawer` で包む
`SessionListScreen.kt` に足される。`AppRoot.kt` は全部が触る。
**並列可否は機能名ではなくファイル単位で判断すること。** 同一ファイルへの大きな並行編集の
マージは、このリポジトリが 2026-08-25 に一度事故を起こした形である。
Q0〜Q6 は全段を直列で実行した。

**収束的なレビューとコミットは単一主体で直列に**(HARNESS.md派遣手順)。

## 4. 共通ルール(全フェーズ)

### 4.1 契約first手順

1. フェーズ冒頭で、実機serve(`scripts/serve.ps1`、ポート4097)の `GET /doc` から
   該当エンドポイント/イベント/スキーマの形を確認する。スナップショット
   (`docs/spec/opencode-1.18.21-openapi.json`)は証拠として使ってよいが、**迷ったら実機を叩く**。
2. `docs/API_CONTRACT.md` に「Qnで使用する分」として追記する(パス、メソッド、リクエスト/レスポンスの
   必須フィールド、SSEのproperties)。**推測でフィールド名を書かない**。
3. レビュアーは「契約文書 ↔ 実装 ↔ スタブフィクスチャ」の3点突合を行う。

### 4.2 フィクスチャは実物の形を貼る(4度の失敗を繰り返さない)

P3(role) / P4(metadata, PermissionRepliedEvent) / L3(session.error) の4回、
**テストフィクスチャがコードと同じ誤った前提を持ち、ユニットテスト全緑のまま実機でだけ壊れた**。

- 新規イベント/レスポンスのフィクスチャは、specスナップショットまたは実機レスポンスから**形をコピー**する。
  文字列で代用した近似を作らない。
- 実物でしか出ない語彙(実物のpart種別、errorオブジェクト形など)は、P5で採取した
  `e2e-artifacts/P5/` の記録も使う。
- テストは「振る舞いの説明の再述」ではなく**数値と同一性を検証する**(境界値・件数・キーの一致)。

### 4.3 視覚ゲート

UIを変えるフェーズでは、ゲート条件に**スクリーンショット証跡**を含める
(`e2e-artifacts/QN/` に連番保存)。「機械判定できるもの(コントラスト比・a11y属性・オーバーフロー)」と
「描画を見ないと分からないもの」をレビューチャーターで両方要求する。

### 4.4 スタブ拡張規約

- `e2e-stub/server.mjs`(Node単ファイル・依存ゼロ)を拡張する。環境変数で挙動を切り替え
  (`STUB_*`)、既存の陽性試験(未知type混入でストリームを落とさない)を壊さない。
- スタブの新機能には `run.md` に起動手順と観測方法(`__stub/stats` 等)を追記する。
- スタブは「正しく落ちること」も確認する(陽性コントロール両方向)。

### 4.5 実装エージェントへの定型文

HARNESS.md既定 + 本計画分:

- 「指示が実測と矛盾する場合は従わず、実測結果と併せて差し戻すこと」
- 「完了報告の『確認済み』欄には**測定手段と測定対象**を書く(何をどう測ったか)」
- 「検出器(lint/検証スクリプト)が『全通過・0件』を報告したら、先に検出器自身がマッチしているか疑う」
- 「変異テストの復元に `git checkout -- <file>` は使わない。バイトコピーから戻す」

### 4.6 ブロックしない項目の明示

外部要因(実物でのモデル不調、実物で発火しないイベント、エミュレータのクリップボード確認など)に
依存するゲート項目は、各フェーズに「ブロックしない」と明示する。未検証は➖と正直に記録する。

## 5. フェーズ詳細

### Q0 設計基盤と残債回収

**目的**: 以降のフェーズの土台。Claudeアプリ風の視覚言語と、機能追加に耐える構造を先に固める。

スコープ:
1. **テーマ**: Claudeアプリ風のダークテーマを既定にする(ほぼ黒背景 `#0f0f0f` 系、わずかに明るい
   サーフェス、アクセント色は現行Material動的色から固定色へ)。ライトテーマは色トークンだけ用意し、
   切替UIはQ5。`ui/theme/Theme.kt` に集約。
2. **R1回収**: partを持たないassistantメッセージは空バブルを描かない。ストリーミング中のプレースホルダ
   (busy中)と、完了後に空だった場合(→「(応答なし)」等の小さな表示)を区別する。
3. **構造整理**: `AppViewModel`(約465行)から、SSE購読とAPI呼び出しをデータ層へ移す。
   - `OpenCodeEventStream`: **アプリ起動中は常時1本のSSE接続**を保持し、イベントを購読者へ流す。
     **~~StateFlowへ~~ → 訂正(2026-08-27)**: イベントは StateFlow にしてはならない。StateFlow は
     conflate するため、購読者が一瞬でも遅れると `message.part.updated` を捨て、捨てた分が逐次描画から
     欠落する(**P3ゲート①の退行**)。イベントは「最新値」ではなく「列」なので、バッファ付き
     SharedFlow など全件・順序どおり配る手段を使う。**接続状態は最新値だけが意味を持つので StateFlow でよい**。
     この訂正は Q0 実装が実測に基づいて差し戻したもので、原文のほうが誤っていた。
     チャット画面はこれを購読する(画面出入りで接続を切らない。Q1の一覧の状態表示が常時接続を要求する)。
   - `SessionsRepository` / `ChatRepository`: REST呼び出しをVMから分離。
   - **挙動は変えない**(リファクタ)。画面・挙動の追加はしない。
4. 既存の回転耐性・重複排除ロジック(ChatModels.ktの純関数群)は**温存**する。

ゲート(機械検証可能):
- [ ] `gradlew.bat :app:assembleDebug` 成功、`:app:testDebugUnitTest` 全緑(現43件+新規)
- [ ] スタブで「part無しassistantメッセージ」を履歴に混入 → 空バブルが描画されないことを
      uiautomator dumpで確認(証跡 `e2e-artifacts/Q0/`)
- [ ] リファクタ回帰: P3相当のスタブE2E(逐次描画/idle復帰/回転)を再実行し証跡保存
- [ ] ダークテーマ起動スクショ(視覚ゲート)
- [ ] `logcat -b crash` 0件

リスク: リファクタでSSEの購読タイミングがずれるとP3の回転耐性を壊す。→ 回帰ゲートを必ず実機で。

### Q1 セッション一覧のClaude化

**目的**: 一覧をClaudeアプリ相当の情報密度にする(R2, R4)。§2.1の仕様に沿う。

契約first: `PATCH /session/{id}`(body: title/metadata/permission/time.archived)、
`DELETE /session/{id}`、`GET /session` query(`start`/`limit`/`search`/`directory`)、
`GET /session/status`(戻り: `{<sessionID>: {type: idle|retry|busy, attempt?, message?, next?}}`)、
SSE `session.status` / `session.updated` / `session.created` / `session.deleted` を
API_CONTRACT.mdへ追記。

スコープ:
1. **日付グループヘッダー**: 今日/昨日/今週/先週/それ以前は「M月d日」。`time.updated` 由来。
   グルーピングは純関数としてユニットテスト(境界: 週の起点、月をまたぐ先週)。
2. **相対時刻**: 今日内は「N分前/N時間前」、昨日は「昨日」、それ以前は「M月d日」。
3. **実行状態バッジ**: `GET /session/status` をQ0の常時SSEと組み合わせ、busy=緑ドット+「実行中」、
   retry=オレンジ+「再試行中」、idle=無表示。`session.status` イベントで逐次更新。
4. **タイトル自動更新**: `session.updated` で一覧のタイトルを反映(opencodeが自動タイトルを付ける)。
5. **ページング(R2)**: 100件窓を突破する。「一覧の末尾に到達した」ことをスタブ120件で実測。
   **~~初回 `limit=50`、スクロール末尾で `start` 続行~~ → 訂正(2026-08-27、実物serve 423件で実測)**:
   **`start` はオフセットではない。** `time.updated` の下限フィルタ(エポックミリ秒・境界含む)で、
   **より新しい方向にしか効かない**。`start=1,2,3,10,50` はすべて同じ先頭3件を返し、
   `start=<ms>` はその時刻以降の件数だけを返す。したがって `start` で古い方向へは進めない。
   R2 の「直近100件の窓」は `limit` の既定値100そのものであり、`limit=100000` は423件全部を返した。
   **ページングは `limit` を伸ばす方式で実装する。** 代償(ページごとに全件を取り直す)は
   レビューで評価すること。
6. **検索**: TopAppBarの検索アイコン→検索欄→ `GET /session?search=`。デバウンス。
7. **長押しメニュー**: 改名(PATCH title)/削除(DELETE、確認ダイアログ付き)/コピー(セッションID)。
   削除は `session.deleted` を待たず楽観更新+失敗時ロールバック。
8. **未読ドット**(任意、ブロックしない): DataStoreに「最終閲覧時刻」を保存し、`time.updated` が
   新しければ青ドット。

ゲート:
- [ ] スタブのセッションtimeを今日/昨日/今週/先週/以前に加工 → 5種ヘッダーが正しく分類される
      (dumpでヘッダーと所属カードの対応を確認、証跡 `e2e-artifacts/Q1/`)
- [ ] スタブが `session.status(busy)` を発行 → 該当カードに「実行中」バッジ、`idle` で消える
- [ ] 120件スタブでスクロール追い越し読み込み → 120件目に到達できる
- [ ] 検索欄入力 → スタブが `search` クエリを受信(スタブログ)し、結果が絞られる
- [ ] 長押し→改名 → 一覧とスタブの状態が一致 / 削除 → 一覧から消える+スタブでDELETE受信
- [ ] 回転: グルーピング・スクロール位置・展開中メニューが壊れない
- [ ] 実物スモーク1回(一覧+status表示)
- [ ] `logcat -b crash` 0件

ブロックしない: 実物serveでの `session.updated` タイトル自動更新の発火(タイミング依存)。

### Q2 チャット画面のリッチ化

**目的**: 応答を「読める」状態にする(R5)。Claudeアプリのチャット表示に寄せる。

スコープ:
1. **Markdown描画**: 見出し/箇条書き/番号付きリスト/**太字**/`インラインコード`/コードブロック(等幅+背景+
   言語ラベル)/リンク。ライブラリはCompose対応の実績あるものを選定
   (候補: `com.mikepenz:multiplatform-markdown-renderer-m3`。選定理由を報告に書く。
   ツールチェーン制約は上げない——ライブラリ追加は可)。
2. **ツール活動表示**: ToolPartを「ツール名+状態アイコン+折り畳み」の1行カードにする。
   running中はスピナー、completedはチェック、errorは赤。展開で `state.input` の要約を表示
   (長い場合は先頭N文字)。既存の `toolLabel` 文字列描画を置き換える。
3. **reasoning折り畳み**: `type:"reasoning"` のpartは「思考」の折り畳み行(既定は閉じる、タップで展開)。
4. **コピー**: メッセージ長押し→「コピー」(テキスト全体をClipboardManagerへ)。
5. **自動スクロール**: ストリーミング中は末尾追従。ユーザーが上へスクロールしたら追従解除し
   「↓ 最新へ」チップ、タップで末尾へ戻る。
6. **retry状態表示**: `session.status` の `type:"retry"`(attempt/message/nextあり)を
   入力欄上部のバナーに表示(「N秒後に再試行(2/5)」)。`next`(エポックms)からのカウントダウン。
   欠陥Gのバナーと統合し、`session.error` とretryの両方を同じバナー領域で扱う。
7. 空バブルのストリーミング中プレースホルダは「…」等の待ち表示にする(Q0と整合)。

ゲート:
- [ ] スタブがmarkdownフィクスチャ(見出し/リスト/コードブロック/太字/リンク)を返す →
      描画スクショ(視覚ゲート)。コードブロックが等幅+背景付きであること
- [ ] スタブがtool part(running→completed)を演出 → 状態変化が1行カードに反映されるスクショ2枚
- [ ] reasoning partが折り畳みで表示される(既定で閉じている)スクショ
- [ ] 長押し→「コピー」メニューが動作し、クラッシュしない(エミュレータのクリップボード実測は
      環境依存のため**ブロックしない**。コードレビューでClipboardManager経由を確認)
- [ ] ストリーミング中の末尾追従+上スクロールで「最新へ」チップが出ることを実測
- [ ] スタブが `session.status(retry)` を発行 → カウントダウン付きバナー、`idle` で消える
- [ ] 回帰: P3の逐次描画/idle/回転、P4のpermission/abortをスタブで再実行
- [ ] `logcat -b crash` 0件

リスク: markdownライブラリがBOM 2024.12.01のComposeと合わない場合がある → その場合は
ビルドを通る最も近い版を選び、理由を報告。ツールチェーン自体は上げない。

### Q3 Todo + Question(Claude Code的対話)

**目的**: opencodeエージェントの対話機能(タスクリスト/質問)をチャットに統合する。

契約first: `GET /session/{sessionID}/todo`(戻り: `Todo{content, status, priority}[]`)、
`POST /question/{requestID}/reply`(body: `{answers: string[][]}` — **質問ごとの選択ラベル配列の配列**)、
`POST /question/{requestID}/reject`、SSE `todo.updated`(`{sessionID, todos}`)、
`question.asked`(`{id:"que..", sessionID, questions: [{question, header, options:[{label, description}], multiple?, custom?}], tool?}`)、
`question.replied` / `question.rejected` を追記。

スコープ:
1. **Todoカード**: チャット末尾(または入力欄上)に現在のタスクリストを表示。
   `status` 3値(pending/in_progress/completed)をチェックボックス/スピナー/チェックで表現。
   `todo.updated` で逐次更新、`session.idle` 後も最終状態を保持。
2. **Questionカード**: `question.asked` をpermissionと同様のダイアログでなく**インラインカード**で表示
   (質問文+header+選択肢ボタン列)。選択→ `POST /question/{requestID}/reply` に
   `answers: [[選択ラベル]]` を送信。`multiple: true` は複数選択、`custom: true` は自由入力欄を追加。
   「拒否」ボタン→ `/reject`。応答後はカードを選択済み状態で残す(Claudeアプリの挙動)。
3. permissionダイアログとの同時発生: questionとpermissionが同時にpendingでも壊れない
   (独立のStateFlow、UI上は別コンポーネント)。
4. 回転耐性: questionカードの選択状態はrememberSaveable(Q4のpermissionダイアログと同じSaver方針)。

ゲート:
- [ ] スタブが `todo.updated`(pending→in_progress→completed)を演出 → 3状態の変化をスクショで確認
- [ ] スタブが `question.asked`(2択+custom有効)→ カード表示→選択→スタブがreplyを受信
      (answersの形を `__stub/stats` で確認)→カードが選択済み表示になる
- [ ] 拒否導線で `/reject` が飛ぶ
- [ ] question表示中の回転で選択状態が消えない
- [ ] permissionとquestionの同時pendingで両方表示される
- [ ] ユニットテスト: answers組立ロジック(単選/複数/custom混在)を数値で検証
- [ ] `logcat -b crash` 0件

ブロックしない: 実物serveでの `question.asked` / `todo.updated` 発火(モデルとタスク次第)。
機構はスタブで検証し、実物発火は機会があればスモークに含める。

### Q4 モデル/エージェント選択

**目的**: 「そのモデルが落ちている」をアプリ内で扱えるようにする(R3)。

契約first: `GET /provider`(戻り: `{all: [{id, name, source, env, options, models{}}], default, connected}`)、
`GET /agent`(戻り: `[{name, mode: subagent|primary|all, hidden, model?}]`)、
`POST /session` bodyの `agent`/`model{id, providerID, variant?}`、PromptInputの `model`/`agent`、
および **`POST /api/session/{sessionID}/model`**(body: `{model: ModelRef}`、v2系)を追記。

**実機検証タスク(実装の前)**:
- 既存セッションのモデルは作成時に固定か(P5実測)。`POST /api/session/{id}/model` が実物で機能するか、
  `prompt_async` の `model` 指定がper-promptで効くかを、実物serve+安いモデルで1回ずつ実測する。
- 結果をAPI_CONTRACT.mdに記録し、**機能の当て先を決める**:
  - セッションモデル切替が効く → チャットTopAppBarにモデルピル+切替シート
  - 効かない → 作成時指定+「このセッションのモデル」表示のみ(切替は新規セッション導線)
  - per-prompt modelが効く → 送信時の上書きは行わず表示のみに使う(誤送信防止)

スコープ:
1. モデル/エージェント一覧取得と表示(接続済みプロバイダを優先、`connected` を使う)。
2. 新規作成ダイアログにモデル/エージェント選択を追加(既定は `default` または無指定)。
3. 実機検証の結果に応じた切替UI(上記分岐)。
4. メッセージのメタ表示: AssistantMessageの `providerID/modelID/cost/tokens` をメッセージの
   折り畳み詳細に表示(常時は非表示、長押しメニュー「詳細」)。
5. 失敗時の導線: `session.error` バナーに「モデルを変更」ボタン(切替可能な場合)。

ゲート:
- [ ] 実機検証の結果がAPI_CONTRACT.mdに記録されている(どの経路で切替可能か、証跡付き)
- [ ] 実物で `GET /provider` / `GET /agent` を取得し、一覧UIに表示されるスクショ
- [ ] モデル指定付きでセッション作成 → 実物で1往復し、AssistantMessageの `providerID/modelID` が
      指定と一致することをサーバー側(`GET /session/{id}/message`)で確認
- [ ] 切替可能な経路を採用した場合: 切替後の1往復が成功すること(実物、安いモデル)
- [ ] `logcat -b crash` 0件

ブロックしない: レート制限中モデルからの切替成功シナリオの実物再現(時期依存)。

### Q5 ドロワー+設定画面

**目的**: アプリの外枠をClaudeアプリ相当にする(§2.2, §2.3)。

スコープ:
1. **モーダルドロワー**(SessionListScreenに `ModalNavigationDrawer`):
   - ヘッダー: 接続先ホスト名+バージョン(health由来)
   - 項目: 新規セッション(+)/セッション一覧(選択中ピル)/接続設定
   - 「最近の項目」: 一覧上位N件のタイトル(タップでチャットへ)
   - フッター: 設定(ギア)→設定画面へ
2. **設定画面**: ConnectionScreenの内容を「接続」セクションとして取り込み、以下を追加。
   - カラーモード: システム/ダーク/ライト(DataStore永続化、Q0のテーマトークンに接続)
   - 触覚フィードバック: トグル(送信/permission応答等の操作時にHapticFeedback)
   - 情報: アプリバージョン/サーバーバージョン(health)
3. TopAppBarをハンバーガー+タイトル構成に統一(一覧/設定/チャット)。

ゲート:
- [ ] ドロワーの開閉スクショ、「最近の項目」タップでチャットへ遷移する実測
- [ ] カラーモード切替 → 即時反映+プロセス強制終了→再起動後も維持(実測、証跡)
- [ ] 触覚トグルの永続化実測(ON/OFFで該当操作のHapticFeedback呼び出しが変わることは
      コードレビュー+トグル実測で確認。振動の実感は**ブロックしない**)
- [ ] 設定画面の回転耐性(既存のscreenKey回帰を壊さない)
- [ ] `logcat -b crash` 0件

### Q6 仕上げ(品質ゲート強化)

**目的**: 断片の品質を揃え、最終スモークで締める。

スコープ:
1. **a11y**: 本文コントラスト比≥4.5:1をテーマ色で機械計算(計算式をレビュアーが独立実施)。
   タッチターゲット48dp、`contentDescription` の付与漏れ検査。
2. **空状態/エラー状態の統一**: セッション0件/検索0件/メッセージ0件/接続エラー/401の
   各状態のスクショを揃え、文言と導線(再試行/設定を開く)を統一する。
3. **回帰一式**: P3〜P5+Q1〜Q5の主要シナリオをスタブで通し、実物スモークを1回:
   「新規作成(モデル指定)→プロンプト→markdown応答→todo/question(スタブでは無し/実物は機会があれば)
   →完了→一覧で状態反映」。
4. **申し送りの最終確認**: TEST_REPORTの所見ログに未回収が無いかを確認し、
   残りは「→ 次期計画」または「却下(理由)」を明記。

ゲート:
- [ ] コントラスト比の計算結果が基準を満たす(数値を報告)
- [ ] 空状態/エラー状態6種のスクショ一式(`e2e-artifacts/Q6/`)
- [ ] 最終実物スモーク1回完走+`logcat -b crash` 0件
- [ ] TEST_REPORTに全フェーズの判定マトリクスが揃っている

## 5b. Q7〜Q9 詳細(§6 からの昇格、2026-08-27)

**昇格の理由**: Q0〜Q6 の目標は「モバイル版Claudeアプリ相当の操作体験」であり、**Claude Code の中核である
「コードを読む・差分を見る・巻き戻す」が計画から丸ごと落ちていた**。§6 はそれらを「価値の判断がついた時に
別計画を立てる」として先送りしていたが、判断は付いた —— opencode serve は既にその機能を**持っている**
(実機spec 162パスに `/session/{id}/diff` `/vcs/*` `/file/*` `/find/*` `/pty/*` が存在する)。
サーバー側にある能力をクライアントが出していないだけであり、追加開発ではなく**未消費の実装**である。

契約はすべて `docs/spec/opencode-1.18.21-openapi.json` から採取した。ただし**契約の一次ソースは
実機serveの `/doc`** であり、各フェーズは着手時に §4 の契約first手順で `API_CONTRACT.md` を更新すること。

### Q7 差分表示 + VCS + 巻き戻し

**目的**: 「エージェントが何を変更したか」を端末上で読めるようにする。Claude Code との差が最も大きい部分。

契約first(採取済みの形。実機で再確認してから `API_CONTRACT.md` へ):

```
GET /session/{sessionID}/diff?messageID=&directory=&workspace=
  -> SnapshotFileDiff[] = {file, patch, additions:number, deletions:number,
                           status: "added"|"deleted"|"modified"}
GET /vcs            -> VcsInfo      = {branch, default_branch}
GET /vcs/status     -> VcsFileStatus[] = {file, additions, deletions, status}
GET /vcs/diff?mode=git|branch&context=N -> VcsFileDiff[] = {file, patch, additions, deletions, status}
POST /session/{sessionID}/revert   body {messageID:"msg..", partID?:"prt.."} -> Session
POST /session/{sessionID}/unrevert body {}                                   -> Session
```

`patch` は unified diff の**文字列**である。パーサはアプリ側で書く。

スコープ:
1. **差分ビューア**: unified patch をパースし、`@@` ハンクごとに行を色分け描画(追加=緑背景、削除=赤背景、
   文脈=既定色)。等幅フォント、**横スクロール可**(折り返さない。コードの折り返しは差分を読めなくする)。
   行番号は旧/新の2列。
2. **ファイル単位の折り畳み**: `file` + `+N -M` バッジ + `status` アイコンの見出し行。既定は折り畳み。
   大きな差分でリスト全体が固まらないよう、展開時にのみハンクを構築する。
3. **メッセージからの導線**: assistant メッセージに変更があるとき「N ファイル変更」のチップを出し、
   タップで `GET /session/{id}/diff?messageID=` の結果を差分ビューアに出す。
4. **ブランチ表示**: セッション一覧/チャットの TopAppBar に `GET /vcs` の `branch` を小さく表示。
   `default_branch` と異なる場合のみ強調する(常時表示は情報量の無駄)。
5. **作業ツリー差分**: ドロワーから「変更中のファイル」(`GET /vcs/status`)→ タップで
   `GET /vcs/diff?mode=git` の該当ファイルを差分ビューアへ。
6. **巻き戻し(revert/unrevert)**: メッセージ長押し→「ここまで戻す」。**確認ダイアログ必須**
   (ファイルシステムを書き換えるため)。実行後は `unrevert` を「元に戻す」として一定時間バナー表示。

**除外**: `POST /vcs/apply`(任意パッチの適用)。端末からの任意パッチ適用は入力手段が無く、
誤爆時の被害がリポジトリ全体に及ぶ。必要になったら独立フェーズで合意を取る。

ゲート:
- [ ] unified patch パーサのユニットテスト。**実データを貼る**こと(実物serveの `diff` 応答を1本採取して
      フィクスチャにする)。ハンクヘッダ `@@ -a,b +c,d @@`、追加のみ/削除のみのファイル、
      末尾改行なし(`\ No newline at end of file`)、**バイナリファイル**の各ケース
- [ ] **変異校正**: パーサを壊した形に戻すと該当テストが落ちることを確認(復元はバイトコピー)
- [ ] スタブが3ファイル分の `SnapshotFileDiff` を返す → チップ「3 ファイル変更」→ 展開して
      追加行/削除行が色分けされることを `uiautomator dump` のテキストで確認(証跡 `e2e-artifacts/Q7/`)
- [ ] 横スクロールで長い行の末尾に到達できる(dump の bounds で確認)
- [ ] `GET /vcs` の branch が TopAppBar に出る(実物serveで1回)
- [ ] revert: スタブで確認ダイアログ → 実行 → `__stub/stats` に revert 受信、unrevert バナー表示
- [ ] **実物での revert は本リポジトリに対して行わない**。使い捨ての一時リポジトリで serve を起こして1回だけ実測する
- [ ] `logcat -b crash` 0件

リスク: 大きな差分(数千行)の描画で LazyColumn が詰まる。→ ハンク単位で遅延構築し、
**5000行超のファイルは「大きすぎるため先頭N行のみ」と明示して打ち切る**(黙って切らない)。

### Q8 ファイルブラウザ + 検索

**目的**: 「エージェントが触っているコードを、端末から直接見る」。Q7 が差分なら Q8 は全文。

契約first:

```
GET /file?path=&directory=&workspace= -> FileNode[] = {name, path, absolute,
                                                       type:"file"|"directory", ignored:boolean}
GET /file/content?path= -> FileContent = {type:"text"|"binary", content, diff?, patch?,
                                          encoding?:"base64", mimeType?}
GET /file/status        -> File[] = {path, added:int, removed:int, status}
GET /find?pattern=      -> {path:{text}, lines:{text}, line_number, absolute_offset,
                            submatches:[{match:{text}, start, end}]}[]   ← ripgrep 形式
GET /find/file?query=&dirs=&type=&limit= -> string[]
GET /find/symbol?query= -> Symbol[] = {name, kind:int, location:{uri, range}}
```

スコープ:
1. **ツリーブラウザ**: `GET /file?path=` でディレクトリを1階層ずつ展開。`ignored:true` は既定で隠し、
   トグルで表示。パンくずで上へ戻れること。
2. **ファイルビューア**: `GET /file/content`。`type:"text"` は等幅+行番号+横スクロール。
   **`type:"binary"` は中身を描かず、mimeType とサイズだけ出す**(base64 を文字として描かない)。
   `diff`/`patch` を持つ場合は「変更あり」トグルで Q7 の差分ビューアを再利用する。
3. **全文検索**: `GET /find?pattern=` の結果を「ファイル > 行番号 > 該当行」でグループ表示。
   `submatches` の `start`/`end` でマッチ部分をハイライト。デバウンス。
4. **ファイル名検索**: `GET /find/file?query=` を検索欄の別タブに。`limit` を必ず付ける。
5. **シンボル検索**: `GET /find/symbol`(LSPが動いていない環境では空配列が返る想定)。
   **空を「無い」と描かず「シンボル索引が使えない」と区別できる表示にする**(Q0/R1と同じ原則)。
6. **チャットからの導線**: ツール活動カード(Q2)のファイルパスをタップ → そのファイルをビューアで開く。

ゲート:
- [ ] スタブがネスト2階層+`ignored:true` を含むツリーを返す → 展開・パンくず往復を dump で確認
      (証跡 `e2e-artifacts/Q8/`)
- [ ] `type:"binary"` のファイルで**base64文字列が画面に出ていないこと**を dump のテキストで確認(陰性側)
- [ ] `GET /find` の実データ形式(ripgrep JSON)でユニットテスト。**submatches の複数マッチ**、
      1行に同じ語が2回出るケースを含める。**変異校正あり**
- [ ] 検索欄入力 → スタブが `pattern` を受信(スタブログ)、結果件数が画面と一致
- [ ] シンボル検索が空配列のとき「見つからない」と「索引が無い」が区別されて表示される
- [ ] 実物serveで本リポジトリを1回ブラウズ(`app/src/main/java/.../Theme.kt` を開く)
- [ ] `logcat -b crash` 0件

リスク: `GET /file/content` に上限が無い。**巨大ファイルで OOM する**。→ 受信サイズを閾値で打ち切り、
「大きすぎるため先頭N KBのみ」と明示する。閾値と実測メモリを報告に書くこと。

### Q9 ターミナル(PTY)

**目的**: 端末から直接シェルを叩ける状態にする。**このフェーズだけ通信方式が違う(WebSocket)。**

契約first(**ここは特に実機での再確認が要る**。WebSocket のハンドシェイクは spec からは読み切れない):

```
GET  /pty/shells                    -> 利用可能なシェル一覧
POST /pty  body {command, args[], cwd, title, env{}} -> Pty = {id, title, command, args, cwd,
                                                               status:"running"|"exited", pid, exitCode}
GET  /pty                           -> Pty[]
POST /pty/{ptyID}/connect-token     -> PtyTicketConnectToken
GET  /pty/{ptyID}/connect?ticket=&cursor=   ← WebSocket アップグレード(チケット方式)
PUT  /pty/{ptyID}                   -> 更新(リサイズ等。実機で確認)
DELETE /pty/{ptyID}                 -> 終了
```

**→ 訂正(2026-08-30、実物 serve 1.18.21 で実測)**: 上の `Pty` は **spec のスキーマ**であって
**応答の形ではない**。実測では `exitCode` が埋まった応答を**一度も観測できなかった** ——
プロセスが終わると PTY は `GET /pty` の一覧から**消え**、`GET /pty/{id}` は 404 になるので、
`status:"exited"` を REST で読める窓が存在しない。詳細と生の応答は
`API_CONTRACT.md`「Q9 で使用する分」。

`POST /pty/{ptyID}/connect-token` には **spec に載っていない `x-opencode-ticket` ヘッダが必須**
(無いと 403 `PtyForbiddenError`)。これが無いとチケットが取れず、**ターミナルが一度も開かない**。

**実機検証タスク(実装の前に必ず)**:
- `connect-token` が返すチケットの寿命・使い回し可否
- `connect` が本当に WebSocket アップグレードか、それとも SSE/ロングポーリングか
- 入力の送り方(WebSocket のテキストフレームか、別エンドポイントか)
- `cursor` の意味(再接続時の再開位置か)
- **リサイズの経路**(`PUT /pty/{id}` の body に cols/rows があるか)
- 結果を `API_CONTRACT.md` に記録し、**無ければこのフェーズは「表示のみ」に縮小して合意を取る**

**→ 実施済み(2026-08-30)。5問すべてに答えが出たので縮小しない**
(`API_CONTRACT.md`「Q9 で使用する分」に生の応答つきで記録):

| 問い | 実測 |
|---|---|
| チケットの寿命・使い回し | `expires_in: 60`。**単回使用**(同じチケットの2本目は接続に失敗) |
| 本当に WebSocket か | **101 Switching Protocols**。SSE でもロングポーリングでもない |
| 入力の送り方 | **同じ WebSocket に生の端末入力を書く**。別エンドポイントは無い。Ctrl+C は `U+0003` 1文字 |
| `cursor` の意味 | 出力先頭からの**累積バイト数**。省略/`0`=全再送、`-1`=ライブのみ、`N`=途中から |
| リサイズの経路 | **`PUT /pty/{id}` body `{size:{rows,cols}}`**。WS 経由ではない |

**着手時の申し送りのうち2件は実測が覆した**(報告に併記):
`connect-token` と `connect` の `directory` はバインドされない(3通りとも 101)。
v1(`/pty`)と v2(`/api/pty`)は**別の登録簿**で、v2 から v1 の PTY は見えない。

スコープ:
1. OkHttp WebSocket で PTY に接続し、出力を等幅ビューへ追記。**末尾追従+上スクロールで追従解除**(Q2と同じ規則)。
2. **ANSI エスケープの扱い**: 完全な端末エミュレータは作らない。**SGR(色・太字)だけ解釈し、
   カーソル移動・画面消去・代替画面は解釈せずに落とす。** そのため `vim` / `top` 等の
   全画面TUIは**動かない**。これを「制限」としてUIに明示する(空白の画面を出して黙らない)。
3. 入力: 1行入力欄 + 送信。加えて Ctrl+C / Tab / ↑↓ の補助キー行。
4. セッション管理: `GET /pty` の一覧、`DELETE` で終了、~~`status:"exited"` と `exitCode` の表示~~。
   **→ 訂正(2026-08-30、実測)**: `exitCode` は **REST からは取れない**(上の訂正)。
   **唯一の出所は SSE の `pty.exited{id, exitCode}`** である。しかも
   `DELETE /pty/{id}` は `pty.deleted` を流し **`exitCode` を持たない**ので、
   **終了コードを持たない終了が2種類ある**(SSE が切れている間に終わった / 自分で削除した)。
   **どちらも 0 と書いてはならない** —— 0 は「正常終了した」という別の主張である。
   アプリは3状態に割る(`pty-exited:<code>` / `pty-exited-code-unknown` / `pty-deleted`)。
   これは §5 の「無い」と「取れなかった」の区別の Q9 における形である。
5. 画面回転・プロセス復帰での再接続(`cursor` を使った再開が可能なら使う)。
   **→ 補足(2026-08-30、実測)**: 使える。ただし**メタフレームの `cursor` は接続時に
   1回だけ流れ、以後更新されない**。メタの値だけを保存して再接続すると、
   **接続してから今までの出力を全部もう一度読む**(症状は「回転のたびに画面の先頭から
   やり直す」で、クラッシュもエラーも出ない)。**出力フレームのバイト数で自分で進めること。**

ゲート:
- [ ] 実機検証タスクの結果が `API_CONTRACT.md` に**証跡付きで**記録されている
      (curl / websocat の生出力を引用。無ければこのフェーズは着手しない)
- [ ] SGRパーサのユニットテスト: 色・太字・リセット・**未知のエスケープを落とす**こと。**変異校正あり**
- [ ] 実物serveで `echo hello` を1回通し、出力が画面に出ることを dump で確認(証跡 `e2e-artifacts/Q9/`)
- [ ] `exit 7` で **SSE `pty.exited{exitCode:7}`** が届き、帯に `pty-exited:7` が出る
      (**`GET /pty` は同時に空になる**ので、REST を根拠にしないこと)
- [ ] **SSE を切ったまま終わらせた場合**に `pty-exited-code-unknown` が出て、
      画面に「0 だったという意味ではありません」が出る(陰性側。**0 と書かない**)
- [ ] `DELETE` で終わらせた場合は `pty-deleted` が出る(終了コードを待たない)
- [ ] 回転で接続が切れない、または切れても再接続して続きが読めること
- [ ] 全画面TUI(`top` 等)を開いたとき、**壊れた画面ではなく制限の説明が出る**(陰性側)
- [ ] `logcat -b crash` 0件

**セキュリティ上の注記**: PTY は任意コマンド実行である。opencode serve が無認証で起動できることは
P5 で実測済み(`OPENCODE_SERVER_PASSWORD` 未設定なら 200 が返る)。**Q9 は Tailscale 等の
プライベートネットワーク内でのみ使う前提**を README に明記し、アプリ内にも接続時の注意を出す。
この前提を変える(公開ネットワークで使う)なら別途設計合意が要る。

## 6. 拡張候補(2026-08-27 に一部を Q7〜Q9 へ昇格。残りは本計画の外)

**昇格済み(2026-08-27、§5b で計画化)**: VCS表示 / 差分表示 / revert / ファイルブラウザ / 検索 / PTY。
「差分表示は描画コストが高い」「PTYストリームは別設計が要る」という当初の理由は**除外の理由ではなく
設計項目**だったので、§5b の各フェーズにリスクと打ち切り規則として書き下した。

以下は依然として本計画の外。価値の判断がついた時に別計画を立てる:

- 共有: `POST /session/{id}/share` / `DELETE /session/{id}/share`(share.urlのコピー)
- fork / summarize(`POST /session/{id}/fork` 等)
- コマンド: `GET /command` / `POST /session/{id}/command` / `POST /session/{id}/shell`
  (Q9 の PTY と役割が重なる。Q9 の実測後に必要性を判断する)
- マルチディレクトリ: `?directory=` 切替(現在はserve起動cwdに固定)。
  **ただし Q7〜Q9 の全エンドポイントが `directory` クエリを取る**ため、
  データ層は最初から `directory` を通せる形にしておくこと(UIは出さない)
- v2ストリーミングイベント(`session.next.*`)への移行(現行legacy系で十分)
- `POST /vcs/apply`(任意パッチ適用。Q7 で明示的に除外した。理由は §5b Q7)

## 7. 制約(変更しないもの)

- ツールチェーン: Gradle 8.14.4 / AGP 8.13.2 / Kotlin 2.4.10 / compileSdk 36(AGENTS.md)。
  **ライブラリ追加は可、ツールチェーン更新は不可。**
- `usesCleartextTraffic=true` 維持(Tailscale内平文HTTP方針)。
- シークレット: `OPENCODE_SERVER_PASSWORD` の値をソース・テスト・ログに書かない。
  スタブの `stub-pass` はダミー値なので例外。
- 実物serveを使うのは各フェーズのスモークとQ4の検証のみ(LLM消費を抑える)。機構検証はスタブ。
- 完了条件は「実機/エミュレータで自分の変更を動かした」まで(AGENTS.md)。
