#!/usr/bin/env node
/**
 * opencode serve スタブ(P3 チャットストリーミング検証用)。Node.js 単ファイル・依存ゼロ。
 *
 * docs/API_CONTRACT.md のMVP使用分のみを模擬する:
 *   GET  /global/health              -> {healthy, version}
 *   GET  /session                   -> Session[]
 *   POST /session {title}           -> Session(メモリ内に作成)
 *   DELETE /session/:id             -> 204
 *   GET  /session/:id/message       -> {info,parts}[](履歴)
 *   POST /session/:id/prompt_async  -> 204(受信をトリガーにSSEで分割テキスト更新を遅延流し)
 *   GET  /event                     -> SSE(envelope {id,type,properties})
 *
 * SSEの演出: プロンプト受信 -> ユーザーメッセージのpart更新エコー ->
 * assistantメッセージの message.updated -> 同一part.idのtextを
 * (STUB_CHUNK_MS間隔で)チャンク累積更新 x N回 -> 未知typeイベント混入 ->
 * session.idle。完了した1往復はメモリ履歴に追記される(プロセス死後の再取得検証用)。
 *
 * 検証補助(要Basic認証):
 *   POST /__stub/drop-events : SSE接続を全切断(アプリ側の再接続観測用)
 *   GET  /__stub/stats       : 接続数/プロンプト数などの内部状態
 *
 * 起動手順は e2e-stub/run.md を参照。
 */
import http from 'node:http';
// Q9: WebSocket のハンドシェイク(Sec-WebSocket-Accept)に要る。**依存ゼロの方針は保つ**(標準モジュール)。
import crypto from 'node:crypto';

const PORT = Number(process.env.STUB_PORT || 4098);
const USER = 'opencode';
// ダミー認証情報(ローカルスタブ専用。実物serveのOPENCODE_SERVER_PASSWORDとは無関係)
const PASSWORD = process.env.STUB_PASSWORD || 'stub-pass';
const CHUNK_MS = Number(process.env.STUB_CHUNK_MS || 500);
const CHUNKS = (process.env.STUB_CHUNKS || 'チャンク1|チャンク2|チャンク3').split('|');
/**
 * Q6: PATCH(改名)の応答を遅らせる。既定0(遅らせない)。
 *
 * **観測のためだけの口**である。申し送り Q5-1 で足した「改名/削除の往復中を行に描く」
 * (`SessionsUi.pendingActionId`)は、ローカルスタブ相手だと往復が数msで終わるので
 * `uiautomator dump` の窓に入らない —— 計測手段が窓に入れないだけで実装は在る、という
 * Q4-1 とまったく同じ形になる。遅らせれば**実機で実際に見える**ようになる。
 */
const PATCH_DELAY_MS = Number(process.env.STUB_PATCH_DELAY_MS || 0);
/** P4: permission.asked を出すか。既定オフ——P3の観測を邪魔しないため、必要な回だけ有効にする。 */
const WANT_PERMISSION = process.env.STUB_PERMISSION === '1';
/** 応答が来なくても永久に止まらないための上限。タイムアウトも「応答なし」という観測結果として扱う。 */
const PERM_TIMEOUT_MS = Number(process.env.STUB_PERM_TIMEOUT_MS || 120000);
/**
 * Q0/R1: 「描くものが無い assistant メッセージ」を履歴に混ぜるか。既定オフ——
 * 既存のP3/P4観測(履歴は1往復ちょうど)を変えないため、必要な回だけ有効にする。
 * 有効時、ses_stub_0001 の履歴に2件足す:
 *   - part を1つも持たない assistant
 *   - ライフサイクル part(step-start / step-finish)だけの assistant
 */
const WANT_EMPTY_ASSISTANT = process.env.STUB_EMPTY_ASSISTANT === '1';
/**
 * Q0/R1: prompt_async の演出で、テキストチャンクより先に step-start part を流すか。
 * 既定オフ。有効時は「まだ描くものが無い assistant バブル」が実物と同じ順序で立つので、
 * ストリーミング中のプレースホルダと完了後の「応答なし」を同一画面で見分けられる。
 */
const WANT_STEP_START = process.env.STUB_STEP_START === '1';

/**
 * Q1: 生成する追加セッションの件数。**既定0** —— 既存のP3/P4/Q0の観測(一覧は固定2件)を
 * 変えないため。ページング検証は `STUB_SESSIONS=120` で行う。
 */
const EXTRA_SESSIONS = Number(process.env.STUB_SESSIONS || 0);
/**
 * Q1: 週の起点(0=日曜)。`WeekFields.of(Locale.JAPAN).firstDayOfWeek` も
 * `Locale.US` も SUNDAY なので既定0。日付グループの期待値計算にだけ使う。
 */
const WEEK_START = Number(process.env.STUB_WEEK_START ?? 0);
/**
 * Q1: DELETE を失敗させる(楽観更新のロールバックを観測するため)。
 * 値はそのままHTTPステータスとして返す。**既定0=失敗させない**。
 */
const DELETE_FAIL_STATUS = Number(process.env.STUB_DELETE_FAIL || 0);
/**
 * Q1: `GET /session` の `limit` 既定値。実サーバーの実測値が100なのでそれに合わせる
 * (API_CONTRACT.md「`start` / `limit` / `search` の意味」)。
 */
const LIST_DEFAULT_LIMIT = Number(process.env.STUB_LIST_DEFAULT_LIMIT || 100);
/**
 * Q1: prompt_async の演出中に `session.status` を busy/idle で流すか。**既定オフ** ——
 * 有効にすると `eventsSent` が増え、P3/P4 の証跡が引用している件数が変わってしまう。
 */
const WANT_STATUS_ON_PROMPT = process.env.STUB_STATUS_ON_PROMPT === '1';

/* ---- Q2 で追加した切り替え。**すべて既定オフ / 既定値は従来の挙動** ---- */

/**
 * Q2: 応答本文を markdown フィクスチャにする。既定オフ ——
 * 有効にすると `STUB_CHUNKS` の代わりに markdown が流れ、P3 の証跡が引用している
 * 「チャンク1チャンク2チャンク3」が出なくなる。
 * 見出し / 箇条書き / 番号付き / 太字 / インラインコード / コードブロック(言語つき) / リンクを含む。
 */
const WANT_MARKDOWN = process.env.STUB_MARKDOWN === '1';

/**
 * Q2: ツール活動 part を running → completed で演出する。既定オフ。
 * shape は spec の required を満たす(ToolPart: id/sessionID/messageID/type/callID/tool/state、
 * ToolStateRunning: status/input/time{start}、ToolStateCompleted: status/input/output/title/metadata/time{start,end})。
 */
const WANT_TOOL_PART = process.env.STUB_TOOL_PART === '1';
/** STUB_TOOL_FILE=1: ツール入力を `filePath` を持つ形にする(Q8 スコープ6)。 */
const WANT_TOOL_FILE = process.env.STUB_TOOL_FILE === '1';

/**
 * Q2: reasoning part を流す。既定オフ。
 * shape は ReasoningPart の required(id/sessionID/messageID/type/text/time{start})。
 */
const WANT_REASONING = process.env.STUB_REASONING === '1';

/**
 * Q2: テキストの前に `session.status{retry}` を1回流し、[STUB_RETRY_MS] 後に busy へ戻す。既定オフ。
 * `action`(任意・required 5項目)も載せる。
 */
const WANT_RETRY = process.env.STUB_RETRY === '1';
const RETRY_MS = Number(process.env.STUB_RETRY_MS || 8000);

/**
 * Q2: abort に対して `session.status{idle}` + `session.idle` を流す。**既定オフ**。
 *
 * 既定オフにしているのは既存E2E(P4ゲート②/Q0)が「abort後に `eventsSent` が不変」を
 * 引用しているため。ただし**実物 serve は流す**(2026-08-27 実測、
 * `POST /abort` の直後に `session.status{"type":"idle"}` と `session.idle`)。
 * つまり既定オフのほうが実サーバーと食い違っている。
 * 詳細は docs/API_CONTRACT.md「abort が流すもの」。
 */
const WANT_ABORT_IDLE = process.env.STUB_ABORT_IDLE === '1';

/**
 * Q2: `msgSeq` の初期値。既定0(従来どおり)。
 *
 * スタブを再起動すると `msg_u1` / `msg_a2` が**再利用**され、アプリのメモリ上に残っている
 * 同じIDのメッセージへ part が合流する(申し送り Q1-2 が観測した「二重描画」の正体)。
 * 実サーバーのIDは再利用されないので、この人工条件を避けたい回はここをずらす。
 */
// (実体は下の `let msgSeq` の初期値)

/* ---- Q3 で追加した切り替え。**すべて既定オフ / 既定値は従来の挙動** ---- */

/**
 * Q3: prompt_async の演出中に `todo.updated` を pending → in_progress → completed で流す。**既定オフ**。
 * 有効にすると `eventsSent` が増え、P3/P4/Q0 の証跡が引用している件数が変わる。
 * shape は spec の `Todo`(required: `content` `status` `priority` の3つ)を満たす。
 */
const WANT_TODO = process.env.STUB_TODO === '1';

/**
 * Q3: `question.asked` を流し、reply / reject が来るまで待つ。**既定オフ**。
 * 既定の質問は **2択 + `custom: true`**(計画書 §5 Q3 のゲート文言そのもの)。
 * `QuestionOption` の required は `label` **と `description`** の両方なので両方載せる。
 */
const WANT_QUESTION = process.env.STUB_QUESTION === '1';

/**
 * Q3: 2問目(`multiple: true`)を足して、単選 / 複数 / custom の混在を作る。**既定オフ**。
 * `STUB_QUESTION=1` と併用する(単独では効かない)。
 */
const WANT_QUESTION_MULTI = process.env.STUB_QUESTION_MULTI === '1';

/** Q3: 応答が来なくても永久に止まらないための上限。 */
const QUESTION_TIMEOUT_MS = Number(process.env.STUB_QUESTION_TIMEOUT_MS || 120000);

/**
 * Q3: `question.asked` の type 名を v2(`question.v2.asked`)にする。**既定オフ**。
 *
 * **実物 serve でどちらが流れるかは観測できていない**(アカウントが 402 を返し推論が回らない)。
 * spec は両方を `Event` union に持ち、shape は完全に同一である。
 * アプリは両方を受ける実装にしたので、**その主張をスタブ側から検証できるようにする**。
 */
const WANT_QUESTION_V2 = process.env.STUB_QUESTION_V2 === '1';

/**
 * Q4: `GET /provider` を「接続済みプロバイダ0件」にする(既定オフ)。
 * 空状態(「選べるモデルがありません」)の陽性コントロール。
 */
const WANT_PROVIDER_EMPTY = process.env.STUB_PROVIDER_EMPTY === '1';

/**
 * Q4: `POST /api/session/:id/model` を指定ステータスで失敗させる(既定 0 = 成功)。
 * 切替失敗が送信エラー帯へ出ることの陰性側。
 */
const MODEL_SWITCH_FAIL = Number(process.env.STUB_MODEL_SWITCH_FAIL || 0);

/**
 * Q4: `session.next.model.switched` を流さない(既定オフ = 実サーバーと同じく流す)。
 * **サーバーが状態の権威**であること —— イベントが来なくても `GET /session/:id` の
 * 取り直しでピルが正しくなること —— を測るための陽性コントロール。
 */
const WANT_NO_SWITCH_EVENT = process.env.STUB_NO_SWITCH_EVENT === '1';

/**
 * Q4: assistant の `message.updated` に `providerID` / `modelID` / `cost` / `tokens` を載せる
 * (既定オフ)。実サーバーは**常に**載せるが、既定を変えると P3/Q0/Q2 の証跡が参照している
 * `info` の形が変わるため、切り替え式にしてある。**値はセッションの現在のモデル由来**
 * —— 実物と同じく「その往復で実際に使われたモデル」を表す。
 */
const WANT_MESSAGE_META = process.env.STUB_MESSAGE_META === '1';

// ---- Q7: 差分 + VCS + 巻き戻し ----
/** `GET /vcs` が返す branch。既定は default_branch と同じ(=強調しない)。 */
const VCS_BRANCH = process.env.STUB_VCS_BRANCH || 'stub-main';
const VCS_DEFAULT_BRANCH = process.env.STUB_VCS_DEFAULT_BRANCH || 'stub-main';
/** `STUB_VCS_EMPTY=1` で `GET /vcs` が `{}` を返す(branch が無いサーバーの形)。 */
const WANT_VCS_EMPTY = process.env.STUB_VCS_EMPTY === '1';
/** `STUB_DIFF=1` で履歴に `patch` パートを足し、`GET /session/:id/diff` が3件返す。 */
const WANT_DIFF = process.env.STUB_DIFF === '1';
/** `STUB_DIFF_BIG=1` で 5000行超のファイルを1件足す(打ち切り表示の観測用)。 */
const WANT_DIFF_BIG = process.env.STUB_DIFF_BIG === '1';
const BIG_DIFF_LINES = Number(process.env.STUB_DIFF_BIG_LINES || 6000);
/** `STUB_DIFF_LONG=1` で1行が非常に長いファイルを1件足す(横スクロールの観測用)。 */
const WANT_DIFF_LONG = process.env.STUB_DIFF_LONG === '1';
const LONG_LINE_CHARS = Number(process.env.STUB_DIFF_LONG_CHARS || 400);
/**
 * `STUB_DIFF_NO_PATCH=1` で `GET /session/:id/diff` の3件目から **`patch` を落とす**。
 *
 * **`patch` は契約上「任意」である**(実機 `/doc` の required は
 * `SnapshotFileDiff` が `additions`/`deletions` だけ)。現行 serve はバイナリにも patch を返すので
 * この形は実物では出せないが、**契約が許す入力**なのでスタブが作れる必要がある。
 * Q7 レビュー minor-1(patch 欠落を「バイナリ」と誤表示していた)の観測用。
 */
const WANT_DIFF_NO_PATCH = process.env.STUB_DIFF_NO_PATCH === '1';
/** `STUB_REVERT_FAIL=409` で revert を失敗させる(SessionBusyError の経路)。 */
const REVERT_FAIL_STATUS = Number(process.env.STUB_REVERT_FAIL || 0);

