package dev.opencode.android.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSONパーサ設定。サーバーは契約書に無いフィールド(cost, tokens, share等)を
 * 返しうるため未知フィールドは無視する(docs/API_CONTRACT.md「推測でフィールド名を書かない」)。
 *
 * encodeDefaults=true はリクエスト側の契約適合のため必須:
 * 既定(false)だとデフォルト値付きフィールドがJSONから落ちる
 * (実測バグ: PromptTextPartInput.type の既定値"text"がエンコードされず
 *  {"parts":[{"text":"..."}]} が送信され、サーバーがtype判定でテキストを取りこぼした)。
 *
 * **explicitNulls=false は Q4 で足した(実測で必要になった)。**
 * `encodeDefaults=true` だけだと `null` のフィールドが `"agent":null` として**送信され**、
 * spec が `additionalProperties:false` かつ `agent: {type:string}` としている
 * `POST /session` は **400 BadRequest** を返す。実測(2026-08-27、実物 serve 4097):
 *
 *     POST /session {"title":"Q4-nulltest","agent":null,"model":null} -> 400 {"_tag":"BadRequest"}
 *
 * つまり「モデル未指定で新規作成する」という**既定の操作が丸ごと壊れる**。
 * 省略すれば通る(`{"title":"..."}` は 200)ので、null は書かない。
 * 復号側にも効くが、方向は緩む側(明示 null をデフォルト値として受ける)なので害は無い。
 */
val contractJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** GET /global/health のレスポンス(API_CONTRACT.md: {healthy: boolean, version: string}) */
@Serializable
data class HealthDto(
    val healthy: Boolean,
    val version: String,
)

/** Session.time(API_CONTRACT.md: {"created": 0, "updated": 0} / エポックミリ秒) */
@Serializable
data class SessionTimeDto(
    val created: Long = 0,
    val updated: Long = 0,
)

/**
 * Session(API_CONTRACT.md「Session (必須フィールドのみ)」)。
 * id以外は欠損に耐えるようデフォルト付き。契約に無いフィールドは持たない。
 */
@Serializable
data class SessionDto(
    val id: String,
    val slug: String? = null,
    val projectID: String? = null,
    val directory: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: SessionTimeDto? = null,
    /**
     * Q4: このセッションの主エージェント名。**任意**(spec の required に無い)。
     * 欠けている = 「サーバー既定に従う」であって「エージェントが無い」ではない。
     */
    val agent: String? = null,
    /**
     * Q4: このセッションのモデル。**任意**。形は [ModelRefDto](`{id, providerID, variant?}`)で、
     * `prompt_async` や `Agent.model` の `{providerID, modelID}` とは**キー名が違う**
     * (API_CONTRACT.md「モデル参照の形が3種類ある」)。
     */
    val model: ModelRefDto? = null,
    /**
     * Q7: 巻き戻しの現在地。**null = 巻き戻していない**。
     *
     * `POST /session/{id}/revert` と `POST /session/{id}/unrevert` はどちらも
     * この `Session` を返すので、「元に戻す」を出すかどうかの**権威はここ**である。
     * 画面が自分で覚えると、他クライアント(TUI/CLI)の revert に追随できない。
     */
    val revert: SessionRevertDto? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: id
}

/**
 * POST /session のリクエストボディ。
 *
 * Q4 で `agent` / `model` を足した(実測: 200 で返る `Session` に両方反映される)。
 * **null は送ってはならない** —— `{"title":"x","agent":null}` は 400 BadRequest である。
 * [contractJson] の `explicitNulls = false` がそれを保証している。
 */
@Serializable
data class CreateSessionRequest(
    val title: String,
    val agent: String? = null,
    val model: ModelRefDto? = null,
)

/**
 * PATCH /session/{sessionID} のリクエストボディ(API_CONTRACT.md「Q1で使用する分」)。
 *
 * spec の requestBody は `{title?, metadata?, permission?, time?{archived?}}` で
 * `additionalProperties: false`。**契約に無いキーを送ると400になりうる**ので、
 * Q1が使う `title` だけを持つ型にする(改名以外の用途はQ5以降で足す)。
 */
@Serializable
data class UpdateSessionRequest(
    val title: String,
)

/**
 * SessionStatus(spec `components.schemas.SessionStatus`、anyOf 3種)。
 *
 * required は種別ごとに違う: idle/busy は `type` のみ、retry は `type` `attempt` `message` `next`。
 * **アプリが表示に使うのは `type` と(retry時の)`attempt` だけ**なので、他は任意で受ける。
 * 未知の `type` 文字列が増えても落とさないよう String で受ける(判定は [runState])。
 */
@Serializable
data class SessionStatusDto(
    val type: String? = null,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null,
    /**
     * Q2 で追加(RUN_PLAN「Q2への申し送り — `SessionStatus.retry` の `action`」)。
     * spec 上 `action` 自体は**任意**だが、存在するなら
     * `reason` `provider` `title` `message` `label` が required。`link` だけ任意。
     * 全て任意で受けるのは、欠けたときにイベントごと落とさないため —— retry を**表示するための**
     * 変更が、パース失敗で retry を**表示しなくする**変更になった前例(L3 `jsonPrimitive`)がある。
     */
    val action: SessionStatusActionDto? = null,
)

/**
 * `SessionStatus.retry.action`(spec: `components.schemas.SessionStatus` の anyOf 2番目)。
 * required は `reason` `provider` `title` `message` `label`、`link` は任意。
 * **フィクスチャはこの required を満たすこと**(API_CONTRACT.md)。
 */
@Serializable
data class SessionStatusActionDto(
    val reason: String? = null,
    val provider: String? = null,
    val title: String? = null,
    val message: String? = null,
    val label: String? = null,
    val link: String? = null,
)