let vcsInfoCalls = 0;
let vcsStatusCalls = 0;
let revertCount = 0;
let unrevertCount = 0;
let revertFailCount = 0;
const REVERTS = [];
// ---------------------------------------------------------------------------
// Q8: ファイルブラウザ + 検索
//
// **セパレータは実物に合わせる。** 実測(Windows の serve 1.18.21):
//   GET /file?path=app -> [{"name":"build","path":"app\\build\\",...,"type":"directory","ignored":true}, ...]
// つまり **`\` 区切りで、ディレクトリは末尾に区切りが付く**。
// スタブが `/` 区切りで返すと、アプリの正規化(normalizeServerPath)が
// **一度も試されないまま実機で初めて壊れる**。近似した形を書かない、の一例である。
// ---------------------------------------------------------------------------

/** STUB_FILE_BIG=1: `/file/content` が閾値(512 KiB)を超える応答を返す(打ち切りの観測)。 */
const WANT_FILE_BIG = process.env.STUB_FILE_BIG === '1';
/** STUB_FILE_DIFF=1: `/file/content` が `diff`(unified diff 文字列)を載せる。
 *  **実物 1.18.21 は一度も返さない**(実測)ので、「変更あり」トグルはここでしか測れない。 */
const WANT_FILE_DIFF = process.env.STUB_FILE_DIFF === '1';
/** STUB_SYMBOLS=1: シンボル索引が「使える」側にする(校正クエリが非0を返す)。
 *  **既定オフ = 実物と同じ「常に空」**。区別の陰性側が既定で測れる。 */
const WANT_SYMBOLS = process.env.STUB_SYMBOLS === '1';
/** STUB_FIND_MANY=1: `/find` がサーバー上限(10件)ちょうどを返す(打ち切り注記の観測)。 */
const WANT_FIND_MANY = process.env.STUB_FIND_MANY === '1';

/** ツリーのフィクスチャ。**2階層 + ignored:true** をゲートが要求している。 */
const FILE_TREE = {
  '.': [
    { name: 'app', path: 'app\\', absolute: 'C:\\stub\\app', type: 'directory', ignored: false },
    { name: 'build', path: 'build\\', absolute: 'C:\\stub\\build', type: 'directory', ignored: true },
    { name: 'README.md', path: 'README.md', absolute: 'C:\\stub\\README.md', type: 'file', ignored: false },
    { name: 'image.bin', path: 'image.bin', absolute: 'C:\\stub\\image.bin', type: 'file', ignored: false },
    // **0バイトのファイル**。実物は「存在しないパス」にも同じ応答を返すので、
    // アプリはこの2つを区別できない —— それを画面で確かめるための行(レビュー blocker-1)。
    { name: 'empty.txt', path: 'empty.txt', absolute: 'C:\\stub\\empty.txt', type: 'file', ignored: false },
  ],
  'app': [
    { name: 'src', path: 'app\\src\\', absolute: 'C:\\stub\\app\\src', type: 'directory', ignored: false },
    { name: 'build', path: 'app\\build\\', absolute: 'C:\\stub\\app\\build', type: 'directory', ignored: true },
    { name: 'build.gradle.kts', path: 'app\\build.gradle.kts', absolute: 'C:\\stub\\app\\build.gradle.kts', type: 'file', ignored: false },
  ],
  'app/src': [
    { name: 'Theme.kt', path: 'app\\src\\Theme.kt', absolute: 'C:\\stub\\app\\src\\Theme.kt', type: 'file', ignored: false },
    { name: 'big.py', path: 'app\\src\\big.py', absolute: 'C:\\stub\\app\\src\\big.py', type: 'file', ignored: false },
  ],
  // 中身が全部 ignored のディレクトリ。**「空」と「全部隠した」の区別**を測る。
  'build': [
    { name: 'tmp', path: 'build\\tmp\\', absolute: 'C:\\stub\\build\\tmp', type: 'directory', ignored: true },
    { name: 'out.jar', path: 'build\\out.jar', absolute: 'C:\\stub\\build\\out.jar', type: 'file', ignored: true },
  ],
};

/** `/file/content` のフィクスチャ。キーは正規化後のパス。 */
const FILE_CONTENT = {
  'README.md': { type: 'text', content: '# stub repo\n\nline two\nline three\n' },
  'app/src/Theme.kt': {
    type: 'text',
    content: 'package stub\n\nval Dark = 0x0F0F0F\nval Light = 0xFFFFFF\n' +
      '// この行は横に長い: ' + 'x'.repeat(300) + '\n',
  },
  'app/src/big.py': { type: 'text', content: Array.from({ length: 40 }, (_, i) => `def f${i}(): return ${i}`).join('\n') + '\n' },
  'app/build.gradle.kts': { type: 'text', content: 'plugins {}\n' },
  // **base64 を画面に出さないこと**を測るための1件(§5b の陰性側ゲート)。
  'image.bin': {
    type: 'binary',
    content: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
    encoding: 'base64',
    mimeType: 'image/png',
  },
  'empty.txt': { type: 'text', content: '' },
};

/** `/find` のフィクスチャ。**`start`/`end` はバイトオフセット**(実物と同じ)。 */
const FIND_FIXTURE = [
  {
    path: { text: 'app\\src\\Theme.kt' },
    lines: { text: 'val Dark = 0x0F0F0F\n' },
    line_number: 3,
    absolute_offset: 15,
    submatches: [{ match: { text: 'Dark' }, start: 4, end: 8 }],
  },
  {
    // **同じ行に2回**(ゲートが要求している形)。
    path: { text: 'README.md' },
    lines: { text: 'stub and stub again\n' },
    line_number: 3,
    absolute_offset: 20,
    submatches: [
      { match: { text: 'stub' }, start: 0, end: 4 },
      { match: { text: 'stub' }, start: 9, end: 13 },
    ],
  },
  {
    // **日本語を含む行**。`start`/`end` がバイトなので、文字として切ると別の場所になる。
    path: { text: 'docs\\NOTE.md' },
    lines: { text: '1. **差分ビューア**: unified patch\n' },
    line_number: 9,
    absolute_offset: 42,
    submatches: [{ match: { text: '差分ビューア' }, start: 5, end: 23 }],
  },
];

const FILE_LIST_QUERIES = [];
const FILE_CONTENT_QUERIES = [];
const FIND_QUERIES = [];
const FIND_FILE_QUERIES = [];
const FIND_SYMBOL_QUERIES = [];

/**
 * このスタブが名乗る「リポジトリのルート」。`FileNode.absolute` と
 * `Symbol.location.uri` がこの下を指す。
 *
 * **実物 serve は絶対パスも受け付ける**(実測: `?path=E:/github/opencode-android/AGENTS.md`
 * も `?path=AGENTS.md` も同じ 2,076 バイトを返した)。シンボル検索から開く経路は
 * `location.uri`(= 絶対パス)を渡すので、**スタブが絶対パスを 400 にすると
 * スタブの都合が契約になる**(RUN_PLAN の一般則)。ここで相対へ寄せる。
 */
const STUB_ROOT = 'C:/stub';

/** サーバー由来の `\` 区切りを1つの表現に寄せる(アプリ側と同じ規則)。 */
function normalizeStubPath(raw) {
  if (!raw) return '.';
  let v = String(raw).replace(/\\/g, '/');
  while (v.length > 1 && v.endsWith('/')) v = v.slice(0, -1);
  while (v.startsWith('./')) v = v.slice(2);
  if (v === '' || v === '.' || v === '/') return '.';
  // **絶対パスを相対へ寄せる**(実物が絶対パスを受けるのに合わせる)。
  if (v.toLowerCase().startsWith(STUB_ROOT.toLowerCase() + '/')) v = v.slice(STUB_ROOT.length + 1);
  else if (v.toLowerCase() === STUB_ROOT.toLowerCase()) v = '.';
  return v;
}

const VCS_DIFF_QUERIES = [];
const SESSION_DIFF_QUERIES = [];

/**
 * Q4: 実物 serve を模した provider カタログ。**`connected` は `all` の部分集合**で、
 * `default` は connected でないプロバイダにも載る(実測 2026-08-27。API_CONTRACT.md)。
 * `Model` の required 11個をすべて持たせてある —— 近似した形を流さないため。
 */
function stubModel(id, name, providerID, caps = {}) {
  // Q4 レビュー major-2: **`toolcall:false` のモデルを混ぜられるようにする。**
  // 実物の `default` は connected 8社のうち3社が `toolcall:false` だった
  // (groq=Whisper、google/openrouter=画像生成)。**その形を再現できないスタブでは、
  // 絞り込みが効いていることを実機で確かめられない。**
  const toolcall = caps.toolcall !== undefined ? caps.toolcall : true;
  const inText = caps.inText !== undefined ? caps.inText : true;
  const outText = caps.outText !== undefined ? caps.outText : true;
  return {
    id, providerID, name,
    api: { id, url: '', npm: '@ai-sdk/stub' },
    capabilities: {
      temperature: true, reasoning: false, attachment: false, toolcall,
      input: { text: inText, audio: !inText, image: false, video: false, pdf: false },
      output: { text: outText, audio: false, image: !outText, video: false, pdf: false },
      interleaved: false,
    },
    cost: { input: 0.4, output: 2, cache: { read: 0, write: 0 } },
    limit: { context: 262144, output: 262144 },
    status: 'active', options: {}, headers: {}, release_date: '2025-08-12', variants: {},
  };
}

const PROVIDERS = {
  all: [
    {
      id: 'mistral', name: 'Mistral', source: 'api', env: ['MISTRAL_API_KEY'], options: {},
      models: {
        'mistral-medium-latest': stubModel('mistral-medium-latest', 'Mistral Medium', 'mistral'),
        'mistral-small-latest': stubModel('mistral-small-latest', 'Mistral Small', 'mistral'),
      },
    },
    {
      id: 'cerebras', name: 'Cerebras', source: 'api', env: ['CEREBRAS_API_KEY'], options: {},
      models: {
        'gpt-oss-120b': stubModel('gpt-oss-120b', 'GPT OSS 120B', 'cerebras'),
        // **実物の groq/whisper-large-v3-turbo と同じ形**(toolcall:false / input.text:false)。
        // かつ `default` に指定してある —— 1周目はこれが「既定」バッジ付きで最上位に出ていた。
        'whisper-stub-v3': stubModel('whisper-stub-v3', 'Whisper Stub V3', 'cerebras', {
          toolcall: false, inText: false,
        }),
      },
    },
    {
      // **connected に居ない**プロバイダ。実物では203件中195件がこちら側である。
      id: 'anthropic', name: 'Anthropic', source: 'api', env: ['ANTHROPIC_API_KEY'], options: {},
      models: { 'claude-x': stubModel('claude-x', 'Claude X', 'anthropic') },
    },
  ],
  default: {
    mistral: 'mistral-small-latest',
    // **実物と同じく、`default` がチャットに使えないモデルを指す形**を再現する
    // (実測: groq -> whisper-large-v3-turbo、google/openrouter -> 画像生成)。
    cerebras: 'whisper-stub-v3',
    anthropic: 'claude-x',
  },
  connected: ['cerebras', 'mistral'],
};

/**
 * Q4: 実物 `GET /agent` を模す。**`hidden` の3通り**(true / 明示 null / キーごと無し)を
 * 実物と同じように混ぜてある(実測: orchestrator は `hidden: null`、explore はキーが無い)。
 * `permission` は spec の PermissionRuleset だが**実物では配列**だった。そのまま配列で返す。
 */
const AGENTS = [
  { name: 'build', description: '既定のエージェント', mode: 'primary', native: true, options: {},
    permission: [{ permission: '*', pattern: '*', action: 'allow' }] },
  { name: 'plan', description: '計画のみ', mode: 'primary', native: true, hidden: null, options: {},
    permission: [{ permission: '*', pattern: '*', action: 'allow' }] },
  { name: 'orchestrator', description: '総合', mode: 'primary', native: false, options: {},
    model: { modelID: 'mistral-small-latest', providerID: 'mistral' },
    permission: [{ permission: '*', pattern: '*', action: 'allow' }] },
  { name: 'compaction', mode: 'primary', native: true, hidden: true, options: {},
    permission: [{ permission: '*', pattern: '*', action: 'allow' }] },
  { name: 'explore', description: '探索', mode: 'subagent', native: true, options: {},
    permission: [{ permission: '*', pattern: '*', action: 'allow' }] },
];

/** Q4: 受け取った切替を記録する(`__stub/stats` から引用するため)。 */
const MODEL_SWITCHES = [];
const AGENT_SWITCHES = [];
let providerCalls = 0;
let agentCalls = 0;

/** Q2: markdown フィクスチャ。1文字ずつではなく段落単位で累積させる(逐次描画も観測できる)。 */
const MARKDOWN_CHUNKS = [
  '# 見出し1\n\nこれは**太字**と`インラインコード`を含む段落です。\n\n',
  '## 見出し2\n\n- 箇条書き1\n- 箇条書き2\n\n1. 番号付き1\n2. 番号付き2\n\n',
  '```kotlin\nfun main() {\n    println("hello")\n}\n```\n\n' +
    '[opencode](https://example.invalid/opencode) へのリンク。\n',
];

const now = Date.now();
const SESSIONS = new Map([
  ['ses_stub_0001', {
    id: 'ses_stub_0001', slug: 'stub-one', projectID: 'proj_stub',
    directory: '/tmp/stub', title: 'スタブセッション1', version: '1.18.21-stub',
    time: { created: now - 600000, updated: now - 60000 },
  }],
  ['ses_stub_0002', {
    id: 'ses_stub_0002', slug: 'stub-two', projectID: 'proj_stub',
    directory: '/tmp/stub', title: 'スタブセッション2', version: '1.18.21-stub',
    time: { created: now - 500000, updated: now - 120000 },
  }],
]);

/**
 * Q7: `GET /vcs/diff?mode=git&context=3` の応答(**実物 serve 1.18.21 が返したバイト列**)。
 *
 * 一時 git リポジトリを作って `?directory=` で指し、そのまま採取した。
 * 網羅しているのは §5b Q7 のゲートが要求する4形:
 *  - ハンクヘッダ `@@ -a,b +c,d @@`(と関数名の見出し付き)
 *  - 追加のみ(`--- /dev/null`)/ 削除のみ(`+++ /dev/null`)
 *  - 末尾改行なし(`\ No newline at end of file`)
 *  - バイナリ(`Binary files a/x and b/x differ` の1行だけ)
 */
const VCS_FIXTURE = [
  {
    file: 'added2.txt', additions: 2, deletions: 0, status: 'added',
    patch: 'diff --git a/added2.txt b/added2.txt\nnew file mode 100644\nindex 0000000..ce2b18a\n--- /dev/null\n+++ b/added2.txt\n@@ -0,0 +1,2 @@\n+brand new\n+file here\n',
  },
  {
    file: 'big.py', additions: 2, deletions: 2, status: 'modified',
    patch: 'diff --git a/big.py b/big.py\nindex 24a0c4f..c96f0aa 100644\n--- a/big.py\n+++ b/big.py\n@@ -1,5 +1,5 @@\n def f1():\n-    return 111\n+    return 111222\n \n def f2():\n     return 2\n@@ -50,7 +50,7 @@ def f17():\n     return 17\n \n def f18():\n-    return 999\n+    return 999888\n \n def f19():\n     return 19\n',
  },
  {
    file: 'image.bin', additions: 0, deletions: 0, status: 'modified',
    patch: 'diff --git a/image.bin b/image.bin\nindex 4eed854..d20f4c9 100644\nBinary files a/image.bin and b/image.bin differ\n',
  },
  {
    file: 'modified.txt', additions: 0, deletions: 6, status: 'deleted',
    patch: 'diff --git a/modified.txt b/modified.txt\ndeleted file mode 100644\nindex 624784e..0000000\n--- a/modified.txt\n+++ /dev/null\n@@ -1,6 +0,0 @@\n-line1\n-line2 CHANGED\n-line3\n-line4\n-line5\n-line6 added\n',
  },
  {
    file: 'nonewline.txt', additions: 2, deletions: 1, status: 'modified',
    patch: 'diff --git a/nonewline.txt b/nonewline.txt\nindex 27a7ea6..0fec236 100644\n--- a/nonewline.txt\n+++ b/nonewline.txt\n@@ -1,4 +1,5 @@\n a\n b\n c\n-d\n\\ No newline at end of file\n+d\n+e\n\\ No newline at end of file\n',
  },
];

/**
 * `STUB_DIFF_LONG=1` のときだけ足す**1行が長いファイル**。
 *
 * §5b Q7 のゲート「**横スクロールで長い行の末尾に到達できる**(dump の bounds で確認)」
 * を測るための道具。実データの patch はどれも1行が短く、この経路を通れない。
 * **形だけを実データに合わせ、中身は生成する**(bigFileDiff と同じ扱い)。
 */
function longLineDiff() {
  const tail = 'END_OF_LONG_LINE';
  const body = 'x'.repeat(LONG_LINE_CHARS) + tail;
  return {
    file: 'generated/longline.txt', additions: 1, deletions: 1, status: 'modified',
    patch: 'diff --git a/generated/longline.txt b/generated/longline.txt\n' +
      'index 1111111..2222222 100644\n--- a/generated/longline.txt\n+++ b/generated/longline.txt\n' +
      '@@ -1,1 +1,1 @@\n-short\n+' + body + '\n',
  };
}

/**
 * `STUB_DIFF_BIG=1` のときだけ足す巨大ファイル。
 * **アプリの打ち切り(5000行)を測るための道具**であって実データではない ——
 * だから形だけを実データに合わせ、中身は生成する。
 */
function bigFileDiff() {
  const lines = [];
  for (let i = 1; i <= BIG_DIFF_LINES; i++) lines.push(`+line ${i}`);
  return {
    file: 'generated/huge.txt', additions: BIG_DIFF_LINES, deletions: 0, status: 'added',
    patch: 'diff --git a/generated/huge.txt b/generated/huge.txt\nnew file mode 100644\n' +
      'index 0000000..1111111\n--- /dev/null\n+++ b/generated/huge.txt\n' +
      `@@ -0,0 +1,${BIG_DIFF_LINES} @@\n` + lines.join('\n') + '\n',
  };
}

/**
 * Q7: `GET /session/:id/diff` の応答(`SnapshotFileDiff[]`)。**3ファイル**
 * —— §5b Q7 のゲートが「スタブが3ファイル分の SnapshotFileDiff を返す」と指定している。
 *
 * `SnapshotFileDiff` の required は `additions` / `deletions` **だけ**なので、
 * 3件目は `status` を持たない形にしてある。required でないものを常に載せるフィクスチャは、
 * 「欠けたら落ちる」実装を通してしまう(P4 の `metadata` と同じ形)。
 */
const SNAPSHOT_FIXTURE = [
  VCS_FIXTURE[0],
  VCS_FIXTURE[1],
  { file: 'nonewline.txt', additions: 2, deletions: 1, patch: VCS_FIXTURE[4].patch },
];

/** セッションID -> {info,parts}[] 。infoは実装が依存する必須3フィールドのみ(id/sessionID/role)。 */
const HISTORY = new Map([
  ['ses_stub_0001', [
    { info: { id: 'msg_h1', sessionID: 'ses_stub_0001', role: 'user' },
      parts: [{ id: 'prt_h1', sessionID: 'ses_stub_0001', messageID: 'msg_h1', type: 'text', text: '履歴側のユーザー発言です' }] },
    { info: { id: 'msg_h2', sessionID: 'ses_stub_0001', role: 'assistant' },
      parts: [{ id: 'prt_h2', sessionID: 'ses_stub_0001', messageID: 'msg_h2', type: 'text', text: '履歴側のアシスタント応答です' }] },
    // STUB_EMPTY_ASSISTANT=1 のときだけ足す(下の unshift 相当の処理を参照)
  ]],
  ['ses_stub_0002', [
    { info: { id: 'msg_h3', sessionID: 'ses_stub_0002', role: 'user' },
      parts: [{ id: 'prt_h3', sessionID: 'ses_stub_0002', messageID: 'msg_h3', type: 'text', text: '2つ目のセッションの履歴です' }] },
  ]],
]);

/**
 * Q1: 日付グループ検証用の「日数オフセット」を今日の曜日から組み立てる。
 *
 * 固定の `[0,1,3,9,40]` にしないのは、3日前が「今週」か「先週」かが**今日の曜日で変わる**ため。
 * 週の起点からの経過日数を見て、その週の中に確実に入る値を選ぶ。
 * 今日が週の起点またはその翌日のときは「今週(今日・昨日以外)」が存在しないので、
 * その枠は作らない —— 無いものを作ると、無いはずのヘッダーを期待することになる。
 */
function groupDayOffsets() {
  const d = new Date();
  const sinceWeekStart = (d.getDay() - WEEK_START + 7) % 7;
  const offsets = [
    { days: 0, group: 'TODAY' },
    { days: 1, group: 'YESTERDAY' },
  ];
  if (sinceWeekStart >= 2) offsets.push({ days: sinceWeekStart, group: 'THIS_WEEK' });
  offsets.push({ days: sinceWeekStart + 1, group: 'LAST_WEEK' });
  offsets.push({ days: sinceWeekStart + 8, group: 'OLDER' });
  return offsets;
}

const GROUP_OFFSETS = groupDayOffsets();

/**
 * Q1: 追加セッションを生成する。`time.updated` は**エポックミリ秒**(実サーバーと同じ単位。
 * 秒で作るとアプリ側が全部1970年に落ちるので、ここを間違えると検証にならない)。
 * 5つの日付グループを巡回させ、同一グループ内でも1分ずつずらして順序を確定させる。
 */
function seedExtraSessions(count) {
  const base = Date.now();
  for (let i = 0; i < count; i++) {
    const slot = GROUP_OFFSETS[i % GROUP_OFFSETS.length];
    // 過去日は12:00ちょうど付近を狙う(0時直後だと検証中に日付が変わってグループが動く)。
    // 今日だけは12:00が**未来になりうる**(午前中に起動した場合)ので現在時刻から少し戻す。
    let updated;
    if (slot.days === 0) {
      updated = base - 30 * 60000 - i * 60000;
    } else {
      const day = new Date(base);
      day.setDate(day.getDate() - slot.days);
      day.setHours(12, 0, 0, 0);
      updated = day.getTime() - i * 60000;
    }
    const id = `ses_gen${String(i + 1).padStart(4, '0')}`;
    SESSIONS.set(id, {
      id,
      slug: `gen-${i + 1}`,
      projectID: 'proj_stub',
      directory: '/tmp/stub/generated',
      title: `生成セッション${String(i + 1).padStart(3, '0')} (${slot.group})`,
      version: '1.18.21-stub',
      time: { created: updated - 60000, updated },
    });
    HISTORY.set(id, []);
    GENERATED_GROUPS.set(id, slot.group);
  }
}

/** 生成セッションID -> スタブ側が意図した日付グループ。アプリの見出しと突き合わせるための期待値。 */
const GENERATED_GROUPS = new Map();
if (EXTRA_SESSIONS > 0) seedExtraSessions(EXTRA_SESSIONS);

/** Q1: sessionID -> SessionStatus。**idle のセッションは入れない**(実サーバーと同じ形)。 */
const SESSION_STATUS = new Map();

let evtSeq = 0;
let msgSeq = Number(process.env.STUB_MSG_SEQ_SEED || 0);
let promptCount = 0;
let patchCount = 0;
let deleteCount = 0;
/** Q1: `GET /session` が実際に受け取ったクエリの記録(検索クエリ到達の証跡)。 */
const LIST_QUERIES = [];
const sseClients = new Set();

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

function frame(type, properties) {
  const envelope = { id: `evt_${++evtSeq}`, type, properties };
  return `event: ${type}\ndata: ${JSON.stringify(envelope)}\n\n`;
}

function broadcast(type, properties) {
  const f = frame(type, properties);
  for (const res of sseClients) {
    try {
      res.write(f);
    } catch {
      sseClients.delete(res); // 切断済みクライアントへの書き込みで落ちない
    }
  }
  log('SSE->', type, JSON.stringify(properties).slice(0, 120));
}

/**
 * P4用の状態。permissionは「未応答のまま残る」ことが観測対象なので、応答が来るまで保持する。
 * abortは「実行中シーケンスを止めた」ことを観測したいので、世代カウンタで進行中ループを無効化する。
 */
const PERMISSIONS = new Map(); // permissionID -> {sessionID, response|null, payload}
/** Q6: `GET /permission` が何回叩かれたか。**アプリが実際に取りに来たこと**を引用可能にする。 */
let permissionListCalls = 0;
const ABORTED = new Map();     // sessionID -> generation number that was aborted
let permSeq = 0;
/**
 * Q0/R1: 「描くものが無い assistant メッセージ」を履歴へ混ぜる(STUB_EMPTY_ASSISTANT=1 のときだけ)。
 * shapeは docs/spec/opencode-1.18.21-openapi.json の required に合わせる
 * (StepStartPart: id/sessionID/messageID/type、StepFinishPart: +reason/cost/tokens)。
 */
if (WANT_EMPTY_ASSISTANT) {
  HISTORY.get('ses_stub_0001').push(
    // part を1つも持たない assistant(R1の元の症状)
    { info: { id: 'msg_h2b', sessionID: 'ses_stub_0001', role: 'assistant' }, parts: [] },
    // part はあるが描くものが無い(ライフサイクルのみ)。parts.length で判定すると素通りする形
    { info: { id: 'msg_h2c', sessionID: 'ses_stub_0001', role: 'assistant' },
      parts: [
        { id: 'prt_h2c1', sessionID: 'ses_stub_0001', messageID: 'msg_h2c', type: 'step-start' },
        { id: 'prt_h2c2', sessionID: 'ses_stub_0001', messageID: 'msg_h2c', type: 'step-finish',
          reason: 'stop', cost: 0, tokens: { input: 11, output: 0, reasoning: 0, cache: { read: 0, write: 0 } } },
      ] },
  );
}

/**
 * Q7: `PatchPart` を持つ assistant メッセージを履歴へ足す(`STUB_DIFF=1` のときだけ)。
 *
 * spec required: `id` `sessionID` `messageID` `type` `hash` `files`。
 * **「N ファイル変更」チップの材料はこれ**であって `GET /session/:id/diff` ではない ——
 * チップは通信せずに出て、押したときに初めて差分を引く(§5b Q7 スコープ3)。
 */
if (WANT_DIFF) {
  HISTORY.get('ses_stub_0001').push(
    { info: { id: 'msg_h2d', sessionID: 'ses_stub_0001', role: 'assistant' },
      parts: [
        { id: 'prt_h2d1', sessionID: 'ses_stub_0001', messageID: 'msg_h2d', type: 'text',
          text: '3つのファイルを変更しました。' },
        { id: 'prt_h2d2', sessionID: 'ses_stub_0001', messageID: 'msg_h2d', type: 'patch',
          hash: 'abc1234', files: ['added2.txt', 'big.py', 'nonewline.txt'] },
      ] },
  );
}

let abortCount = 0;
let promptGen = 0;

/* ---- Q3 の状態 ---- */

/** sessionID -> Todo[](`GET /session/:id/todo` が返すもの)。**既定は空**なので既存挙動は変わらない。 */
const TODOS = new Map();
/** requestID -> {sessionID, questions, answers|null, rejected} 。未応答のものが `GET /question` に出る。 */
const QUESTIONS = new Map();
let queSeq = 0;
let todoUpdates = 0;

/**
 * Q3: 演出用のタスクリスト。`Todo` の required(content/status/priority)を必ず満たす。
 * 実物 serve の実セッションから採った形と同じ3キーちょうど(API_CONTRACT.md「Q3で使用する分」)。
 */
function todoSnapshot(phase) {
  const rows = [
    ['契約を実機 /doc から確認する', ['in_progress', 'completed', 'completed']],
    ['Todo カードを実装する', ['pending', 'in_progress', 'completed']],
    ['Question カードを実装する', ['pending', 'pending', 'completed']],
  ];
  return rows.map(([content, states]) => ({
    content,
    status: states[phase],
    priority: 'high',
  }));
}