/**
 * Message(UserMessage | AssistantMessage anyOf)のうち実装が依存してよい必須3フィールド。
 * id/sessionID/role は両バリアントともrequired(API_CONTRACT.md、2026-08-23に実機 /doc で確認)。
 * role: "user" | "assistant"。それ以外の値が来てもクラッシュさせないようStringで受ける。
 */
@Serializable
data class MessageInfoDto(
    val id: String? = null,
    val sessionID: String? = null,
    val role: String? = null,

    // ---- Q4: AssistantMessage のメタ(§5 Q4 スコープ4) ----
    //
    // **assistant はフラット、user はネスト**である(API_CONTRACT.md の実測):
    //   assistant: {..., "providerID":"mistral", "modelID":"mistral-small-latest", "cost":0, "tokens":{...}}
    //   user     : {..., "model":{"providerID":"mistral","modelID":"mistral-small-latest"}}
    // 1つの型で両方受けるので**両方のキーを持つ**。role で読み分けるのは表示側の仕事。
    // どれも任意 —— 402 で失敗した往復でも providerID/modelID は載っていたが、
    // 必須にすると載っていない実装/経路で **履歴が丸ごとパースできなくなる**。
    val providerID: String? = null,
    val modelID: String? = null,
    val agent: String? = null,
    val cost: Double? = null,
    val tokens: MessageTokensDto? = null,
    /** UserMessage 側のネストしたモデル参照。**`{providerID, modelID}`**(`id` ではない)。 */
    val model: PromptModelRefDto? = null,
)

/**
 * `AssistantMessage.tokens`(spec required: `input` `output` `reasoning` `cache`)。
 * **すべて任意で受ける**。数値が1つ欠けただけで履歴が消えるほうが害が大きい。
 */
@Serializable
data class MessageTokensDto(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: MessageCacheTokensDto? = null,
)

@Serializable
data class MessageCacheTokensDto(
    val read: Long? = null,
    val write: Long? = null,
)

/**
 * ToolPart.state(spec: `ToolState` = Pending|Running|Completed|Error の anyOf)。
 *
 * Q2 で `input` / `title` / `output` / `error` を足した(ツール活動カードの展開表示)。
 * バリアントごとに required が違う(Pending: status/input/raw、Running: status/input/time、
 * Completed: status/input/output/title/metadata/time、Error: status/input/error/time)ので、
 * **1つの型で全部を任意として受ける**。`input` は spec 上 `type: object` としか決まっておらず
 * 中身の形はツールごとに違うため、[JsonObject] のまま持って表示直前に文字列化する
 * (`String` と決め打つと SerializationException でイベントごと消える。L3 と同じ形)。
 */
@Serializable
data class ToolStateDto(
    val status: String? = null,
    val input: JsonObject? = null,
    val title: String? = null,
    val output: String? = null,
    val error: String? = null,
)

/**
 * Part(anyOf — type判別)。TextPartのフィールドは契約ピン留め済み。tool等その他typeは
 * 共通ベース(id/sessionID/messageID/type) + 契約記載の callID/tool/state.status のみ持つ。
 * 全フィールドデフォルト付きで、欠損や未知フィールド(ignoreUnknownKeys)に耐える。
 */
@Serializable
data class PartDto(
    val id: String? = null,
    val sessionID: String? = null,
    val messageID: String? = null,
    val type: String? = null,
    /** TextPart.text。 */
    val text: String? = null,
    val synthetic: Boolean? = null,
    /** ToolPart。 */
    val callID: String? = null,
    val tool: String? = null,
    val state: ToolStateDto? = null,
    /**
     * Q7: `PatchPart.files`(spec required: `id` `sessionID` `messageID` `type` `hash` `files`)。
     *
     * **「このメッセージが何ファイル変えたか」はここにしか無い。** サーバーへ問い合わせずに
     * 「N ファイル変更」のチップを出せるのはこのフィールドのおかげで、
     * チップを押したときに初めて `GET /session/{id}/diff` を引く(§5b Q7 スコープ3)。
     *
     * required だが **nullable で受ける** —— 型が required を主張しても、
     * このプロジェクトは required 宣言で3回壊れている(P4 の metadata / P4 の
     * PermissionReplied / L3 の error)。
     */
    val files: List<String>? = null,
    /** `PatchPart.hash` / `SnapshotPart.snapshot`。表示には使わないが同一性の手掛かり。 */
    val hash: String? = null,
    val snapshot: String? = null,
)

/** GET /session/{id}/message の1要素: {info: Message, parts: Part[]}。infoは欠損に耐える。 */
@Serializable
data class MessageEntryDto(
    val info: MessageInfoDto? = null,
    val parts: List<PartDto> = emptyList(),
)

/** PromptInput.parts の要素。MVPはテキスト1パートのみ(API_CONTRACT.md PromptInput)。 */
@Serializable
data class PromptTextPartInput(
    val type: String = "text",
    val text: String,
)

/** POST /session/{id}/prompt_async のボディ。parts必須、MVPは他フィールドを送らない。 */
@Serializable
data class PromptInput(
    val parts: List<PromptTextPartInput>,
)

/** 一覧は time.updated の降順(新しいものが上)。updated不明は末尾。 */
fun List<SessionDto>.sortedForDisplay(): List<SessionDto> =
    sortedByDescending { it.time?.updated ?: Long.MIN_VALUE }