function emitTodo(sessionId, phase) {
  const todos = todoSnapshot(phase);
  TODOS.set(sessionId, todos);
  todoUpdates++;
  broadcast('todo.updated', { sessionID: sessionId, todos });
}

/**
 * Q3: 質問1件分。**`QuestionOption` の required は `label` と `description` の両方**。
 * `multiple` / `custom` は spec 上任意なので、載せる回だけ載せる。
 */
function questionFixture(multi = WANT_QUESTION_MULTI) {
  const questions = [
    {
      question: 'どちらの方式で進めますか',
      header: '方式選択',
      options: [
        { label: 'A案', description: '速いが粗い' },
        { label: 'B案', description: '遅いが確実' },
      ],
      custom: true,
    },
  ];
  if (multi) {
    questions.push({
      question: '確認したい観点を選んでください(複数可)',
      header: '観点',
      options: [
        { label: '性能', description: '速度とメモリ' },
        { label: '互換', description: '既存挙動を壊さないか' },
        { label: '可読性', description: '後から読めるか' },
      ],
      multiple: true,
    });
  }
  return questions;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/** prompt_async 1回分のSSEシーケンス。同一part.idでtextを累積更新し逐次描画を観測可能にする。 */
async function runPromptSequence(sessionId, userText) {
  const myGen = ++promptGen;
  const userMsgId = `msg_u${++msgSeq}`;
  const asstMsgId = `msg_a${++msgSeq}`;
  const asstPartId = `prt_a${msgSeq}`;

  // 0) Q1: 実行状態 busy(既定オフ。STUB_STATUS_ON_PROMPT=1 のときだけ)
  if (WANT_STATUS_ON_PROMPT) {
    SESSION_STATUS.set(sessionId, { type: 'busy' });
    broadcast('session.status', { sessionID: sessionId, status: { type: 'busy' } });
  }

  // 1) ユーザーメッセージのエコー(実物と同様にpart更新として流す)
  broadcast('message.part.updated', {
    sessionID: sessionId,
    part: { id: `prt_${userMsgId}`, sessionID: sessionId, messageID: userMsgId, type: 'text', text: userText },
    time: { start: Date.now() },
  });
  await sleep(200);

  // 2) assistantメッセージの状態更新(info内容にはアプリは依存しない)
  // Q4/STUB_MESSAGE_META=1(既定オフ): 実物と同じく **assistant はフラットな**
  // providerID / modelID / cost / tokens を持つ(user 側はネストした `model`)。
  const asstInfo = { id: asstMsgId, sessionID: sessionId, role: 'assistant' };
  if (WANT_MESSAGE_META) {
    const cur = SESSIONS.get(sessionId)?.model;
    asstInfo.providerID = cur?.providerID || 'mistral';
    asstInfo.modelID = cur?.id || 'mistral-medium-latest';
    asstInfo.agent = SESSIONS.get(sessionId)?.agent || 'build';
    asstInfo.cost = 0.0123;
    asstInfo.tokens = { input: 42, output: 17, reasoning: 0, cache: { read: 3, write: 5 } };
  }
  broadcast('message.updated', { sessionID: sessionId, info: asstInfo });

  // 2.5) STUB_STEP_START=1: 実物と同じく step-start を先に流す。
  //      この時点の assistant バブルは「描くものが無い」状態になる(R1の待ち表示の観測点)。
  if (WANT_STEP_START) {
    broadcast('message.part.updated', {
      sessionID: sessionId,
      part: { id: `prt_ss${msgSeq}`, sessionID: sessionId, messageID: asstMsgId, type: 'step-start' },
      time: { start: Date.now() },
    });
    await sleep(200);
  }

  // 2.6) Q2: retry 状態(既定オフ)。`action` は spec の required 5項目を満たす。
  if (WANT_RETRY) {
    const retryStatus = {
      type: 'retry',
      attempt: 2,
      message: 'upstream temporarily unavailable',
      next: Date.now() + RETRY_MS,
      action: {
        reason: 'rate_limit',
        provider: 'anthropic',
        title: 'レート制限',
        message: 'しばらく待ってから再試行します',
        label: '設定を開く',
      },
    };
    SESSION_STATUS.set(sessionId, retryStatus);
    broadcast('session.status', { sessionID: sessionId, status: retryStatus });
    await sleep(RETRY_MS);
    if (ABORTED.get(sessionId) === myGen) { log('aborted during retry'); return; }
    SESSION_STATUS.set(sessionId, { type: 'busy' });
    broadcast('session.status', { sessionID: sessionId, status: { type: 'busy' } });
  }

  // 2.65) Q3: タスクリストの第1段(pending 中心)。既定オフ。
  if (WANT_TODO) {
    emitTodo(sessionId, 0);
    await sleep(CHUNK_MS);
    if (ABORTED.get(sessionId) === myGen) { log('aborted during todo'); return; }
  }

  // 2.7) Q2: reasoning part(既定オフ)。ReasoningPart の required を満たす。
  if (WANT_REASONING) {
    broadcast('message.part.updated', {
      sessionID: sessionId,
      part: {
        id: `prt_rs${msgSeq}`, sessionID: sessionId, messageID: asstMsgId, type: 'reasoning',
        text: 'まず要求を分解する。次に必要な道具を選び、最後に結果をまとめる。',
        metadata: {},
        time: { start: Date.now() },
      },
      time: { start: Date.now() },
    });
    await sleep(200);
  }

  // 2.8) Q2: ツール活動 part を running → completed(既定オフ)。**同一 part.id を更新する**
  //      —— 別IDにするとカードが2枚並び、「状態変化」ではなく「2回実行」に見える。
  if (WANT_TOOL_PART) {
    const toolPartId = `prt_tl${msgSeq}`;
    // STUB_TOOL_FILE=1: **ファイルパスを持つツール入力**にする(Q8 スコープ6:
    // ツールカードのパスをタップしてファイルビューアを開く導線の観測)。
    // **既定は従来どおり** `command` のみ —— Q2 の証跡が `input: command=ls -la, ...` を引用している。
    const toolInput = WANT_TOOL_FILE
      ? { filePath: 'app/src/Theme.kt', description: 'read file' }
      : { command: 'ls -la', description: 'list files' };
    broadcast('message.part.updated', {
      sessionID: sessionId,
      part: {
        id: toolPartId, sessionID: sessionId, messageID: asstMsgId, type: 'tool',
        callID: `call_${msgSeq}`, tool: 'bash',
        state: { status: 'running', input: toolInput, title: 'bash: ls -la', time: { start: Date.now() } },
      },
      time: { start: Date.now() },
    });
    await sleep(CHUNK_MS * 2);
    if (ABORTED.get(sessionId) === myGen) { log('aborted during tool'); return; }
    broadcast('message.part.updated', {
      sessionID: sessionId,
      part: {
        id: toolPartId, sessionID: sessionId, messageID: asstMsgId, type: 'tool',
        callID: `call_${msgSeq}`, tool: 'bash',
        state: {
          status: 'completed', input: toolInput, output: 'total 8\ndrwxr-xr-x 2 u u 4096 .',
          title: 'bash: ls -la', metadata: {},
          time: { start: Date.now() - 1000, end: Date.now() },
        },
      },
      time: { start: Date.now() },
    });
    await sleep(200);
  }

  // 3) 同一part.idへの遅延チャンク累積(逐次描画の本体)
  const BODY_CHUNKS = WANT_MARKDOWN ? MARKDOWN_CHUNKS : CHUNKS;
  let acc = '';
  for (let i = 0; i < BODY_CHUNKS.length; i++) {
    await sleep(CHUNK_MS);
    // abortされたら以降のチャンクを流さない(P4ゲート②の観測点)
    if (ABORTED.get(sessionId) === myGen) { log('aborted mid-stream at chunk', i); return; }
    acc += BODY_CHUNKS[i];
    broadcast('message.part.updated', {
      sessionID: sessionId,
      part: { id: asstPartId, sessionID: sessionId, messageID: asstMsgId, type: 'text', text: acc },
      time: { start: Date.now() },
    });
    if (i === 0) {
      // 4) 未知typeをわざと混入し、アプリがストリームを落とさないことを観測可能にする
      broadcast('session.next.text_delta', { sessionID: sessionId, delta: '(v2系・未対応type)' });
      broadcast('stub.unknown_event', { note: 'unknown-type tolerance probe' });
    }
  }

  // 4.2) Q3: タスクリストの第2段(in_progress が動く)。既定オフ。
  if (WANT_TODO) {
    emitTodo(sessionId, 1);
    await sleep(CHUNK_MS);
    if (ABORTED.get(sessionId) === myGen) { log('aborted during todo(2)'); return; }
  }

  // 4.4) Q3: `question.asked` を出し、reply / reject が来るまで待つ。既定オフ。
  //      permission と**同時に pending** にできるよう、permission より先に出して待たない —— と
  //      したいところだが、順に待つほうが「同時 pending」を作れない。ここは出して即座に
  //      permission へ進み、**両方が同時に未応答**になる形にする(§5 Q3 スコープ3の観測点)。
  let pendingQuestionId = null;
  if (WANT_QUESTION) {
    pendingQuestionId = `que_${++queSeq}`;
    const questions = questionFixture();
    QUESTIONS.set(pendingQuestionId, {
      sessionID: sessionId, questions, answers: null, rejected: false,
    });
    // type 名は既定 v1。STUB_QUESTION_V2=1 で v2 名にする(shape は同一)。
    broadcast(WANT_QUESTION_V2 ? 'question.v2.asked' : 'question.asked', {
      id: pendingQuestionId,
      sessionID: sessionId,
      questions,
      tool: { messageID: asstMsgId, callID: `call_${pendingQuestionId}` },
    });
  }

  // 4.5) permission.asked を出し、応答が来るまで待つ(P4ゲート①)。
  //      未応答のまま回転しても要求が消えないことを観測するため、ここで止まるのが正しい挙動。
  if (WANT_PERMISSION) {
    const permId = `per_${++permSeq}`;
    // Q6: `GET /permission` がこの中身をそのまま返せるよう、**payload ごと**控える。
    // 実物 serve の `GET /permission` は `PermissionRequest[]` を返し、その要素は
    // `permission.asked` の properties と同じ形である(spec / API_CONTRACT.md)。
    const permPayload = {
      id: permId,
      sessionID: sessionId,
      permission: 'bash',
      patterns: ['rm -rf *'],
      metadata: { command: 'rm -rf /tmp/stub-demo' },
      always: [],
      tool: { messageID: asstMsgId, callID: `call_${permId}` },
    };
    PERMISSIONS.set(permId, { sessionID: sessionId, response: null, payload: permPayload });
    broadcast('permission.asked', permPayload);
    // 応答待ち。abortされた場合も抜ける。
    for (let waited = 0; waited < PERM_TIMEOUT_MS; waited += 250) {
      if (ABORTED.get(sessionId) === myGen) { log('aborted while waiting permission'); return; }
      if (PERMISSIONS.get(permId)?.response) break;
      await sleep(250);
    }
    const answered = PERMISSIONS.get(permId)?.response;
    log('permission resolved:', permId, answered ?? '(timeout)');
    broadcast('permission.replied', { id: permId, sessionID: sessionId, response: answered ?? 'timeout' });
    if (answered === 'reject') {
      broadcast('session.idle', { sessionID: sessionId });
      return;
    }
  }

  // 4.6) Q3: 質問の応答待ち。**実物 serve と同じく、応答するまで実行は終わらない。**
  //      タイムアウトは「応答なし」という観測結果として扱う(permission と同じ扱い)。
  if (pendingQuestionId) {
    for (let waited = 0; waited < QUESTION_TIMEOUT_MS; waited += 250) {
      if (ABORTED.get(sessionId) === myGen) { log('aborted while waiting question'); return; }
      const q = QUESTIONS.get(pendingQuestionId);
      if (q && (q.answers || q.rejected)) break;
      await sleep(250);
    }
    const q = QUESTIONS.get(pendingQuestionId);
    log('question resolved:', pendingQuestionId, JSON.stringify(q && (q.answers ?? (q.rejected ? 'rejected' : null))));
    // **`asked` は `id`、`replied`/`rejected` は `requestID`**(spec)。ここを揃えないと
    // アプリ側の実装をスタブが誤って肯定する。
    if (q && q.rejected) {
      broadcast(WANT_QUESTION_V2 ? 'question.v2.rejected' : 'question.rejected', {
        sessionID: sessionId, requestID: pendingQuestionId,
      });
      broadcast('session.idle', { sessionID: sessionId });
      return;
    }
    broadcast(WANT_QUESTION_V2 ? 'question.v2.replied' : 'question.replied', {
      sessionID: sessionId, requestID: pendingQuestionId, answers: (q && q.answers) || [],
    });
  }

  // 4.7) Q3: タスクリストの第3段(全部 completed)。**session.idle の後も残ることの観測点**。
  if (WANT_TODO) emitTodo(sessionId, 2);

  // 5) 完了 -> 入力欄復帰シグナル
  if (ABORTED.get(sessionId) === myGen) { log('aborted before idle'); return; }
  if (WANT_STATUS_ON_PROMPT) {
    SESSION_STATUS.delete(sessionId);
    broadcast('session.status', { sessionID: sessionId, status: { type: 'idle' } });
  }
  broadcast('session.idle', { sessionID: sessionId });

  // 6) 完了した1往復をメモリ履歴へ追記(プロセス死後の再取得=ゲート③検証用)
  const hist = HISTORY.get(sessionId) || [];
  hist.push(
    { info: { id: userMsgId, sessionID: sessionId, role: 'user' },
      parts: [{ id: `prt_${userMsgId}`, sessionID: sessionId, messageID: userMsgId, type: 'text', text: userText }] },
    { info: { id: asstMsgId, sessionID: sessionId, role: 'assistant' },
      parts: [{ id: asstPartId, sessionID: sessionId, messageID: asstMsgId, type: 'text', text: acc }] },
  );
  HISTORY.set(sessionId, hist);
  const ses = SESSIONS.get(sessionId);
  if (ses) ses.time.updated = Date.now();
  log('prompt sequence done:', sessionId);
}

function checkAuth(req) {
  const h = req.headers.authorization || '';
  if (!h.startsWith('Basic ')) return false;
  let decoded = '';
  try {
    decoded = Buffer.from(h.slice(6), 'base64').toString('utf8');
  } catch {
    return false;
  }
  const idx = decoded.indexOf(':');
  const user = idx >= 0 ? decoded.slice(0, idx) : decoded;
  const pass = idx >= 0 ? decoded.slice(idx + 1) : '';
  return user === USER && pass === PASSWORD;
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => { data += c; });
    req.on('end', () => resolve(data));
  });
}

/** 壊れたJSONでスタブ自身が死なないようにする(検証中のクラッシュ=証跡中断を防ぐ)。 */
function parseJsonSafe(text) {
  try {
    return JSON.parse(text || '{}');
  } catch {
    return null;
  }
}

const server = http.createServer(async (req, res) => {
  try {
    await handle(req, res);
  } catch (e) {
    log('handler error:', e && e.message);
    try {
      sendJson(res, 500, { error: 'internal' });
    } catch { /* 既に応答済み */ }
  }
});

async function handle(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const path = decodeURIComponent(url.pathname);

  if (!checkAuth(req)) {
    log('401', req.method, path);
    res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="opencode-stub"' });
    res.end();
    return;
  }

  // ---- SSE ----
  if (req.method === 'GET' && path === '/event') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    req.socket.setKeepAlive(true);
    res.write('retry: 3000\n\n');
    res.write(frame('server.connected', {}));
    sseClients.add(res);
    log('SSE client connected (total:', sseClients.size, ')');
    // 切断検知: errorイベントを放置するとuncaughtExceptionでプロセスが死ぬ(実測済み)
    res.on('error', () => sseClients.delete(res));
    req.socket.on('error', () => { /* close側で削除される */ });
    req.on('close', () => {
      sseClients.delete(res);
      log('SSE client closed (total:', sseClients.size, ')');
    });
    return;
  }

  // ---- 検証補助 ----
  if (req.method === 'POST' && path === '/__stub/drop-events') {
    for (const c of [...sseClients]) c.destroy();
    sendJson(res, 200, { dropped: true });
    return;
  }
  if (req.method === 'GET' && path === '/__stub/stats') {
    sendJson(res, 200, {
      sseClients: sseClients.size,
      promptCount,
      eventsSent: evtSeq,
      sessions: [...SESSIONS.keys()],
      permissionsPending: [...PERMISSIONS.entries()].filter(([, v]) => !v.response).map(([k]) => k),
      permissionListCalls,
      permissionsAnswered: [...PERMISSIONS.entries()].filter(([, v]) => v.response).map(([k, v]) => `${k}:${v.response}`),
      abortCount,
      // ---- Q1 ----
      sessionCount: SESSIONS.size,
      patchCount,
      deleteCount,
      sessionStatus: Object.fromEntries(SESSION_STATUS),
      listQueries: LIST_QUERIES.slice(-10),
      lastListQuery: LIST_QUERIES[LIST_QUERIES.length - 1] || null,
      maxLimitSeen: LIST_QUERIES.reduce((m, q) => Math.max(m, Number(q.limit) || 0), 0),
      // ---- Q3 ----
      todoUpdates,
      todos: Object.fromEntries(TODOS),
      questionsPending: [...QUESTIONS.entries()].filter(([, v]) => !v.answers && !v.rejected).map(([k]) => k),
      // **`answers` をそのまま出す**。ゲートが「answersの形を __stub/stats で確認」と
      // 指定しているのはここ —— 形の同一性は引用できる文字列でしか主張できない。
      questionsAnswered: [...QUESTIONS.entries()]
        .filter(([, v]) => v.answers)
        .map(([k, v]) => ({ requestID: k, answers: v.answers })),
      questionsRejected: [...QUESTIONS.entries()].filter(([, v]) => v.rejected).map(([k]) => k),
      // ---- Q4 ----
      providerCalls,
      agentCalls,
      // 送られた ModelRef を**そのまま**出す。`id` / `modelID` の取り違えは
      // ここに載る文字列でしか主張できない(実サーバーは誤りを 204 で受理する)。
      // ---- Q8 ----
      // **撃たれたクエリをそのまま出す。** キーの取り違え(`path` を落とす等)は
      // ここに載る文字列でしか主張できない —— 実サーバーは未知のクエリキーを黙って無視する。
      fileListQueries: FILE_LIST_QUERIES.slice(-10),
      fileContentQueries: FILE_CONTENT_QUERIES.slice(-10),
      findQueries: FIND_QUERIES.slice(-10),
      findFileQueries: FIND_FILE_QUERIES.slice(-10),
      findSymbolQueries: FIND_SYMBOL_QUERIES.slice(-10),
      // ---- Q7 ----
      vcsInfoCalls,
      vcsStatusCalls,
      vcsDiffQueries: VCS_DIFF_QUERIES.slice(-10),
      sessionDiffQueries: SESSION_DIFF_QUERIES.slice(-10),
      revertCount,
      unrevertCount,
      revertFailCount,
      reverts: REVERTS,
      sessionReverts: Object.fromEntries(
        [...SESSIONS.entries()].map(([id, ses]) => [id, ses.revert || null]),
      ),
      modelSwitches: MODEL_SWITCHES,
      agentSwitches: AGENT_SWITCHES,
      sessionModels: Object.fromEntries(
        [...SESSIONS.entries()].map(([id, s]) => [id, { agent: s.agent || null, model: s.model || null }]),
      ),
    });
    return;
  }

  /**
   * Q1: 生成セッションの「スタブ側が意図した日付グループ」と実際の `time.updated`。
   * アプリの見出しと突き合わせるための期待値。**アプリと独立に(JS側で)計算している**ので、
   * 一致すれば「両方が同じ思い込みを共有していた」以外の説明が要る。
   */
  if (req.method === 'GET' && path === '/__stub/session-groups') {
    sendJson(res, 200, {
      weekStart: WEEK_START,
      offsets: GROUP_OFFSETS,
      sessions: [...GENERATED_GROUPS.entries()].map(([id, group]) => ({
        id,
        group,
        title: SESSIONS.get(id)?.title,
        updated: SESSIONS.get(id)?.time?.updated,
      })),
    });
    return;
  }

  /**
   * Q1: 実行状態を外から設定して `session.status` を流す。
   * body: `{"sessionID":"ses_...","type":"busy"|"retry"|"idle","attempt":1,"message":"…","next":0}`
   * `idle` はマップから消す(実サーバーが idle を載せないのに合わせる)が、
   * **イベントとしては `{"type":"idle"}` を流す**(実サーバーの実測どおり)。
   * retry は spec の required(`type` `attempt` `message` `next`)を満たす形で流す。
   */
  if (req.method === 'POST' && path === '/__stub/session-status') {
    const body = parseJsonSafe(await readBody(req)) || {};
    const sid = body.sessionID;
    const type = body.type || 'idle';
    if (!sid || !['idle', 'busy', 'retry'].includes(type)) {
      sendJson(res, 400, { error: 'sessionID and type(idle|busy|retry) required' });
      return;
    }
    let status;
    if (type === 'retry') {
      status = {
        type: 'retry',
        attempt: Number(body.attempt ?? 1),
        message: String(body.message ?? 'upstream temporarily unavailable'),
        next: Number(body.next ?? Date.now() + 5000),
      };
      // Q2: `retry.action` は spec 上**任意**だが、あるなら
      // reason/provider/title/message/label が required(link だけ任意)。
      // body.action を渡したときだけ載せる(既定は従来どおり action 無し)。
      if (body.action) {
        status.action = {
          reason: String(body.action.reason ?? 'rate_limit'),
          provider: String(body.action.provider ?? 'anthropic'),
          title: String(body.action.title ?? 'レート制限'),
          message: String(body.action.message ?? 'しばらく待ってから再試行します'),
          label: String(body.action.label ?? '設定を開く'),
          ...(body.action.link ? { link: String(body.action.link) } : {}),
        };
      }
    } else {
      status = { type };
    }
    if (type === 'idle') SESSION_STATUS.delete(sid); else SESSION_STATUS.set(sid, status);
    sendJson(res, 200, { ok: true, status });
    broadcast('session.status', { sessionID: sid, status });
    return;
  }

  // ---- REST(MVP使用分) ----
  if (req.method === 'GET' && path === '/global/health') {
    sendJson(res, 200, { healthy: true, version: '1.18.21-stub' });
    return;
  }

  /**
   * Q1: `start` / `limit` / `search` を**実サーバーで測った意味のまま**実装する
   * (API_CONTRACT.md「`start` / `limit` / `search` の意味」):
   *   - 並びは `time.updated` 降順
   *   - `limit` 既定100(これが R2 の「直近100件の窓」の正体)
   *   - `start` は**オフセットではなく** `time.updated >= start` の下限フィルタ
   *   - `search` は title の部分一致・大文字小文字無視
   * 既定(クエリ無し・セッション2件)の応答は従来と同じなので、既存のE2Eは壊れない。
   */
  if (req.method === 'GET' && path === '/session') {
    const q = {
      start: url.searchParams.get('start'),
      limit: url.searchParams.get('limit'),
      search: url.searchParams.get('search'),
    };
    LIST_QUERIES.push({ at: Date.now(), ...q });
    if (LIST_QUERIES.length > 50) LIST_QUERIES.shift();

    let list = [...SESSIONS.values()].sort((a, b) => (b.time?.updated || 0) - (a.time?.updated || 0));
    if (q.search) {
      const needle = q.search.toLowerCase();
      list = list.filter((s) => (s.title || '').toLowerCase().includes(needle));
    }
    if (q.start !== null && q.start !== '' && Number.isFinite(Number(q.start))) {
      const floor = Number(q.start);
      list = list.filter((s) => (s.time?.updated || 0) >= floor);
    }
    const limit = q.limit !== null && Number.isFinite(Number(q.limit)) ? Number(q.limit) : LIST_DEFAULT_LIMIT;
    sendJson(res, 200, list.slice(0, Math.max(0, limit)));
    return;
  }

  /**
   * Q1: `GET /session/status`。実サーバーと同じく **idle は載せない**
   * (何も走っていなければ `{}`)。
   */
  // ---- Q4: GET /provider。**`connected` は `all` の部分集合**(実物実測)。 ----
  if (req.method === 'GET' && path === '/provider') {
    providerCalls++;
    // STUB_PROVIDER_EMPTY=1(既定オフ)で接続済み0件にする。空状態の陽性コントロール。
    sendJson(res, 200, WANT_PROVIDER_EMPTY ? { ...PROVIDERS, connected: [] } : PROVIDERS);
    return;
  }

  // ---- Q4: GET /agent ----
  if (req.method === 'GET' && path === '/agent') {
    agentCalls++;
    sendJson(res, 200, AGENTS);
    return;
  }

  // ---- Q4: POST /api/session/:id/model {model: ModelRef} -> 204(実物実測) ----
  const switchModelMatch = path.match(/^\/api\/session\/([^/]+)\/model$/);
  if (req.method === 'POST' && switchModelMatch) {
    const ses = SESSIONS.get(switchModelMatch[1]);
    if (!ses) {
      sendJson(res, 404, {
        _tag: 'SessionNotFoundError', sessionID: switchModelMatch[1],
        message: `Session not found: ${switchModelMatch[1]}`,
      });
      return;
    }
    const body = parseJsonSafe(await readBody(req));
    if (!body || !body.model) { sendJson(res, 400, { _tag: 'InvalidRequestError' }); return; }
    MODEL_SWITCHES.push({ at: Date.now(), sessionID: ses.id, model: body.model });
    // STUB_MODEL_SWITCH_FAIL=<status>(既定0)で失敗させる。陰性側。
    if (MODEL_SWITCH_FAIL > 0) {
      sendJson(res, MODEL_SWITCH_FAIL, { _tag: 'InvalidRequestError', message: 'STUB_MODEL_SWITCH_FAIL' });
      return;
    }
    // **実サーバーは値を検証しない**(存在しないモデルも 204 で保存する。実測 #4)。
    // スタブも検証しない —— 検証してしまうと、アプリ側の防波堤が要ることを隠す。
    ses.model = body.model;
    ses.time = { ...(ses.time || {}), updated: Date.now() };
    res.writeHead(204);
    res.end();
    log('model switch:', ses.id, JSON.stringify(body.model));
    // **`session.updated` は流さない**(実測。切替では流れない)。
    // STUB_NO_SWITCH_EVENT=1 で next.model.switched も止め、
    // 「イベントが来なくても GET の取り直しで直る」ことを測れるようにする。
    if (!WANT_NO_SWITCH_EVENT) {
      broadcast('session.next.model.switched', {
        sessionID: ses.id, messageID: `msg_stub${++msgSeq}`,
        timestamp: new Date().toISOString(), model: body.model,
      });
    }
    return;
  }

  // ---- Q4: POST /api/session/:id/agent {agent} -> 204(実物実測) ----
  const switchAgentMatch = path.match(/^\/api\/session\/([^/]+)\/agent$/);
  if (req.method === 'POST' && switchAgentMatch) {
    const ses = SESSIONS.get(switchAgentMatch[1]);
    if (!ses) { sendJson(res, 404, { _tag: 'SessionNotFoundError' }); return; }
    const body = parseJsonSafe(await readBody(req));
    if (!body || typeof body.agent !== 'string') { sendJson(res, 400, { _tag: 'InvalidRequestError' }); return; }
    AGENT_SWITCHES.push({ at: Date.now(), sessionID: ses.id, agent: body.agent });
    ses.agent = body.agent;
    res.writeHead(204);
    res.end();
    if (!WANT_NO_SWITCH_EVENT) {
      broadcast('session.next.agent.switched', {
        sessionID: ses.id, messageID: `msg_stub${++msgSeq}`,
        timestamp: new Date().toISOString(), agent: body.agent,
      });
    }
    return;
  }

  if (req.method === 'GET' && path === '/session/status') {
    sendJson(res, 200, Object.fromEntries(SESSION_STATUS));
    return;
  }

  if (req.method === 'POST' && path === '/session') {
    const body = parseJsonSafe(await readBody(req));
    if (!body) { sendJson(res, 400, { error: 'bad json' }); return; }
    const id = `ses_stub_new${++msgSeq}`;
    const ses = {
      id, slug: body.title || 'new', projectID: 'proj_stub', directory: '/tmp/stub',
      title: body.title || id, version: '1.18.21-stub',
      time: { created: Date.now(), updated: Date.now() },
    };
    // Q4: 実サーバーは `agent` / `model` を受理して `Session` に反映する(実測)。
    // **キーが無い場合は生やさない** —— 実物も未指定のセッションでは両方欠ける。
    if (typeof body.agent === 'string') ses.agent = body.agent;
    if (body.model && typeof body.model === 'object') ses.model = body.model;
    SESSIONS.set(id, ses);
    HISTORY.set(id, []);
    sendJson(res, 200, ses);
    // Q1: 実サーバーは POST /session で session.created を流す(実測)。形も {sessionID, info}。
    broadcast('session.created', { sessionID: id, info: ses });
    return;
  }

  // ---- Q4: GET /session/:id -> Session。**モデル/エージェントの権威**(実物実測 200) ----
  const getSessionMatch = path.match(/^\/session\/([^/]+)$/);
  if (req.method === 'GET' && getSessionMatch) {
    const ses = SESSIONS.get(getSessionMatch[1]);
    if (!ses) {
      sendJson(res, 404, { name: 'NotFoundError', data: { message: `Session not found: ${getSessionMatch[1]}` } });
      return;
    }
    sendJson(res, 200, ses);
    return;
  }

  // ---- Q1: PATCH /session/:id(改名)。実サーバーは 200 + 更新後 Session を返す ----
  const patchMatch = path.match(/^\/session\/([^/]+)$/);
  if (req.method === 'PATCH' && patchMatch) {
    const ses = SESSIONS.get(patchMatch[1]);
    if (!ses) {
      sendJson(res, 404, { name: 'NotFoundError', data: { message: `Session not found: ${patchMatch[1]}` } });
      return;
    }
    const body = parseJsonSafe(await readBody(req));
    if (!body) { sendJson(res, 400, { error: 'bad json' }); return; }
    // Q6: 観測用の遅延(既定0)。`STUB_PATCH_DELAY_MS` の doc を参照。
    if (PATCH_DELAY_MS > 0) await sleep(PATCH_DELAY_MS);
    if (typeof body.title === 'string') ses.title = body.title;
    patchCount++;
    // 実測: 改名しても time.updated は動かない。ここでも動かさない。
    sendJson(res, 200, ses);
    broadcast('session.updated', { sessionID: ses.id, info: ses });
    return;
  }

  // ---- Q3: GET /session/:id/todo。**todo が無ければ `[]`(404 ではない。実物実測)** ----
  const todoMatch = path.match(/^\/session\/([^/]+)\/todo$/);
  if (req.method === 'GET' && todoMatch) {
    sendJson(res, 200, TODOS.get(todoMatch[1]) || []);
    return;
  }

  // ---- Q3: GET /question。**全セッション横断の未応答質問**(実物実測: 無ければ `[]`) ----
  // ---- Q6: GET /permission -> `PermissionRequest[]`(全セッション横断の未応答)----
  //
  // 実物 serve 1.18.21 に存在することを実測済み(2026-08-27: 200 `[]`)。
  // 申し送り Q5-2「permission がチャット再入場で復帰しない」を測るために足した。
  // **既存の陽性試験を壊さない**: 応答済みは返さないので、P4 の観測(応答後に
  // `permissionsPending` が空になる)と同じ集合を別の形で見せているだけである。
  if (req.method === 'GET' && path === '/permission') {
    permissionListCalls++;
    const pending = [...PERMISSIONS.entries()]
      .filter(([, v]) => !v.response && v.payload)
      .map(([, v]) => v.payload);
    sendJson(res, 200, pending);
    return;
  }

  if (req.method === 'GET' && path === '/question') {
    const pending = [...QUESTIONS.entries()]
      .filter(([, v]) => !v.answers && !v.rejected)
      .map(([id, v]) => ({ id, sessionID: v.sessionID, questions: v.questions }));
    sendJson(res, 200, pending);
    return;
  }

  // ---- Q3: POST /question/:id/reply {answers: string[][]} -> 200 + `true` / 404 ----
  const qReplyMatch = path.match(/^\/question\/([^/]+)\/reply$/);
  if (req.method === 'POST' && qReplyMatch) {
    const raw = await readBody(req);
    log('question reply body:', raw);
    const body = parseJsonSafe(raw);
    const entry = QUESTIONS.get(qReplyMatch[1]);
    if (!entry) {
      // 実物と同じ形(spec `QuestionNotFoundError`: `_tag` `requestID` `message`)
      sendJson(res, 404, {
        _tag: 'QuestionNotFoundError',
        requestID: qReplyMatch[1],
        message: `Question not found: ${qReplyMatch[1]}`,
      });
      return;
    }
    // spec: required は `answers` のみ、`QuestionAnswer` は `string[]`。
    // **形を検査する** —— スタブが何でも受けると、アプリの形の誤りを見逃す。
    if (!body || !Array.isArray(body.answers) ||
        !body.answers.every((a) => Array.isArray(a) && a.every((s) => typeof s === 'string'))) {
      sendJson(res, 400, { error: 'answers must be string[][]' });
      return;
    }
    entry.answers = body.answers;
    sendJson(res, 200, true);
    return;
  }

  // ---- Q3: POST /question/:id/reject -> 200 + `true` / 404 ----
  const qRejectMatch = path.match(/^\/question\/([^/]+)\/reject$/);
  if (req.method === 'POST' && qRejectMatch) {
    const entry = QUESTIONS.get(qRejectMatch[1]);
    if (!entry) {
      sendJson(res, 404, {
        _tag: 'QuestionNotFoundError',
        requestID: qRejectMatch[1],
        message: `Question not found: ${qRejectMatch[1]}`,
      });
      return;
    }
    entry.rejected = true;
    sendJson(res, 200, true);
    return;
  }

  /**
   * Q3: `todo.updated` を外から流す(prompt を回さずに3状態を作るための口)。
   * body: `{"sessionID":"ses_...","phase":0|1|2}`。既定挙動には影響しない。
   */
  /**
   * Q4: `session.error` を1回流す(prompt を回さずにエラー帯を作る口)。
   * body `{sessionID, statusCode?, isRetryable?, message?}`。
   * **形は実物の 402 フレームをそのまま模す**(API_CONTRACT.md):
   *   `{name:'APIError', data:{message, statusCode, isRetryable, responseHeaders, responseBody, metadata}}`
   */
  if (req.method === 'POST' && path === '/__stub/session-error') {
    const body = parseJsonSafe(await readBody(req)) || {};
    const sessionID = body.sessionID || 'ses_stub_0001';
    const data = {
      message: body.message || 'Payment Required: {"detail":"Check your subscription"}',
      responseHeaders: { server: 'cloudflare' },
      responseBody: '{}',
      metadata: { url: 'https://api.example/v1/chat/completions' },
    };
    // **キーごと省かれる場合も作れること**。実物は載せてくるが、載せないサーバーで
    // 「分からない」と「再試行するな」を取り違えないことを測る側でもある。
    if (body.statusCode !== null && body.statusCode !== undefined) data.statusCode = body.statusCode;
    if (body.isRetryable !== null && body.isRetryable !== undefined) data.isRetryable = body.isRetryable;
    broadcast('session.error', { sessionID, error: { name: 'APIError', data } });
    sendJson(res, 200, { ok: true, sessionID, data });
    return;
  }

  if (req.method === 'POST' && path === '/__stub/todo') {
    const body = parseJsonSafe(await readBody(req)) || {};
    if (!body.sessionID) { sendJson(res, 400, { error: 'sessionID required' }); return; }
    const phase = Math.min(2, Math.max(0, Number(body.phase ?? 0)));
    emitTodo(body.sessionID, phase);
    sendJson(res, 200, { ok: true, phase, todos: TODOS.get(body.sessionID) });
    return;
  }

  /**
   * Q3: `question.asked` を外から流す。body: `{"sessionID":"ses_...","multiple":false,"v2":false}`。
   * prompt シーケンスを回さずにカード単体を出せる(permission との同時 pending も作れる)。
   */
  if (req.method === 'POST' && path === '/__stub/question') {
    const body = parseJsonSafe(await readBody(req)) || {};
    if (!body.sessionID) { sendJson(res, 400, { error: 'sessionID required' }); return; }
    const id = `que_${++queSeq}`;
    // `empty:true` は **`options: []` かつ custom 無し**の質問。spec は `options` を required に
    // しているが `minItems` を置いていないので、この形は契約上あり得る(Q3レビュー minor-6)。
    const questions = body.empty === true
      ? [{ question: '入力手段がありません', header: '空', options: [] }]
      : questionFixture(body.multiple === true || WANT_QUESTION_MULTI);
    QUESTIONS.set(id, { sessionID: body.sessionID, questions, answers: null, rejected: false });
    broadcast(body.v2 ? 'question.v2.asked' : 'question.asked', {
      id, sessionID: body.sessionID, questions,
    });
    sendJson(res, 200, { ok: true, requestID: id, questions });
    return;
  }

  // ---------------------------------------------------------------------------
  // Q7: 差分 + VCS + 巻き戻し
  //
  // **patch は実物 serve 1.18.21 の `GET /vcs/diff?mode=git&context=3` が返したバイト列**
  // (採取手順は docs/API_CONTRACT.md §Q7)。このプロジェクトは「フィクスチャが実データと
  // 違う形だったのでテストは全緑のまま症状が残る」を7回繰り返しているので、
  // **近似した文字列を書かない**。
  // ---------------------------------------------------------------------------

  // ---- GET /vcs -> VcsInfo。**required は1つも無い**(実機 /doc)----
  if (req.method === 'GET' && path === '/vcs') {
    vcsInfoCalls++;
    // STUB_VCS_EMPTY=1: branch を持たない応答(git 管理下でないディレクトリの形)。
    sendJson(res, 200, WANT_VCS_EMPTY ? {} : { branch: VCS_BRANCH, default_branch: VCS_DEFAULT_BRANCH });
    return;
  }

  // ---- GET /vcs/status -> VcsFileStatus[](patch を持たない軽い一覧)----
  if (req.method === 'GET' && path === '/vcs/status') {
    vcsStatusCalls++;
    const all = [...VCS_FIXTURE];
    if (WANT_DIFF_BIG) all.push(bigFileDiff());
    if (WANT_DIFF_LONG) all.push(longLineDiff());
    sendJson(res, 200, all.map(({ file, additions, deletions, status }) => ({
      file, additions, deletions, status,
    })));
    return;
  }

  // ---- GET /vcs/diff?mode=git|branch&context=N -> VcsFileDiff[] ----
  //
  // **`mode` は required。** 実物は省略すると 400 を返す(実測):
  //   {"name":"BadRequest","data":{"message":"Missing key\n  at [\"mode\"]","kind":"Query"}}
  // スタブが受け入れてしまうと、アプリが mode を送らない欠陥を見逃す。
  if (req.method === 'GET' && path === '/vcs/diff') {
    const mode = url.searchParams.get('mode');
    VCS_DIFF_QUERIES.push({ mode, context: url.searchParams.get('context') });
    if (!mode) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'Missing key\n  at ["mode"]', kind: 'Query' } });
      return;
    }
    if (!['git', 'branch'].includes(mode)) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: `Invalid mode: ${mode}`, kind: 'Query' } });
      return;
    }
    // mode=branch は既定ブランチとの差。ここでは空(実物でも空だった)。
    if (mode === 'branch') { sendJson(res, 200, []); return; }
    const extra = [];
    if (WANT_DIFF_BIG) extra.push(bigFileDiff());
    if (WANT_DIFF_LONG) extra.push(longLineDiff());
    sendJson(res, 200, extra.length ? [...VCS_FIXTURE, ...extra] : VCS_FIXTURE);
    return;
  }

  // ---------------------------------------------------------------------------
  // Q8: ファイルブラウザ + 検索
  //
  // **required のクエリを検査する。** 実物は `path` / `pattern` / `query` を落とすと 400 を返す
  // (実測: `{"name":"BadRequest","data":{"message":"Missing key\n  at [\"path\"]","kind":"Query"}}`)。
  // スタブが受け入れてしまうと、アプリがキーを落とす欠陥を見逃す。
  // 一方 **`directory` のような任意キーは落としても 200** —— そちらは
  // `__stub/stats` のクエリ記録でしか見えない。
  // ---------------------------------------------------------------------------

  // ---- GET /file?path= -> FileNode[] ----
  if (req.method === 'GET' && path === '/file') {
    const raw = url.searchParams.get('path');
    FILE_LIST_QUERIES.push({ path: raw, directory: url.searchParams.get('directory') });
    if (raw === null) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'Missing key\n  at ["path"]', kind: 'Query' } });
      return;
    }
    const key = normalizeStubPath(raw);
    sendJson(res, 200, FILE_TREE[key] || []);
    return;
  }

  // ---- GET /file/content?path= -> FileContent ----
  if (req.method === 'GET' && path === '/file/content') {
    const raw = url.searchParams.get('path');
    FILE_CONTENT_QUERIES.push({ path: raw, directory: url.searchParams.get('directory') });
    if (raw === null) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'Missing key\n  at ["path"]', kind: 'Query' } });
      return;
    }
    const key = normalizeStubPath(raw);
    // **存在しないパスも 200 `{"type":"text","content":""}` を返す。**
    //
    // 1周目は 400 `{"name":"BadRequest","data":{"message":"No such file: ..."}}` を返していた。
    // **その応答は実サーバーに存在しない。捏造したエラーボディだった。**
    // 実測(2026-08-28、実物 serve 1.18.21)—— 3つはバイト単位で同一(いずれも28バイト):
    //
    //   GET /file/content?path=no/such/file.txt         -> 200 {"type":"text","content":""}
    //   GET /file/content?path=docs/DOES_NOT_EXIST.md   -> 200 {"type":"text","content":""}
    //   GET /file/content?path=<本物の0バイトファイル>   -> 200 {"type":"text","content":""}
    //
    // **スタブが現実より親切だったので、アプリが「0 バイト。読み込みに失敗したわけでは
    // ありません」と断言する欠陥がスタブ由来の dump 全部で不可視だった**
    // (レビュー blocker-1)。RUN_PLAN の一般則「スタブの挙動をゲート条件に書き写した瞬間、
    // スタブの誤りが正解になる」を、このスタブ自身が破っていた。
    const found = FILE_CONTENT[key] || { type: 'text', content: '' };
    const body = { ...found };
    // STUB_FILE_BIG=1: 閾値(512 KiB)を確実に超えさせる。**受信打ち切りの観測用**。
    if (WANT_FILE_BIG && body.type === 'text') {
      body.content = 'BIGSTART\n' + ('y'.repeat(120) + '\n').repeat(6000);
    }
    // STUB_FILE_DIFF=1: `diff` を載せる。**実物は一度も返さない**(実測)ので、
    // §5b スコープ2 の「変更あり」トグルはここでしか測れない。
    if (WANT_FILE_DIFF && body.type === 'text') {
      body.diff = 'diff --git a/' + key + ' b/' + key + '\n' +
        'index 1111111..2222222 100644\n' +
        '--- a/' + key + '\n' +
        '+++ b/' + key + '\n' +
        '@@ -1,3 +1,3 @@\n' +
        ' package stub\n' +
        '-val Dark = 0x000000\n' +
        '+val Dark = 0x0F0F0F\n';
    }
    sendJson(res, 200, body);
    return;
  }

  // ---- GET /file/status -> File[] ----
  //
  // **実物 1.18.21 は変更があっても `[]` を返す**(実測。同時刻の `/vcs/status` は5件返した)。
  // スタブも `[]` を返す —— **食い違ったスタブ挙動を正にしない**(RUN_PLAN の一般則)。
  // アプリはこの口を判断材料にしない。
  if (req.method === 'GET' && path === '/file/status') {
    sendJson(res, 200, []);
    return;
  }

  // ---- GET /find?pattern= -> Match[](ripgrep 形式)----
  //
  // **実物はどんな語でも10件で打ち切る**(実測: `a` でも `e` でも10件)。
  // spec に `limit` は無いのでアプリからは増やせない。
  if (req.method === 'GET' && path === '/find') {
    const pattern = url.searchParams.get('pattern');
    FIND_QUERIES.push({ pattern, directory: url.searchParams.get('directory') });
    if (pattern === null) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'Missing key\n  at ["pattern"]', kind: 'Query' } });
      return;
    }
    if (WANT_FIND_MANY) {
      // ちょうど上限(10件)。**「これ以上あるかもしれない」の注記**を出させる。
      const many = [];
      for (let i = 0; i < 10; i++) {
        many.push({
          path: { text: `app\\src\\gen${i}.kt` },
          lines: { text: `val hit${i} = "${pattern}"\n` },
          line_number: i + 1,
          absolute_offset: i * 10,
          submatches: [{ match: { text: pattern }, start: 12, end: 12 + Buffer.byteLength(pattern, 'utf8') }],
        });
      }
      sendJson(res, 200, many);
      return;
    }
    // 語に当たらなければ空(0件は 404 ではない。実測)。
    const hits = FIND_FIXTURE.filter((m) => m.lines.text.includes(pattern) || m.submatches.some((sm) => sm.match.text === pattern));
    sendJson(res, 200, hits);
    return;
  }

  // ---- GET /find/file?query=&limit= -> string[] ----
  //
  // **`limit` の上限は 200。超えると 400**(実測:
  // `Expected a value less than or equal to 200, got 999`)。
  // **セパレータは混在する**(実物: `"app/src/x.kt"` と `"app\\"` が同じ配列に入る)。
  if (req.method === 'GET' && path === '/find/file') {
    const query = url.searchParams.get('query');
    const limitRaw = url.searchParams.get('limit');
    FIND_FILE_QUERIES.push({ query, limit: limitRaw, directory: url.searchParams.get('directory') });
    if (query === null) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'Missing key\n  at ["query"]', kind: 'Query' } });
      return;
    }
    if (limitRaw !== null && Number(limitRaw) > 200) {
      sendJson(res, 400, {
        name: 'BadRequest',
        data: { message: `Expected a value less than or equal to 200, got ${limitRaw}\n  at ["limit"]`, kind: 'Query' },
      });
      return;
    }
    const all = [];
    for (const entries of Object.values(FILE_TREE)) {
      for (const e of entries) all.push(e.path);
    }
    const hits = all.filter((p) => p.toLowerCase().includes(query.toLowerCase()));
    const limit = Number(limitRaw) > 0 ? Number(limitRaw) : 100;
    sendJson(res, 200, hits.slice(0, limit));
    return;
  }

  // ---- GET /find/symbol?query= -> Symbol[] ----
  //
  // **既定は実物と同じ「常に空」**(LSP が動いていない)。`200 []` からは
  // 「見つからない」と「索引が使えない」が区別できないので、アプリは
  // 校正クエリ(`?query=a`)をもう1本撃つ。**STUB_SYMBOLS=1 で索引が在る側にできる** ——
  // 陽性側は実物では測れない(LSP が動く serve が無い)ので、ここでしか測れない。
  if (req.method === 'GET' && path === '/find/symbol') {
    const query = url.searchParams.get('query');
    FIND_SYMBOL_QUERIES.push({ query, directory: url.searchParams.get('directory') });
    if (query === null) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'Missing key\n  at ["query"]', kind: 'Query' } });
      return;
    }
    if (!WANT_SYMBOLS) { sendJson(res, 200, []); return; }
    const SYMBOLS = [
      {
        name: 'Dark',
        kind: 13,
        location: {
          uri: 'file:///C:/stub/app/src/Theme.kt',
          range: { start: { line: 2, character: 4 }, end: { line: 2, character: 8 } },
        },
      },
      {
        name: 'Light',
        kind: 13,
        location: {
          uri: 'file:///C:/stub/app/src/Theme.kt',
          range: { start: { line: 3, character: 4 }, end: { line: 3, character: 9 } },
        },
      },
    ];
    sendJson(res, 200, SYMBOLS.filter((sym) => sym.name.toLowerCase().includes(query.toLowerCase())));
    return;
  }

  // ---- GET /session/:id/diff?messageID= -> SnapshotFileDiff[] ----
  //
  // **実物では既存200セッションすべてが `[]` を返した**(402 でエージェントが
  // ファイルを変更しないため)。非空を出せるのはこのスタブだけなので、
  // 「非空でどう描かれるか」はスタブでしか測れないことを記録しておく。
  const sesDiffMatch = path.match(/^\/session\/([^/]+)\/diff$/);
  if (req.method === 'GET' && sesDiffMatch) {
    SESSION_DIFF_QUERIES.push({ sessionID: sesDiffMatch[1], messageID: url.searchParams.get('messageID') });
    // **`patch` を落とした要素**を混ぜられるようにする(契約上 `patch` は任意)。
    const snapshot = WANT_DIFF_NO_PATCH
      ? SNAPSHOT_FIXTURE.map((e, i) => (i === 2 ? { file: e.file, additions: e.additions, deletions: e.deletions } : e))
      : SNAPSHOT_FIXTURE;
    sendJson(res, 200, WANT_DIFF ? snapshot : []);
    return;
  }

  // ---- POST /session/:id/revert {messageID, partID?} -> Session ----
  //
  // **ファイルシステムを書き換える操作の模擬**。実際には何も書かないが、
  // `Session.revert` を立てて返す —— アプリはそこを権威として「元に戻す」を出す。
  const revertMatch = path.match(/^\/session\/([^/]+)\/revert$/);
  if (req.method === 'POST' && revertMatch) {
    const ses = SESSIONS.get(revertMatch[1]);
    if (!ses) {
      sendJson(res, 404, { name: 'NotFoundError', data: { message: `Session not found: ${revertMatch[1]}` } });
      return;
    }
    const body = parseJsonSafe(await readBody(req)) || {};
    // spec: `messageID` required / pattern `^msg` / additionalProperties:false。
    // **形を検査する** —— スタブが何でも受けると、アプリの形の誤りを見逃す。
    if (typeof body.messageID !== 'string' || !body.messageID.startsWith('msg')) {
      sendJson(res, 400, { name: 'BadRequest', data: { message: 'messageID must match ^msg', kind: 'Body' } });
      return;
    }
    if (REVERT_FAIL_STATUS > 0) {
      revertFailCount++;
      sendJson(res, REVERT_FAIL_STATUS, {
        name: 'SessionBusyError',
        data: { message: 'Session is busy', sessionID: ses.id },
      });
      return;
    }
    revertCount++;
    REVERTS.push({ sessionID: ses.id, messageID: body.messageID, partID: body.partID ?? null });
    ses.revert = { messageID: body.messageID, ...(body.partID ? { partID: body.partID } : {}) };
    sendJson(res, 200, ses);
    return;
  }

  // ---- POST /session/:id/unrevert -> Session ----
  const unrevertMatch = path.match(/^\/session\/([^/]+)\/unrevert$/);
  if (req.method === 'POST' && unrevertMatch) {
    const ses = SESSIONS.get(unrevertMatch[1]);
    if (!ses) {
      sendJson(res, 404, { name: 'NotFoundError', data: { message: `Session not found: ${unrevertMatch[1]}` } });
      return;
    }
    unrevertCount++;
    delete ses.revert;
    sendJson(res, 200, ses);
    return;
  }

  const msgMatch = path.match(/^\/session\/([^/]+)\/message$/);
  if (req.method === 'GET' && msgMatch) {
    sendJson(res, 200, HISTORY.get(msgMatch[1]) || []);
    return;
  }

  // ---- P4: permission 応答(legacy経路。契約書どおり /session/{sid}/permissions/{pid}) ----
  const permMatch = path.match(/^\/session\/([^/]+)\/permissions\/([^/]+)$/);
  if (req.method === 'POST' && permMatch) {
    const raw = await readBody(req);
    const body = parseJsonSafe(raw) || {};
    const entry = PERMISSIONS.get(permMatch[2]);
    if (!entry) {
      sendJson(res, 404, { error: 'unknown permission' });
      return;
    }
    if (!['once', 'always', 'reject'].includes(body.response)) {
      sendJson(res, 400, { error: 'response must be once|always|reject' });
      return;
    }
    entry.response = body.response;
    log('permission reply:', permMatch[2], body.response);
    sendJson(res, 200, { ok: true });
    return;
  }

  // ---- P4: abort。進行中のシーケンスを世代番号で無効化する ----
  const abortMatch = path.match(/^\/session\/([^/]+)\/abort$/);
  if (req.method === 'POST' && abortMatch) {
    ABORTED.set(abortMatch[1], promptGen);
    abortCount++;
    log('abort:', abortMatch[1], 'gen', promptGen);
    sendJson(res, 200, { ok: true });
    // Q2/STUB_ABORT_IDLE(既定オフ): **実物 serve は abort で完了通知を流す**
    // (2026-08-27 実測: POST /abort の直後に session.status{idle} と session.idle)。
    // 既定オフなのは P4ゲート②/Q0 の証跡が「abort後 eventsSent 不変」を引用しているため。
    // 有効にすると実サーバー相当になり、申し送り Q0-1 の busy 抱え込みは起きない。
    if (WANT_ABORT_IDLE) {
      SESSION_STATUS.delete(abortMatch[1]);
      broadcast('session.status', { sessionID: abortMatch[1], status: { type: 'idle' } });
      broadcast('session.idle', { sessionID: abortMatch[1] });
    }
    return;
  }

  const promptMatch = path.match(/^\/session\/([^/]+)\/prompt_async$/);
  if (req.method === 'POST' && promptMatch) {
    const raw = await readBody(req);
    log('prompt_async body:', raw);
    const body = parseJsonSafe(raw);
    if (!body || !Array.isArray(body.parts)) {
      sendJson(res, 400, { error: 'parts required' });
      return;
    }
    promptCount++;
    res.writeHead(204);
    res.end();
    const text = body.parts.filter((p) => p.type === 'text').map((p) => p.text).join(' ');
    runPromptSequence(promptMatch[1], text); // 非同期でSSEシーケンスを流す
    return;
  }

  const delMatch = path.match(/^\/session\/([^/]+)$/);
  if (req.method === 'DELETE' && delMatch) {
    deleteCount++;
    // Q1: STUB_DELETE_FAIL=<status> で失敗させる。楽観更新のロールバックを観測するための陰性側。
    // 既定0では従来どおり成功する。
    if (DELETE_FAIL_STATUS > 0) {
      log('delete forced failure:', delMatch[1], DELETE_FAIL_STATUS);
      sendJson(res, DELETE_FAIL_STATUS, { name: 'ForcedFailure', data: { message: 'STUB_DELETE_FAIL' } });
      return;
    }
    const ses = SESSIONS.get(delMatch[1]);
    if (!ses) {
      sendJson(res, 404, { name: 'NotFoundError', data: { message: `Session not found: ${delMatch[1]}` } });
      return;
    }
    SESSIONS.delete(delMatch[1]);
    HISTORY.delete(delMatch[1]);
    SESSION_STATUS.delete(delMatch[1]);
    // 実サーバーは 200 + ボディ `true`(実測。以前この行は 204 を返していた)。
    sendJson(res, 200, true);
    broadcast('session.deleted', { sessionID: ses.id, info: ses });
    return;
  }

  // ---- Q9: ターミナル(PTY)----
  if (await handlePty(req, res, url, path)) return;

  log('404', req.method, path);
  sendJson(res, 404, { error: 'not found' });
}