/**
 * `PermissionRequest`(spec required: `id`(^per) `sessionID` `permission` `patterns` `metadata`
 * `always`。`tool` は任意)。**`GET /permission` の要素であり、`permission.asked` の
 * properties と同じ形**である —— `GET /question` と `question.asked` の関係(Q3)と対称。
 *
 * Q6 でこの型に置き換わる前は `PermissionAskedDto` / `PermissionRepliedDto` という
 * **どこからも参照されていない2つの型**がここにあり、両方とも `metadata: String?` を
 * 宣言していた。それは P4 で「承認ダイアログが一度も出ない」を起こした宣言そのもので、
 * 死んだコードとして残っていた。**次に誰かが使ったら同じ欠陥が戻る形**なので消した。
 */
@Serializable
data class PermissionRequestDto(
    val id: String,
    val sessionID: String? = null,
    val permission: String,
    val patterns: List<String> = emptyList(),
    /**
     * **`String?` にしないこと。** 実測(P4 / 実物 serve)で来るのはオブジェクトであり、
     * `String?` と宣言した版は `permission.asked` を全て `Ignored` に落として
     * **承認ダイアログが一度も出なかった**(TEST_REPORT P4 の欠陥1)。
     * ここは `PermissionAskedEvent.metadata` と**同じ宣言**にしてある。
     */
    val metadata: JsonElement? = null,
    val always: List<String> = emptyList(),
    val tool: PermissionToolDto? = null,
)

/** tool オブジェクト(任意)。 */
@Serializable
data class PermissionToolDto(
    val messageID: String? = null,
    val callID: String? = null,
)

/** POST /session/{sessionID}/permissions/{permissionID} のリクエストボディ。response: "once" | "always" | "reject"。 */
@Serializable
data class PermissionReplyRequest(
    val response: String,
)

// ---- Q3: Todo + Question ----

/**
 * `Todo`(spec: `components.schemas.Todo`。required は `content` `status` `priority` の3つ)。
 *
 * **`status` を enum で受けない。** spec の型は `type: string` であって enum ではなく、
 * description は `pending, in_progress, completed, cancelled` の**4値**を挙げている
 * (計画書 §5 Q3 スコープ1 は3値と書いていて spec より狭い)。
 * enum で decode すると `cancelled` が1件混ざっただけで `todo.updated` のパースが失敗し、
 * **タスクリストを見せるための変更がタスクリストを見せなくする**(L3 `jsonPrimitive` と同じ形)。
 * 未知の値も文字列のまま受け、表示側の [dev.opencode.android.ui.todoStatusOf] で寄せる。
 *
 * `priority` は required だが表示には使っていない。**required を欠かさないため**に持つ
 * (フィクスチャが required を落とすと、実物との食い違いに気づけなくなる)。
 * 実測(2026-08-27、実物 serve の実セッション)でもこの3キーちょうどが返った。
 */
@Serializable
data class TodoDto(
    val content: String = "",
    val status: String? = null,
    val priority: String? = null,
)

/**
 * `QuestionOption`(spec required: `label` `description` —— **description も required**)。
 * `label` が `answers` に載る値そのものなので、ここだけは欠けると回答を組み立てられない。
 */
@Serializable
data class QuestionOptionDto(
    val label: String = "",
    val description: String? = null,
)

/**
 * `QuestionInfo`(spec required: `question` `header` `options`。`multiple` / `custom` は任意)。
 * 任意なので**欠けたら false**。ここを非nullで宣言すると質問イベントごと落ちる。
 */
@Serializable
data class QuestionInfoDto(
    val question: String = "",
    val header: String? = null,
    val options: List<QuestionOptionDto> = emptyList(),
    val multiple: Boolean? = null,
    val custom: Boolean? = null,
)

/**
 * `QuestionRequest`(spec required: `id`(^que) `sessionID` `questions`。`tool` は任意)。
 * `GET /question` の要素であり、`question.asked` の properties と**同じ形**。
 */
@Serializable
data class QuestionRequestDto(
    val id: String? = null,
    val sessionID: String? = null,
    val questions: List<QuestionInfoDto> = emptyList(),
    val tool: PermissionToolDto? = null,
)

/**
 * `POST /question/{requestID}/reply` のボディ。spec required は `answers` のみ。
 *
 * **`answers` は `string[][]`**: 外側が質問の並び(「in order of questions」)、
 * 内側が**選んだラベルの配列**(`QuestionAnswer = {type:array, items:{type:string}}`)。
 * 組み立ては [dev.opencode.android.ui.buildQuestionAnswers] に純関数として置いてある。
 */
@Serializable
data class QuestionReplyRequest(
    val answers: List<List<String>>,
)

// ---- Q4: モデル / エージェント選択 ----

/**
 * `ModelRef`(spec required: `id` `providerID`。`variant` は任意)。
 *
 * **これは3種類あるモデル参照のうちの1つである**(API_CONTRACT.md「モデル参照の形が3種類ある」):
 *  - `ModelRef` = `{id, providerID, variant?}`  … `POST /session` の `model`、`Session.model`、
 *    `POST /api/session/{id}/model` の `model`、`session.next.model.switched` の `model`
 *  - `{providerID, modelID}`                    … `prompt_async` の `model`、`UserMessage.model`
 *  - `{modelID, providerID}`                    … `Agent.model`
 *
 * **`id` と `modelID` を取り違えると、サーバーは 400 を返さず 204 で受理して黙って無視する**
 * (実測 #4: 存在しないモデルも 204)。P4 の `permission.asked{id}` /
 * `permission.replied{requestID}` と同じ罠なので、型を分けて取り違えようがなくする。
 */
@Serializable
data class ModelRefDto(
    val id: String,
    val providerID: String,
    val variant: String? = null,
)

/**
 * `prompt_async` / `UserMessage` 側のモデル参照 —— **`{providerID, modelID}`**。
 * [ModelRefDto] と**混ぜてはならない**ので別の型にしてある。
 *
 * アプリはこれを**送信には使わない**(API_CONTRACT.md 実測 #3: `prompt_async` に `model` を
 * 載せるとセッションのモデルが恒久的に書き換わる)。受信の読み取り専用。
 */