// 全SSE接続への心跳コメント(プロキシ等のアイドル切断対策)
setInterval(() => {
  for (const c of [...sseClients]) {
    try {
      c.write(': ping\n\n');
    } catch {
      sseClients.delete(c);
    }
  }
}, 15000);

// 検証継続のための最終防護線(スタブ自身のクラッシュ=証跡中断を防ぐ)。握りつぶさず記録は残す。
process.on('uncaughtException', (e) => log('uncaughtException (survive):', e && e.stack));
process.on('unhandledRejection', (e) => log('unhandledRejection (survive):', e));

server.listen(PORT, () => {
  log(`opencode stub listening on http://localhost:${PORT} (user=${USER}, chunkMs=${CHUNK_MS}, chunks=${CHUNKS.length})`);
});

// ---------------------------------------------------------------------------
// Q9: ターミナル(PTY)
// ---------------------------------------------------------------------------
//
// **実物に合わせる。捏造しない。** Q8 のレビュー blocker は
// 「スタブが実サーバーに無い 400 を捏造し、テストがその誤りを固定していた」ことだった。
// ここで模擬するのは実測(2026-08-30、実物 serve 1.18.21)で確かめた形だけである:
//
//   POST /pty/{id}/connect-token         ヘッダ無し   -> 403 PtyForbiddenError
//   POST /pty/{id}/connect-token  x-opencode-ticket:1 -> 200 {ticket, expires_in:60}
//   ws  /pty/{id}/connect?ticket=&cursor=             -> 101(**チケットは単回使用**)
//   終了すると PTY は `GET /pty` から消え、`GET /pty/{id}` は 404 になる
//   終了コードは SSE の `pty.exited{id, exitCode}` にしか出ない
//   `DELETE` は `pty.deleted` を流し、**exitCode を持たない**
//
// 環境変数:
//   STUB_PTY_BANNER_MS   接続後にバナーを流すまでの待ち(既定 50)
//   STUB_PTY_EXIT_CODE   `exit` を受けたときに流す終了コード(既定 0)
//   STUB_PTY_SILENT_EXIT 1 なら **pty.exited を流さずに**終わらせる
//                        (アプリ側の `pty-exited-code-unknown` の陽性試験)
//   STUB_PTY_ALTSCREEN   1 なら起動直後に代替画面へ入る出力を流す
//                        (全画面TUI の陰性試験: 壊れた画面ではなく制限の説明が出ること)
//   STUB_PTY_DROP_MS     >0 なら接続を N ms で一方的に切る(再接続と cursor の試験)
//   STUB_PTY_NO_META     1 ならメタフレームを流さない(cursor を知らないままの経路)