@Serializable
data class PromptModelRefDto(
    val providerID: String? = null,
    val modelID: String? = null,
)

/**
 * `Model.cost`(**正本はピン留めした `docs/spec/opencode-1.18.21-openapi.json` の
 * `components.schemas.Model.properties.cost`**)。
 *
 * **`models.dev/api.json` の生形をそのまま書いてはならない。** 1周目の実装は models.dev 側の
 * `cache_read` / `cache_write` / `context_over_200k` というキー名で宣言していたが、
 * serve が返すのは**変換後の形**で、キー名が一つも一致しない ——
 * `@SerialName` が当たらないので**サイレントに全部 null**になっていた(レビュー blocker-1)。
 *
 * ピン留め済み spec の形(`additionalProperties:false`、required は `input` `output` `cache`):
 *
 * ```
 * Cost = { input:number, output:number,
 *          cache:{read:number, write:number},
 *          tiers?:{input,output,cache:{read,write}, tier:{type:"context", size:number}}[],
 *          experimentalOver200K?:{input,output,cache:{read,write}} }
 * ```
 *
 * 採取証跡(`e2e-artifacts/Q10Q11/provider.json`)でも全モデルが
 * `"cost":{"input":..,"output":..,"cache":{"read":..,"write":..}}` の形だった。
 *
 * 単位は per 1M tokens の USD。spec が required としているものも**アプリ側は全部 optional で受ける**
 * (「無い」と「取れなかった」を区別するため。欠けたら表示しない)。
 */
@Serializable
data class CostDto(
    val input: Double? = null,
    val output: Double? = null,
    val cache: CostCacheDto? = null,
    val tiers: List<CostTierDto>? = null,
    val experimentalOver200K: CostTierDto? = null,
)

/** `Cost.cache` —— **ネストしたオブジェクト**である(`{read, write}`)。フラットな `cache_read` ではない。 */
@Serializable
data class CostCacheDto(
    val read: Double? = null,
    val write: Double? = null,
)

/**
 * `Cost.tiers[]` の1件、および `Cost.experimentalOver200K`。
 *
 * spec 上 `tiers[]` は `tier:{type:"context", size:number}` を required で伴い、
 * `experimentalOver200K` は `tier` を**持たない**。同じ型で受け、
 * `experimentalOver200K` 側では `tier` が null のままになる。
 */
@Serializable
data class CostTierDto(
    val input: Double? = null,
    val output: Double? = null,
    val cache: CostCacheDto? = null,
    val tier: CostTierRefDto? = null,
)

/**
 * `tiers[].tier` —— 段階の境目。`size` が実際の閾値(トークン数)である。
 *
 * **`size` は `Double` である。** ピン留め済み spec の
 * `components.schemas.Model.properties.cost.items.properties.tier.properties.size` は
 * `{"type":"number"}` であって `integer` ではない。1周目は「トークン数だから整数だろう」と
 * `Long` で宣言していたが、これは blocker-1(models.dev の生形をそのまま書いた)と
 * **同じ形の欠陥** —— 契約ではなく実物の見た目に合わせた宣言である。
 *
 * 実害の形も同じで、しかも重い: `Long` 宣言のまま `200000.5` が1件でも来ると
 * kotlinx.serialization が例外を投げ、**`GET /provider` の応答全体(200件超のモデル)の
 * デコードが失敗してカタログが丸ごと消える**(E2E ゲートが実機で対照実験して確認。
 * `200000.5` → `200000` に戻すだけで復帰した)。
 * 非整数のトークン数は現実には来ないが、**契約が許している形で落ちてはならない**。
 *
 * 表示側の丸めは [dev.opencode.android.ui.tierThresholdLabel] が持つ。
 */
@Serializable
data class CostTierRefDto(
    val type: String? = null,
    val size: Double? = null,
)

/**
 * `Provider.models` の1件(spec `Model`)。
 *
 * spec の required は11個(`id` `providerID` `api` `name` `capabilities` `cost` `limit`
 * `status` `options` `headers` `release_date`)あるが、**アプリが宣言するのは表示に使う分だけ**。
 * `ignoreUnknownKeys` があるので残りは materialize されない —— これは飾りではなく
 * **5.4 MiB の応答を端末で捌くための実装上の要**である(API_CONTRACT.md の実測)。
 */
@Serializable
data class ProviderModelDto(
    val id: String = "",
    val providerID: String? = null,
    val name: String? = null,
    val family: String? = null,
    /** 実測では `"active"`。spec 上 required だが未知値でも落とさない。 */
    val status: String? = null,
    /**
     * **Q4 レビュー major-2 で足した。** 当初は「表示に使わないから」と捨てていたが、
     * 捨てた結果 **チャットに使えないモデルを「既定」として最上位に出していた**。
     *
     * 実測(2026-08-27、実物 serve の connected 8社600モデル):
     *
     * | provider | `default` | 実体 | toolcall |
     * |---|---|---|---|
     * | groq | `whisper-large-v3-turbo` | Whisper(音声認識) | **false** |
     * | google | `gemini-3-pro-image-preview` | Nano Banana Pro(画像) | **false** |
     * | openrouter | `google/gemini-3-pro-image-preview` | 同上 | **false** |
     * | mistral | `voxtral-small-latest` | Voxtral(音声) | true |
     *
     * opencode はツール実行が本体なので `toolcall:false` のモデルを選んだセッションは
     * 実用にならない。**サーバーは検証せず 204 で受理する**(API_CONTRACT.md 実測 #4)ので、
     * 選ばせない責任はクライアントにしかない。
     *
     * 判断材料を持たない設計は「捨てても表示は動く」ので**壊れて見えない**。
     * これがこのプロジェクトの欠陥の形そのものだった。
     */
    val capabilities: ModelCapabilitiesDto? = null,
    /**
     * Q10: モデルのコスト。`models.dev` が正本で、opencode serve の `GET /provider` が
     * 同じ `Cost` をプロキシする。**null = 未取得/未提供**であり「無料」とは区別する
     * (HANDOFF 中核原則「無いと取れなかったを区別する」)。
     *
     * デコードの隔離は [TolerantModelMapSerializer] が**モデル単位で**行う
     * (以前はここに `cost` 専用の serializer を付けていた。同 serializer の doc を参照)。
     */
    val cost: CostDto? = null,
)

/**
 * `Provider.models` を**壊れても1モデルで止める**ためのシリアライザ(Q10/Q11 差し戻し回収)。
 *
 * **このプロジェクトは同じ形の障害を3回起こしている。**
 *  1. `CostDto` のキー名が契約と違った(レビュー blocker-1)
 *  2. `CostTierRefDto.size` の型が契約と違った(1回目の E2E ゲート blocker)
 *  3. `capabilities` 部分木(`ModelCapabilitiesDto` → `ModelModalityDto` の2段ネスト)を
 *     壊すと**同じ形で全滅する**ことが2回目の E2E ゲートで実機確認された
 *
 * 3回とも壊れたフィールドは違うのに、**壊れ方は同じ**だった ——
 * `GET /provider` は1モデルの1フィールドのデコード失敗が
 * **応答全体(203プロバイダ / 7,338モデル)を道連れ**にし、一覧が丸ごと `catalog-failed` になる。
 * 2回目までの手当ては壊れたフィールドごとの serializer(`cost` 専用)で、
 * これは**次の1件**を防げない —— 現に3件目は `capabilities` に来た。
 * **フィールド単位の対症療法をやめ、増幅器そのものを切る。**
 *
 * ここでは1プロバイダの `models` を `{modelID: JsonElement}` として受け、
 * **各モデルを独立に** [ProviderModelDto] へ変換する。1件が失敗しても、
 * そのモデル以外は 7,337 件すべてが一覧に残る。
 *
 * 失敗したモデルの扱いは2段:
 *  1. まず `cost` / `capabilities` の**部分木を落として**もう一度読む。表示に要る
 *     `id` / `name` が生きているなら、**そのモデル自身も一覧に残す**
 *     (`cost = null` = 未取得、`capabilities = null` = 分からない。
 *     [dev.opencode.android.ui.ModelCapabilitiesDto.canRunSession] は null を通す)
 *  2. それでも読めない(`id` が数値である等、モデルの骨格が契約と違う)なら**その1件だけ捨てる**
 *
 * **メモリの根拠(実測)。** `cost` だけを隔離した前版は「モデル単位の隔離は
 * 応答全体を `JsonObject` に materialize するので 5.4 MiB を端末で捌けなくなる」と書いていたが、
 * これは**規模を測らずに書いた**。実際に materialize されるのは
 * **1プロバイダぶんの `models` サブツリー**だけで、しかもプロバイダごとに使い捨てられる ——
 * 応答全体が同時にツリーとして生きることはない。1モデルの JSON は採取証跡
 * `e2e-artifacts/Q10Q11/provider.json` の実測で **566〜596 バイト**(5件、中央値 587)、
 * API_CONTRACT の実測(5.4 MiB / 7,338 モデル)からの平均も **約 770 バイト**である。
 * [ProviderModelDto] の `ignoreUnknownKeys` 前提も壊れない:
 * 捨てているフィールドがツリーとして生きるのは**そのモデルを読んでいる間だけ**で、
 * 保持されるのは今までどおり宣言済みの6フィールドだけである。
 *
 * `ProviderDto.models` の型(`Map<String, ProviderModelDto>`)は変わらないので、
 * `selectableModels` / `excludedModelCount` 等の呼び出し側は影響を受けない。
 */
object TolerantModelMapSerializer : KSerializer<Map<String, ProviderModelDto>> {
    private val delegate = MapSerializer(String.serializer(), ProviderModelDto.serializer())
    private val elements = MapSerializer(String.serializer(), JsonElement.serializer())
    private val model = ProviderModelDto.serializer()