const PTY_BANNER_MS = Number(process.env.STUB_PTY_BANNER_MS || 50);
const PTY_EXIT_CODE = Number(process.env.STUB_PTY_EXIT_CODE || 0);
const PTY_SILENT_EXIT = process.env.STUB_PTY_SILENT_EXIT === '1';
const PTY_ALTSCREEN = process.env.STUB_PTY_ALTSCREEN === '1';
const PTY_DROP_MS = Number(process.env.STUB_PTY_DROP_MS || 0);
const PTY_NO_META = process.env.STUB_PTY_NO_META === '1';

/** 実測した `GET /pty/shells` の形(`{path,name,acceptable}`)。 */
const PTY_SHELLS = [
  { path: 'C:\\Windows\\system32\\cmd.exe', name: 'cmd', acceptable: true },
  { path: 'C:\\Program Files\\Git\\bin\\bash.exe', name: 'bash', acceptable: true },
  // **`acceptable:false` を1件混ぜる。** アプリは消さずに「(非推奨)」と印を付ける規則で、
  // その両方向を実機で確かめられないと、印を落とす変異が見えない。
  { path: 'C:\\Windows\\System32\\wsl.exe', name: 'wsl', acceptable: false },
];

/** id -> {info, buffer(Buffer), sockets:Set, exited:boolean} */
const PTYS = new Map();
/** ticket -> {ptyID, used:boolean} */
const PTY_TICKETS = new Map();