    /** 壊れていたら**落として読み直す**部分木。実際に3回壊れたのはこの2つだけである。 */
    private val prunable = listOf("cost", "capabilities")

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, ProviderModelDto>) {
        encoder.encodeSerializableValue(delegate, value)
    }

    override fun deserialize(decoder: Decoder): Map<String, ProviderModelDto> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val raw = jsonDecoder.decodeSerializableValue(elements)
        val out = LinkedHashMap<String, ProviderModelDto>(raw.size)
        for ((key, element) in raw) {
            val decoded = decodeModel(jsonDecoder.json, element) ?: continue
            out[key] = decoded
        }
        return out
    }

    /** 1件ぶん。読めなければ部分木を落として再挑戦し、それでも駄目なら null(= この1件は捨てる)。 */
    private fun decodeModel(json: Json, element: JsonElement): ProviderModelDto? {
        tryDecode(json, element)?.let { return it }
        val obj = element as? JsonObject ?: return null
        if (prunable.none { it in obj }) return null
        val pruned = JsonObject(obj.filterKeys { it !in prunable })
        return tryDecode(json, pruned)
    }

    private fun tryDecode(json: Json, element: JsonElement): ProviderModelDto? = try {
        json.decodeFromJsonElement(model, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * `Model.capabilities`(spec required: `temperature` `reasoning` `attachment` `toolcall`
 * `input` `output` `interleaved`)。**アプリが読むのは絞り込みに使う3つだけ**。
 *
 * すべて nullable。**欠けている = 「分からない」であって「できない」ではない**ので、
 * 絞り込みは「サーバーが明示的に false と言ったものだけを外す」向きに倒す
 * ([dev.opencode.android.ui.ModelChoice.canRunSession])。逆向きに倒すと、
 * capabilities を返さないサーバーで**選べるモデルが1件も出なくなる** ——
 * R3 の導線を丸ごと閉じることになる。
 */
@Serializable
data class ModelCapabilitiesDto(
    val toolcall: Boolean? = null,
    val reasoning: Boolean? = null,
    val input: ModelModalityDto? = null,
    val output: ModelModalityDto? = null,
)

/** `capabilities.input` / `capabilities.output`(spec required: text/audio/image/video/pdf)。 */
@Serializable
data class ModelModalityDto(
    val text: Boolean? = null,
    val audio: Boolean? = null,
    val image: Boolean? = null,
    val video: Boolean? = null,
    val pdf: Boolean? = null,
)

/**
 * `Provider`(spec required: `id` `name` `source` `env` `options` `models`)。
 * `models` は **`{"<modelID>": Model}`** のマップ(配列ではない)。
 */
@Serializable
data class ProviderDto(
    val id: String = "",
    val name: String? = null,
    /** `env` | `config` | `custom` | `api`。未知値でも落とさないので String。 */
    val source: String? = null,
    /** **1モデルのデコード失敗を1モデルに閉じ込める**([TolerantModelMapSerializer] の doc)。 */
    @Serializable(with = TolerantModelMapSerializer::class)
    val models: Map<String, ProviderModelDto> = emptyMap(),
)

/**
 * `GET /provider` のレスポンス(spec required: `all` `default` `connected` の**3つとも**)。
 *
 * 実測(2026-08-27、実物 serve): `all` 203件 / モデル総数 7,338 / 応答 5.4 MiB、
 * `connected` 8件でモデル 600件。**`all` を画面に出さない**。`connected` で絞る。
 * `default` は **203件すべて**について既定modelIDを持つ(connected だけではない)。
 */
@Serializable
data class ProvidersDto(
    val all: List<ProviderDto> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

/**
 * `Agent.model` —— **`{modelID, providerID}`**(spec required は両方)。
 * 実測で `model` を持っていたのは17件中 `orchestrator` 1件だけだった。
 */
@Serializable
data class AgentModelDto(
    val modelID: String? = null,
    val providerID: String? = null,
)

/**
 * `Agent`(spec required: `name` `mode` `permission` `options`)。
 *
 * **`hidden` は任意なので欠けたら false**、`description` も任意。
 * `mode` は `subagent` | `primary` | `all` の enum だが、**String で受ける** ——
 * 未知の mode が1件増えただけで `GET /agent` 全体が decode 失敗するのは、
 * このプロジェクトが繰り返した形そのものである。
 */
@Serializable
data class AgentDto(
    val name: String = "",
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean? = null,
    val model: AgentModelDto? = null,
    val variant: String? = null,
)

/** `POST /api/session/{sessionID}/model` のボディ(spec required: `model`)。 */
@Serializable
data class SwitchModelRequest(
    val model: ModelRefDto,
)

/** `POST /api/session/{sessionID}/agent` のボディ(spec required: `agent`)。 */
@Serializable
data class SwitchAgentRequest(
    val agent: String,
)

// ---------------------------------------------------------------------------
// Q7: 差分表示 + VCS + 巻き戻し
// ---------------------------------------------------------------------------

/**
 * `SnapshotFileDiff`(`GET /session/{sessionID}/diff` の要素)。
 *
 * **required は `additions` / `deletions` の2つだけ**(実機 `/doc` 2026-08-27、
 * SHA-256 が `docs/spec/opencode-1.18.21-openapi.json` と一致)。
 * QUALITY_PLAN §5b が `{file, patch, additions, deletions, status}` と全部あるかのように
 * 書いているのは**採取時の要約であって required ではない** —— `file` も `patch` も `status` も
 * 欠けうるので、全部 nullable で受ける。
 *
 * ここを required 宣言すると、P4 の `PermissionRepliedEvent`(「id だけの replied」を
 * 必須宣言して**応答成功後もダイアログが閉じない**)と同じ壊れ方をする。
 */
@Serializable
data class SnapshotFileDiffDto(
    val file: String? = null,
    /** unified diff の文字列。パースは [dev.opencode.android.ui.parseUnifiedDiff]。 */
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** `"added"` | `"deleted"` | `"modified"`。**enum にしない**(未知値で全体が落ちる)。 */
    val status: String? = null,
)

/**
 * `VcsInfo`(`GET /vcs`)。**required が1つも無い**(実機 `/doc`)。
 * 実測値: `{"branch":"master","default_branch":"master"}`。
 */
@Serializable
data class VcsInfoDto(
    val branch: String? = null,
    @SerialName("default_branch")
    val defaultBranch: String? = null,
)

/**
 * `VcsFileStatus`(`GET /vcs/status`)。required は `file` `additions` `deletions` `status`。
 * **`patch` を持たない**のがファイル差分との違いで、一覧を軽く出すための口である。
 */
@Serializable
data class VcsFileStatusDto(
    val file: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null,
)

/**
 * `VcsFileDiff`(`GET /vcs/diff?mode=git|branch`)。required は `file` `additions` `deletions`
 * —— **`patch` と `status` は任意**。バイナリファイルでも `patch` は来る
 * (`Binary files a/x and b/x differ` の1行。実測)が、来ない前提を壊さない。
 */
@Serializable
data class VcsFileDiffDto(
    val file: String = "",
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null,
)

/**
 * `POST /session/{sessionID}/revert` のボディ。spec required は `messageID` のみ、
 * `partID` は任意、`additionalProperties: false`。
 *
 * **`null` を明示しない**([contractJson] の `explicitNulls = false`)。
 * `additionalProperties:false` のスキーマに `"partID":null` を送ると 400 になる形は
 * Q4 が `POST /session` で実測している。
 */
@Serializable
data class RevertRequest(
    val messageID: String,
    val partID: String? = null,
)

/**
 * `Session.revert`(spec required: `messageID`)。
 *
 * **「このセッションは今どこまで巻き戻されているか」の権威はサーバー**である。
 * `revert` / `unrevert` の応答も `GET /session/{id}` も同じ `Session` を返すので、
 * 画面は自分で覚えず、ここを読む(RUN_PLAN 決定2 の Q7 における対応物)。
 */
@Serializable
data class SessionRevertDto(
    val messageID: String? = null,
    val partID: String? = null,
    val snapshot: String? = null,
    /** 巻き戻しによって捨てられる差分(unified diff)。実測未観測。 */
    val diff: String? = null,
)

// ---------------------------------------------------------------------------
// Q8: ファイルブラウザ + 検索
// ---------------------------------------------------------------------------

/**
 * `FileNode`(`GET /file?path=`)。**required は5つ全部**(実機 `/doc` 2026-08-28、
 * SHA-256 が `docs/spec/opencode-1.18.21-openapi.json` と一致)。
 *
 * それでも既定値を持たせてあるのは Q7 と同じ理由 —— required 宣言は
 * 「サーバーが1つ落としただけで**一覧が丸ごと消える**」形を作る(P4 の
 * `PermissionRepliedEvent` が実際にそれで壊れた)。**契約は文書で守り、
 * パーサは緩く受ける。** 欠けたことは呼び出し側が値で判断できる。
 *
 * **[path] はサーバーOSの区切りで来る**(実測: `app\build\`)。ディレクトリは
 * **末尾に区切りが付く**。UI へ出す前に
 * [dev.opencode.android.ui.normalizeServerPath] を通すこと。
 */
@Serializable
data class FileNodeDto(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    /** `"file"` | `"directory"`。**enum にしない**(未知値で配列全体が落ちる)。 */
    val type: String = "",
    val ignored: Boolean = false,
)

/**
 * `FileContent`(`GET /file/content?path=`)。**required は `type` と `content` の2つだけ**。
 *
 * ## `patch` を宣言しない理由(実測が計画書を覆した箇所)
 *
 * QUALITY_PLAN §5b は `diff?`/`patch?` を持つとき Q7 の差分ビューアを再利用せよと書くが、
 * Q7 のパーサが食うのは **unified diff の文字列**である。spec の `FileContent.patch` は
 * **構造化オブジェクト**(`{oldFileName,newFileName,hunks:[...]}`)であり、
 * ここで `patch: String?` と宣言すると `SerializationException` になって
 * **差分を出すための宣言がファイルの中身ごと表示できなくする**。
 *
 * 文字列なのは [diff] だけである。`patch` は宣言せず `ignoreUnknownKeys` に落とす。
 *
 * **1.18.21 は [diff] を一度も返さない**(実測: 変更済みファイルでもキーは `type`/`content` のみ)。
 */
@Serializable
data class FileContentDto(
    /** `"text"` | `"binary"`。 */
    val type: String = "",
    val content: String = "",
    /** unified diff の**文字列**。実測ではこの serve は返さない。 */
    val diff: String? = null,
    /** `"base64"`(バイナリのとき)。 */
    val encoding: String? = null,
    val mimeType: String? = null,
)

/**
 * `File`(`GET /file/status`)。required は4つ全部。
 *
 * **この口は 1.18.21 では常に `[]` を返す**(実測。同時刻の `GET /vcs/status` は5件返した)。
 * Q8 は変更ファイルの権威として **使わない**。DTO を残すのは
 * 「使わないと決めたこと」を契約側で示すためで、`FilesGateway` にも口は在るが
 * 画面はそれを判断材料にしない([dev.opencode.android.ui.FileBrowserController] の doc)。
 */
@Serializable
data class FileStatusDto(
    val path: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val status: String? = null,
)

/** `Match.path` / `Match.lines` / `submatch.match` に共通の `{text}`。 */
@Serializable
data class FindTextDto(val text: String = "")

/**
 * `Match.submatches[]`。**[start] / [end] は UTF-8 のバイトオフセットである**(実測)。
 *
 * `差分ビューア`(6文字/18バイト)が `start=5,end=23` で返る。
 * 文字インデックスとして使うと**日本語を含む行だけがずれて塗られる**。
 * 変換は [dev.opencode.android.ui.byteRangeToCharRange]。
 */
@Serializable
data class FindSubmatchDto(
    val match: FindTextDto = FindTextDto(),
    val start: Int = 0,
    val end: Int = 0,
)

/**
 * `GET /find?pattern=` の要素(**ripgrep の JSON 形式そのまま**)。
 *
 * **サーバーは10件で打ち切る。件数を指定する口が無い**(実測: `a` でも `e` でも10件)。
 * ちょうど [FIND_SERVER_CAP] 件返ったら「これ以上あるかもしれない」を画面に出すこと。
 *
 * `lines.text` は**末尾の改行を含む**。
 */
@Serializable
data class FindMatchDto(
    val path: FindTextDto = FindTextDto(),
    val lines: FindTextDto = FindTextDto(),
    @SerialName("line_number")
    val lineNumber: Int = 0,
    @SerialName("absolute_offset")
    val absoluteOffset: Long = 0,
    val submatches: List<FindSubmatchDto> = emptyList(),
)

/** `Symbol.location.range.start` / `.end`。 */
@Serializable
data class SymbolPositionDto(val line: Int = 0, val character: Int = 0)

/** `Symbol.location.range`。 */
@Serializable
data class SymbolRangeDto(
    val start: SymbolPositionDto = SymbolPositionDto(),
    val end: SymbolPositionDto = SymbolPositionDto(),
)

/** `Symbol.location`。[uri] は `file://` 形式(LSP の規約)。 */
@Serializable
data class SymbolLocationDto(
    val uri: String = "",
    val range: SymbolRangeDto = SymbolRangeDto(),
)

/**
 * `Symbol`(`GET /find/symbol?query=`)。[kind] は **LSP の SymbolKind(整数)**で、
 * spec は enum を持たない(`type: integer, minimum: 0`)。
 *
 * **この環境では常に `[]` が返る**(LSP が動いていない)。
 * 「見つからない」と「索引が使えない」の区別は
 * [dev.opencode.android.ui.FileBrowserController] の**校正クエリ**が付ける。
 */
@Serializable
data class SymbolDto(
    val name: String = "",
    val kind: Int = 0,
    val location: SymbolLocationDto = SymbolLocationDto(),
)

// ---------------------------------------------------------------------------
// Q9: ターミナル(PTY)
// ---------------------------------------------------------------------------

/**
 * `Pty`(`POST /pty` / `GET /pty` / `GET /pty/{ptyID}` / `PUT /pty/{ptyID}`)。
 *
 * 実機 `/doc` の required は `id` `title` `command` `args` `cwd` `status` `pid` の7つ。
 * **[exitCode] だけが任意**である。それでも全部に既定値を持たせるのは Q7/Q8 と同じ理由 ——
 * required 宣言は「サーバーが1つ落としただけで一覧が丸ごと消える」形を作る。
 *
 * ## [exitCode] は REST では一度も観測されていない(実測 2026-08-30、実物 serve 1.18.21)
 *
 * スキーマに在るのに、**この応答で埋まっているのを見たことがない**。理由は寿命の側にある:
 * プロセスが終わると PTY は `GET /pty` の一覧から**消え**、`GET /pty/{id}` は 404 になる。
 *
 * ```
 * (cmd.exe に "exit 7" を送った直後)
 * GET /pty        -> 200 []
 * GET /pty/{id}   -> 404 {"_tag":"PtyNotFoundError",...}
 * ```
 *
 * つまり `status:"exited"` を REST で読める窓が存在しない。**終了コードの唯一の出所は
 * SSE の `pty.exited`** である([SseEvent.PtyLifecycle])。
 */
@Serializable
data class PtyDto(
    val id: String = "",
    val title: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val cwd: String = "",
    /** `"running"` | `"exited"`。**enum にしない**(未知値で一覧全体が落ちる)。 */
    val status: String = "",
    val pid: Int = 0,
    /** spec 上は任意。**REST では一度も観測されていない**(上の doc)。 */
    val exitCode: Int? = null,
)

/**
 * `GET /pty/shells` の要素。
 *
 * **計画書(§5b Q9)は「利用可能なシェル一覧」としか書いていないが、実物はオブジェクトを返す**
 * (実測 2026-08-30、実物 serve 1.18.21、Windows):
 *
 * ```
 * [{"path":"C:\Program Files\PowerShell\7\pwsh.EXE","name":"pwsh","acceptable":true},
 *  {"path":"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.EXE","name":"powershell","acceptable":true},
 *  {"path":"C:\Program Files\Git\bin\bash.exe","name":"bash","acceptable":true},
 *  {"path":"C:\Windows\system32\cmd.exe","name":"cmd","acceptable":true}]
 * ```
 *
 * [path] を `POST /pty` の `command` に渡す(`name` は表示用)。
 */
@Serializable
data class PtyShellDto(
    val path: String = "",
    val name: String = "",
    /**
     * サーバーが「PTY として起動してよい」と判断したか。
     * **false を「無い」と読まない** —— 一覧には載っているが薦められていない、である。
     */
    val acceptable: Boolean = false,
)

/**
 * `POST /pty/{ptyID}/connect-token` の応答(spec required: `ticket` `expires_in`)。
 *
 * 実測: `{"ticket":"65ffeda9-…","expires_in":60}`。
 * **チケットは単回使用**である(同じチケットで2本目を開くと接続が失敗する。実測)。
 * 再接続のたびに取り直すこと。
 */
@Serializable
data class PtyTicketDto(
    val ticket: String = "",
    @SerialName("expires_in")
    val expiresIn: Int = 0,
)

/**
 * `POST /pty` のボディ。
 *
 * `cwd` / `env` は送らない —— 送らなければ serve の起動 cwd が使われ(実測: 応答の `cwd` が
 * リポジトリルートになる)、端末から任意のパスや環境変数を入れる導線を作らない。
 * **null は送られない**([contractJson] の `explicitNulls = false`)。
 */
@Serializable
data class CreatePtyRequest(
    val command: String,
    val args: List<String> = emptyList(),
    val title: String,
)

/** `PUT /pty/{ptyID}` の `size`。**行と桁の両方が要る**(片方だけの口は無い)。 */
@Serializable
data class PtySizeDto(
    val rows: Int,
    val cols: Int,
)

/**
 * `PUT /pty/{ptyID}` のボディ。**リサイズは WebSocket ではなくここを通る**(実測)。
 *
 * 実測: `PUT /pty/{id} {"size":{"rows":24,"cols":100}}` -> 200 + `Pty`。
 * 直後に PTY 側が `ESC[8;24;100t` と全画面の描き直しを流す。
 */
@Serializable
data class UpdatePtyRequest(
    val title: String? = null,
    val size: PtySizeDto? = null,
)