const PTY_STATS = {
  createCalls: 0,
  tokenCalls: 0,
  tokenForbidden: 0,
  connectQueries: [],
  ticketReuseRejected: 0,
  inputs: [],
  resizes: [],
  deletes: [],
  exits: [],
};

let ptySeq = 0;

/** 実測のバナーとほぼ同じ形(全画面制御が**本文より先**に来る)。 */
function ptyBannerBytes() {
  const alt = PTY_ALTSCREEN ? '\u001b[?1049h' : '';
  return Buffer.from(
    alt +
      '\u001b[?9001h\u001b[?1004h\u001b[?25l\u001b[2J\u001b[m\u001b[H' +
      'Stub Windows [Version 10.0.0.0]' +
      '\u001b]0;C:\\Windows\\system32\\cmd.exe\u0007' +
      '\u001b[?25h\r\nC:\\stub>',
    'utf8',
  );
}

function ptyInfo(pty) {
  return { ...pty.info };
}

/** 出力を積んで、繋がっている全ソケットへ流す(**多重接続は実物でも可能**)。 */
function ptyEmit(pty, text) {
  const bytes = Buffer.from(text, 'utf8');
  pty.buffer = Buffer.concat([pty.buffer, bytes]);
  for (const s of pty.sockets) wsSendText(s, text);
}

function ptyFinish(pty, code) {
  if (pty.exited) return;
  pty.exited = true;
  PTY_STATS.exits.push({ id: pty.info.id, code, silent: PTY_SILENT_EXIT });
  // **終了すると REST から消える**(実測)。
  PTYS.delete(pty.info.id);
  for (const s of [...pty.sockets]) wsClose(s, 1000);
  pty.sockets.clear();
  // `STUB_PTY_SILENT_EXIT=1` は「イベントが届かなかった」を再現する ——
  // アプリは終了コードを 0 と書かず `pty-exited-code-unknown` を出さねばならない。
  if (!PTY_SILENT_EXIT) broadcast('pty.exited', { id: pty.info.id, exitCode: code });
}

async function handlePty(req, res, url, path) {
  if (req.method === 'GET' && path === '/__stub/pty-stats') {
    sendJson(res, 200, {
      ...PTY_STATS,
      alive: [...PTYS.keys()],
      tickets: [...PTY_TICKETS.entries()].map(([t, v]) => ({ ticket: t, used: v.used })),
    });
    return true;
  }

  if (req.method === 'GET' && path === '/pty/shells') {
    sendJson(res, 200, PTY_SHELLS);
    return true;
  }

  if (req.method === 'GET' && path === '/pty') {
    sendJson(res, 200, [...PTYS.values()].map(ptyInfo));
    return true;
  }

  if (req.method === 'POST' && path === '/pty') {
    const body = (parseJsonSafe(await readBody(req)) || {});
    PTY_STATS.createCalls++;
    const id = `pty_stub${++ptySeq}`;
    const info = {
      id,
      title: body.title || 'terminal',
      command: body.command || 'cmd.exe',
      args: body.args || [],
      cwd: 'C:\\stub',
      status: 'running',
      pid: 4000 + ptySeq,
    };
    const pty = { info, buffer: Buffer.alloc(0), sockets: new Set(), exited: false };
    PTYS.set(id, pty);
    broadcast('pty.created', { info });
    setTimeout(() => {
      if (PTYS.has(id)) ptyEmit(pty, ptyBannerBytes().toString('utf8'));
    }, PTY_BANNER_MS);
    sendJson(res, 200, info);
    return true;
  }

  const one = path.match(/^\/pty\/([^/]+)$/);
  if (one) {
    const pty = PTYS.get(one[1]);
    if (req.method === 'GET') {
      // **終了した PTY は 404**(実測)。
      if (!pty) {
        sendJson(res, 404, { _tag: 'PtyNotFoundError', ptyID: one[1], message: `PTY session not found: ${one[1]}` });
      } else {
        sendJson(res, 200, ptyInfo(pty));
      }
      return true;
    }
    if (req.method === 'PUT') {
      const body = (parseJsonSafe(await readBody(req)) || {});
      if (!pty) {
        sendJson(res, 404, { _tag: 'PtyNotFoundError', ptyID: one[1], message: `PTY session not found: ${one[1]}` });
        return true;
      }
      PTY_STATS.resizes.push({ id: one[1], size: body.size || null, title: body.title ?? null });
      // 実物と同じく、リサイズの直後に全画面の描き直しが流れる。
      if (body.size) ptyEmit(pty, `\u001b[8;${body.size.rows};${body.size.cols}t\u001b[H\r\nC:\\stub>`);
      sendJson(res, 200, ptyInfo(pty));
      return true;
    }
    if (req.method === 'DELETE') {
      PTY_STATS.deletes.push(one[1]);
      if (!pty) {
        sendJson(res, 404, { _tag: 'PtyNotFoundError', ptyID: one[1], message: `PTY session not found: ${one[1]}` });
        return true;
      }
      PTYS.delete(one[1]);
      pty.exited = true;
      for (const s of [...pty.sockets]) wsClose(s, 1000);
      pty.sockets.clear();
      // **`pty.deleted` には exitCode が無い**(実測)。
      broadcast('pty.deleted', { id: one[1] });
      sendJson(res, 200, true);
      return true;
    }
  }

  const token = path.match(/^\/pty\/([^/]+)\/connect-token$/);
  if (token && req.method === 'POST') {
    PTY_STATS.tokenCalls++;
    // **ヘッダが無いと 403**。spec には載っていないが実物はこう振る舞う。
    if (!req.headers['x-opencode-ticket']) {
      PTY_STATS.tokenForbidden++;
      sendJson(res, 403, { _tag: 'PtyForbiddenError', message: 'Invalid PTY connect token request' });
      return true;
    }
    if (!PTYS.get(token[1])) {
      sendJson(res, 404, { _tag: 'PtyNotFoundError', ptyID: token[1], message: `PTY session not found: ${token[1]}` });
      return true;
    }
    const ticket = `stub-ticket-${PTY_STATS.tokenCalls}`;
    PTY_TICKETS.set(ticket, { ptyID: token[1], used: false });
    sendJson(res, 200, { ticket, expires_in: 60 });
    return true;
  }

  return false;
}

// ---- WebSocket(RFC6455 の、この用途に要る分だけ)----

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function wsAccept(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

function wsEncode(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x80 | opcode, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

function wsSendText(socket, text) {
  try { socket.write(wsEncode(0x1, Buffer.from(text, 'utf8'))); } catch { /* 切断済み */ }
}

function wsSendBinary(socket, bytes) {
  try { socket.write(wsEncode(0x2, bytes)); } catch { /* 切断済み */ }
}

function wsClose(socket, code) {
  try {
    const payload = Buffer.alloc(2);
    payload.writeUInt16BE(code, 0);
    socket.write(wsEncode(0x8, payload));
    socket.end();
  } catch { /* 切断済み */ }
}

/** クライアント → サーバーのフレームを解く(**必ずマスクされている**)。 */
function wsReadFrames(buffer, onMessage, onClose) {
  let buf = buffer;
  while (buf.length >= 2) {
    const opcode = buf[0] & 0x0f;
    const masked = (buf[1] & 0x80) !== 0;
    let len = buf[1] & 0x7f;
    let offset = 2;
    if (len === 126) {
      if (buf.length < 4) break;
      len = buf.readUInt16BE(2); offset = 4;
    } else if (len === 127) {
      if (buf.length < 10) break;
      len = Number(buf.readBigUInt64BE(2)); offset = 10;
    }
    const maskLen = masked ? 4 : 0;
    if (buf.length < offset + maskLen + len) break;
    const mask = masked ? buf.subarray(offset, offset + 4) : null;
    const payload = Buffer.from(buf.subarray(offset + maskLen, offset + maskLen + len));
    if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
    buf = buf.subarray(offset + maskLen + len);
    if (opcode === 0x8) { onClose(); return buf; }
    if (opcode === 0x1 || opcode === 0x2) onMessage(payload.toString('utf8'));
  }
  return buf;
}

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const path = decodeURIComponent(url.pathname);
  const m = path.match(/^\/pty\/([^/]+)\/connect$/);
  const ticket = url.searchParams.get('ticket');
  const cursorRaw = url.searchParams.get('cursor');
  PTY_STATS.connectQueries.push({
    path,
    ticket,
    cursor: cursorRaw,
    directory: url.searchParams.get('directory'),
  });

  const entry = ticket ? PTY_TICKETS.get(ticket) : null;
  const pty = m ? PTYS.get(m[1]) : null;
  // **チケットは単回使用**(実測)。使い回しは弾く —— 通すとアプリ側の
  // 「毎回取り直す」が壊れていても気付けない。
  if (!m || !entry || entry.used || entry.ptyID !== m[1] || !pty) {
    if (entry && entry.used) PTY_STATS.ticketReuseRejected++;
    socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  entry.used = true;

  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${wsAccept(req.headers['sec-websocket-key'] || '')}\r\n\r\n`,
  );
  socket.setNoDelay(true);
  pty.sockets.add(socket);

  // リプレイ。`cursor=-1` はライブのみ、それ以外は N バイト目から。
  const cursor = cursorRaw === null ? 0 : Number(cursorRaw);
  const replayFrom = cursor < 0 ? pty.buffer.length : Math.min(cursor, pty.buffer.length);
  const replay = pty.buffer.subarray(replayFrom);
  // **実物と同じ順序**: リプレイ本文がある接続では本文が先、メタが後。
  if (replay.length > 0) wsSendText(socket, replay.toString('utf8'));
  if (!PTY_NO_META) {
    wsSendBinary(socket, Buffer.concat([Buffer.from([0x00]), Buffer.from(JSON.stringify({ cursor: pty.buffer.length }), 'utf8')]));
  }

  let pending = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    pending = wsReadFrames(
      Buffer.concat([pending, chunk]),
      (text) => {
        PTY_STATS.inputs.push(text);
        // エコーして「実行」する。**`exit` だけを特別扱いする**(終了コードの試験)。
        ptyEmit(pty, text.replace(/\r/g, '\r\n'));
        const line = text.replace(/[\r\n]/g, '');
        if (line === 'exit') {
          setTimeout(() => ptyFinish(pty, PTY_EXIT_CODE), 30);
        } else if (line.startsWith('echo ')) {
          ptyEmit(pty, `\u001b[?25l${line.slice(5)}\u001b[7;1HC:\\stub>\u001b[?25h`);
        } else if (line.length > 0) {
          ptyEmit(pty, `'${line}' は認識されていません。\r\nC:\\stub>`);
        }
      },
      () => {
        pty.sockets.delete(socket);
        socket.destroy();
      },
    );
  });
  socket.on('error', () => pty.sockets.delete(socket));
  socket.on('close', () => pty.sockets.delete(socket));

  if (PTY_DROP_MS > 0) {
    setTimeout(() => {
      if (pty.sockets.has(socket)) {
        pty.sockets.delete(socket);
        socket.destroy();
      }
    }, PTY_DROP_MS);
  }
});
