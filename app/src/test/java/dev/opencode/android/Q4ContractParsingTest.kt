package dev.opencode.android

import dev.opencode.android.data.AgentDto
import dev.opencode.android.data.CreateSessionRequest
import dev.opencode.android.data.MessageEntryDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.SessionDto
import dev.opencode.android.data.SseEvent
import dev.opencode.android.data.SwitchModelRequest
import dev.opencode.android.data.contractJson
import dev.opencode.android.data.parseSseEnvelope
import dev.opencode.android.ui.messageDetailLines
import dev.opencode.android.ui.toChatMessageMeta
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q4 の契約突合(docs/API_CONTRACT.md「Q4で使用する分」)。
 *
 * **フィクスチャは実物 serve 4097 から採ったものを貼ってある**(2026-08-27)。
 * 近似した文字列を流さない —— このプロジェクトが7度繰り返した欠陥は
 * 「フィクスチャがコードと同じ誤った前提を持つ」形だった(QUALITY_PLAN §4.2)。
 * 証跡は `e2e-artifacts/Q4/probe/`。
 */
class Q4ContractParsingTest {

    // ---- モデル参照の3形(API_CONTRACT.md「モデル参照の形が3種類ある」) ----

    /**
     * `ModelRef` は **`id`** キーである。`modelID` ではない。
     *
     * **これが Q4 で一番危ない一行**である。取り違えてもサーバーは 400 を返さず、
     * `additionalProperties:false` に当たって全体が弾かれるか、黙って無視される。
     * 実測 #4 では存在しないモデルすら 204 で受理された —— つまり
     * **キーを間違えても画面上は成功に見える**。
     */
    @Test
    fun `ModelRef のエンコードは id キーで variant は省略される`() {
        val json = contractJson.encodeToString(
            SwitchModelRequest(ModelRefDto(id = "mistral-medium-latest", providerID = "mistral")),
        )
        assertEquals("""{"model":{"id":"mistral-medium-latest","providerID":"mistral"}}""", json)
        assertFalse("modelID キーを出してはならない", json.contains("modelID"))
        assertFalse("variant は null なら出さない", json.contains("variant"))
    }

    @Test
    fun `ModelRef の variant は指定すれば載る`() {
        val json = contractJson.encodeToString(
            SwitchModelRequest(
                ModelRefDto(id = "mistral-medium-latest", providerID = "mistral", variant = "thinking"),
            ),
        )
        assertEquals(
            """{"model":{"id":"mistral-medium-latest","providerID":"mistral","variant":"thinking"}}""",
            json,
        )
    }

    /**
     * **`POST /session` に明示 null を送ると 400 BadRequest**(実測 2026-08-27):
     *
     *     {"title":"Q4-nulltest","agent":null,"model":null} -> 400 {"_tag":"BadRequest"}
     *
     * つまり「モデル未指定で新規作成する」という既定の操作が丸ごと壊れる。
     * `encodeDefaults = true` だけならこれが実際に送られていた。
     */
    @Test
    fun `作成リクエストは未指定の agent と model を送らない`() {
        val json = contractJson.encodeToString(CreateSessionRequest(title = "Q4"))
        assertEquals("""{"title":"Q4"}""", json)
        assertFalse("null を書くと 400 になる", json.contains("null"))
    }

    @Test
    fun `作成リクエストは指定した agent と model を載せる`() {
        val json = contractJson.encodeToString(
            CreateSessionRequest(
                title = "Q4-model-probe",
                agent = "plan",
                model = ModelRefDto(id = "mistral-medium-latest", providerID = "mistral"),
            ),
        )
        assertEquals(
            """{"title":"Q4-model-probe","agent":"plan",""" +
                """"model":{"id":"mistral-medium-latest","providerID":"mistral"}}""",
            json,
        )
    }

    // ---- GET /session -> Session の agent / model ----

    /** 実物が返した `POST /session` の応答そのもの(`e2e-artifacts/Q4/probe/created.json`)。 */
    private val realCreatedSession = """
        {"id":"ses_fbebc2a78ffeMMjWyj0mBPzBKu","slug":"neon-river",
         "projectID":"c131653af018fbbc2b3e0a43a252c18337ffdacf",
         "directory":"E:\\github\\opencode-android","path":"",
         "cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
         "title":"Q4-model-probe","agent":"plan",
         "model":{"id":"mistral-medium-latest","providerID":"mistral"},
         "version":"1.18.21","time":{"created":1787801359751,"updated":1787801359751}}
    """.trimIndent()

    @Test
    fun `Session の agent と model を読む`() {
        val s = contractJson.decodeFromString<SessionDto>(realCreatedSession)
        assertEquals("plan", s.agent)
        assertEquals("mistral-medium-latest", s.model?.id)
        assertEquals("mistral", s.model?.providerID)
        assertNull(s.model?.variant)
    }

    /**
     * `Session.model` / `Session.agent` は spec 上**任意**。
     * 欠けている = 「サーバー既定に従う」であって「モデルが無い」ではない。
     */
    @Test
    fun `モデル未設定のセッションも読める`() {
        val s = contractJson.decodeFromString<SessionDto>(
            """{"id":"ses_a","slug":"s","projectID":"p","directory":"/d","title":"t",""" +
                """"version":"1.18.21","time":{"created":1,"updated":2}}""",
        )
        assertNull(s.model)
        assertNull(s.agent)
    }

    /** 実測: `prompt_async` 後の `GET /session` は `variant:"default"` へ正規化されていた。 */
    @Test
    fun `variant が default へ正規化された応答も読める`() {
        val s = contractJson.decodeFromString<SessionDto>(
            """{"id":"ses_a","slug":"s","projectID":"p","directory":"/d","title":"t","version":"1",""" +
                """"time":{"created":1,"updated":2},""" +
                """"model":{"id":"mistral-small-latest","providerID":"mistral","variant":"default"}}""",
        )
        assertEquals("default", s.model?.variant)
    }

    // ---- GET /provider ----

    /**
     * 実物の `Model` は required 11個を持つ(`api` `capabilities` `cost` `limit` `status`
     * `options` `headers` `release_date` を含む)。**そのまま貼って**、
     * アプリが宣言していないフィールドが `ignoreUnknownKeys` で落ちることを確かめる。
     * 抜粋元: `mistral/mistral-medium-2508`(実物 serve 4097)。
     */
    private val realProvidersJson = """
        {"all":[
          {"id":"mistral","name":"Mistral","source":"api","env":["MISTRAL_API_KEY"],"options":{},
           "models":{
             "mistral-medium-2508":{"id":"mistral-medium-2508","providerID":"mistral",
               "api":{"id":"mistral-medium-2508","url":"","npm":"@ai-sdk/mistral"},
               "name":"Mistral Medium 3.1","family":"mistral-medium",
               "capabilities":{"temperature":true,"reasoning":false,"attachment":true,"toolcall":true,
                 "input":{"text":true,"audio":false,"image":true,"video":false,"pdf":false},
                 "output":{"text":true,"audio":false,"image":false,"video":false,"pdf":false},
                 "interleaved":false},
               "cost":{"input":0.4,"output":2,"cache":{"read":0,"write":0}},
               "limit":{"context":262144,"output":262144},
               "status":"active","options":{},"headers":{},"release_date":"2025-08-12","variants":{}},
             "mistral-medium-latest":{"id":"mistral-medium-latest","providerID":"mistral",
               "api":{"id":"mistral-medium-latest","url":"","npm":"@ai-sdk/mistral"},
               "name":"Mistral Medium","family":"mistral-medium",
               "capabilities":{"temperature":true,"reasoning":false,"attachment":true,"toolcall":true,
                 "input":{"text":true,"audio":false,"image":true,"video":false,"pdf":false},
                 "output":{"text":true,"audio":false,"image":false,"video":false,"pdf":false},
                 "interleaved":false},
               "cost":{"input":0.4,"output":2,"cache":{"read":0,"write":0}},
               "limit":{"context":262144,"output":262144},
               "status":"active","options":{},"headers":{},"release_date":"2025-08-12","variants":{}}}},
          {"id":"cerebras","name":"Cerebras","source":"api","env":["CEREBRAS_API_KEY"],"options":{},
           "models":{
             "gpt-oss-120b":{"id":"gpt-oss-120b","providerID":"cerebras",
               "api":{"id":"gpt-oss-120b","url":"","npm":"@ai-sdk/openai-compatible"},
               "name":"GPT OSS 120B","capabilities":{"temperature":true,"reasoning":true,
                 "attachment":false,"toolcall":true,
                 "input":{"text":true,"audio":false,"image":false,"video":false,"pdf":false},
                 "output":{"text":true,"audio":false,"image":false,"video":false,"pdf":false},
                 "interleaved":false},
               "cost":{"input":0.25,"output":0.69,"cache":{"read":0,"write":0}},
               "limit":{"context":131072,"output":40000},
               "status":"active","options":{},"headers":{},"release_date":"2025-08-05","variants":{}}}},
          {"id":"anthropic","name":"Anthropic","source":"api","env":["ANTHROPIC_API_KEY"],"options":{},
           "models":{
             "claude-x":{"id":"claude-x","providerID":"anthropic",
               "api":{"id":"claude-x","url":"","npm":"@ai-sdk/anthropic"},
               "name":"Claude X","capabilities":{"temperature":true,"reasoning":true,
                 "attachment":true,"toolcall":true,
                 "input":{"text":true,"audio":false,"image":true,"video":false,"pdf":true},
                 "output":{"text":true,"audio":false,"image":false,"video":false,"pdf":false},
                 "interleaved":false},
               "cost":{"input":3,"output":15,"cache":{"read":0,"write":0}},
               "limit":{"context":200000,"output":64000},
               "status":"active","options":{},"headers":{},"release_date":"2025-01-01","variants":{}}}}
        ],
        "default":{"mistral":"mistral-medium-latest","cerebras":"gpt-oss-120b","anthropic":"claude-x"},
        "connected":["cerebras","mistral"]}
    """.trimIndent()

    @Test
    fun `GET provider を実物の形で読む`() {
        val p = contractJson.decodeFromString<ProvidersDto>(realProvidersJson)
        assertEquals(3, p.all.size)
        assertEquals(listOf("cerebras", "mistral"), p.connected)
        assertEquals("mistral-medium-latest", p.default["mistral"])
        val mistral = p.all.first { it.id == "mistral" }
        assertEquals("Mistral", mistral.name)
        assertEquals("api", mistral.source)
        assertEquals(2, mistral.models.size)
        assertEquals("Mistral Medium 3.1", mistral.models["mistral-medium-2508"]?.name)
        assertEquals("active", mistral.models["mistral-medium-2508"]?.status)
    }

    // ---- GET /agent ----

    /**
     * 実物の `GET /agent` の3件(`e2e-artifacts/Q4/probe/agent.json` から抜粋)。
     *
     * **注目点が3つある**:
     *  - `orchestrator.model` は **`{modelID, providerID}`**(`ModelRef` ではない)
     *  - `orchestrator.hidden` は **明示 null**、`explore` は**キーごと無い** —— どちらも false 扱い
     *  - `permission` は spec の `PermissionRuleset` だが**実物では配列**である。
     *    アプリは宣言していないので `ignoreUnknownKeys` で落ちる。**落ちることを確認する。**
     */
    private val realAgentsJson = """
        [
          {"name":"orchestrator","description":"総合エージェント","mode":"primary","native":false,
           "hidden":null,"topP":null,"temperature":0.1,"color":null,
           "model":{"modelID":"nemotron-3.5-lightning-free","providerID":"opencode"},
           "variant":null,"steps":null,"options":{},
           "permission":[{"permission":"*","pattern":"*","action":"allow"}]},
          {"name":"explore","mode":"subagent","native":true,"options":{},
           "permission":[{"permission":"*","pattern":"*","action":"allow"}]},
          {"name":"compaction","mode":"primary","native":true,"hidden":true,"options":{},
           "permission":[{"permission":"*","pattern":"*","action":"allow"}]}
        ]
    """.trimIndent()

    @Test
    fun `GET agent を実物の形で読む`() {
        val agents = contractJson.decodeFromString<List<AgentDto>>(realAgentsJson)
        assertEquals(3, agents.size)
        val orch = agents.first { it.name == "orchestrator" }
        assertEquals("primary", orch.mode)
        // hidden は明示 null。**false と同じ扱いになること**。
        assertNull(orch.hidden)
        // Agent.model は modelID キー。ModelRef の id ではない。
        assertEquals("nemotron-3.5-lightning-free", orch.model?.modelID)
        assertEquals("opencode", orch.model?.providerID)

        val explore = agents.first { it.name == "explore" }
        assertEquals("subagent", explore.mode)
        assertNull("hidden キーごと無い", explore.hidden)
        assertNull(explore.description)

        assertEquals(true, agents.first { it.name == "compaction" }.hidden)
    }

    // ---- AssistantMessage / UserMessage のメタ ----

    /**
     * 実物の `GET /session/{id}/message`(402 で失敗した往復。`e2e-artifacts/Q4/probe/msgs.json`)。
     *
     * **失敗した往復でも `providerID`/`modelID` は載っている。** これが R3 の一次証拠であり、
     * 「そのモデルが落ちている」の“その”を指せる唯一の材料である。
     */
    private val realMessagesJson = """
        [
         {"info":{"id":"msg_04145dd640016YAp4gbAqbpg4P","sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu",
                  "role":"user","time":{"created":1787801492836},"summary":{"diffs":[]},
                  "agent":"build","model":{"providerID":"mistral","modelID":"mistral-small-latest"}},
          "parts":[{"id":"prt_1","sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu",
                    "messageID":"msg_04145dd640016YAp4gbAqbpg4P","type":"text","text":"say PONG"}]},
         {"info":{"id":"msg_04145dd72001NJW6ytBLS3tM3K","sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu",
                  "role":"assistant","agent":"build","mode":"build",
                  "providerID":"mistral","modelID":"mistral-small-latest",
                  "cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
                  "time":{"created":1787801492850,"completed":1787801494584},
                  "path":{"cwd":"E:\\github","root":"E:\\github"},
                  "error":{"name":"APIError","data":{"message":"Payment Required","statusCode":402,
                           "isRetryable":false}}},
          "parts":[]}
        ]
    """.trimIndent()

    @Test
    fun `AssistantMessage のメタはフラット、UserMessage はネスト`() {
        val entries = contractJson.decodeFromString<List<MessageEntryDto>>(realMessagesJson)
        assertEquals(2, entries.size)

        val user = entries[0].info!!
        assertNull("user はフラットな providerID を持たない", user.providerID)
        assertEquals("mistral", user.model?.providerID)
        assertEquals("mistral-small-latest", user.model?.modelID)

        val assistant = entries[1].info!!
        assertEquals("mistral", assistant.providerID)
        assertEquals("mistral-small-latest", assistant.modelID)
        assertEquals("build", assistant.agent)
        assertEquals(0.0, assistant.cost!!, 0.0)
        assertEquals(0L, assistant.tokens?.input)
        assertEquals(0L, assistant.tokens?.cache?.read)
    }

    /**
     * **表示用メタも user のネストした `model` から作れること。**
     *
     * これは変異 M9(`providerID ?: model?.providerID` の後半を落とす)が
     * **382件全緑のまま通り抜けた**ので後から足した検出器である。
     * 症状は「assistant の『詳細』は正しく出るのに user のバブルにだけ出ない」——
     * 画面のどこも壊れて見えず、`ChatMessageMeta` に user 用のテストが1本も無かった。
     * このプロジェクトが7度繰り返した「検出器の穴」の Q4 における実例。
     */
    @Test
    fun `UserMessage のメタも表示用に取り出せる`() {
        val entries = contractJson.decodeFromString<List<MessageEntryDto>>(realMessagesJson)
        val userMeta = entries[0].info.toChatMessageMeta()!!
        assertEquals("mistral", userMeta.providerId)
        assertEquals("mistral-small-latest", userMeta.modelId)
        assertEquals("mistral / mistral-small-latest", userMeta.modelLabel)

        val assistantMeta = entries[1].info.toChatMessageMeta()!!
        assertEquals("mistral", assistantMeta.providerId)
        assertEquals("mistral-small-latest", assistantMeta.modelId)
    }

    /** 「詳細」の行。**空の項目を入れない**、`cost=0` は出す(0 と「分からない」は違う)。 */
    @Test
    fun `詳細の行は実測の値をそのまま並べる`() {
        val entries = contractJson.decodeFromString<List<MessageEntryDto>>(realMessagesJson)
        val lines = messageDetailLines(entries[1].info.toChatMessageMeta())
        assertEquals(
            listOf(
                "プロバイダ" to "mistral",
                "モデル" to "mistral-small-latest",
                "エージェント" to "build",
                "コスト" to "0.0",
                "入力トークン" to "0",
                "出力トークン" to "0",
                "推論トークン" to "0",
                "キャッシュ読み" to "0",
                "キャッシュ書き" to "0",
            ),
            lines,
        )
    }

    /** メタが1つも無いメッセージでは「詳細」を出さない(空のダイアログを開かせない)。 */
    @Test
    fun `メタが無ければ詳細の行は空`() {
        val entry = contractJson.decodeFromString<MessageEntryDto>(
            """{"info":{"id":"msg_a","sessionID":"ses_a","role":"assistant"},"parts":[]}""",
        )
        assertNull(entry.info.toChatMessageMeta())
        assertTrue(messageDetailLines(null).isEmpty())
    }

    // ---- SSE ----

    /** 実物のフレームそのもの(`e2e-artifacts/Q4/probe/sse2.txt`)。 */
    @Test
    fun `session next model switched を読む`() {
        val event = parseSseEnvelope(
            """{"id":"evt_041452c82002GdjFF4Rx1pbtGF","type":"session.next.model.switched",""" +
                """"properties":{"sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu",""" +
                """"messageID":"msg_041452c82001XvCOm5QGBkG3QG","timestamp":"2026-08-27T03:30:47.554Z",""" +
                """"model":{"id":"mistral-small-2506","providerID":"mistral"}}}""",
        )
        val e = event as SseEvent.SessionNextChanged
        assertEquals("ses_fbebc2a78ffeMMjWyj0mBPzBKu", e.sessionID)
        assertEquals("mistral-small-2506", e.model?.id)
        assertEquals("mistral", e.model?.providerID)
        assertNull(e.agent)
    }

    @Test
    fun `session next model switched は variant も読む`() {
        val e = parseSseEnvelope(
            """{"id":"evt_x","type":"session.next.model.switched","properties":{"sessionID":"ses_a",""" +
                """"messageID":"msg_a","timestamp":"2026-08-27T03:30:48.615Z",""" +
                """"model":{"id":"mistral-medium-latest","providerID":"mistral","variant":"thinking"}}}""",
        ) as SseEvent.SessionNextChanged
        assertEquals("thinking", e.model?.variant)
    }

    /** 実物のフレームそのもの(`e2e-artifacts/Q4/probe/sse2.txt` の agent 側)。 */
    @Test
    fun `session next agent switched を読む`() {
        val e = parseSseEnvelope(
            """{"id":"evt_041449c680023hM7KxtDfidbUo","type":"session.next.agent.switched",""" +
                """"properties":{"sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu",""" +
                """"messageID":"msg_041449c68001d9dAZKNuv0bAJM","timestamp":"2026-08-27T03:30:10.664Z",""" +
                """"agent":"build"}}""",
        ) as SseEvent.SessionNextChanged
        assertEquals("build", e.agent)
        assertNull("agent イベントは model を持たない", e.model)
    }

    /** `model` が読めなくてもイベントごと捨てない(取り直しの契機を失わないため)。 */
    @Test
    fun `model が壊れていても sessionID は活かす`() {
        val e = parseSseEnvelope(
            """{"id":"evt_x","type":"session.next.model.switched",""" +
                """"properties":{"sessionID":"ses_a","model":"not-an-object"}}""",
        ) as SseEvent.SessionNextChanged
        assertEquals("ses_a", e.sessionID)
        assertNull(e.model)
    }

    /**
     * `session.error` の 402 フレーム(実物。`e2e-artifacts/Q4/probe/sse3.txt`)。
     * **`statusCode` と `isRetryable` を読めること**が R3 の導線の前提である。
     */
    @Test
    fun `session error から statusCode と isRetryable を読む`() {
        val e = parseSseEnvelope(
            """{"id":"evt_04145e370001zmJ0CIg09ftYD7","type":"session.error","properties":{""" +
                """"sessionID":"ses_fbebc2a78ffeMMjWyj0mBPzBKu","error":{"name":"APIError","data":{""" +
                """"message":"Payment Required: {\"detail\":\"Check your subscription\"}",""" +
                """"statusCode":402,"isRetryable":false,"responseHeaders":{"server":"cloudflare"},""" +
                """"responseBody":"{}","metadata":{"url":"https://api.mistral.ai/v1/chat/completions"}}}}}""",
        ) as SseEvent.SessionError
        assertEquals(402, e.statusCode)
        assertEquals(false, e.retryable)
        assertTrue(e.error!!.startsWith("Payment Required"))
    }

    /** 材料が無いフレームでは **null**(= 「分からない」)。false と混同しない。 */
    @Test
    fun `statusCode が無い session error は null を返す`() {
        val e = parseSseEnvelope(
            """{"id":"evt_x","type":"session.error","properties":{"sessionID":"ses_a",""" +
                """"error":"plain string error"}}""",
        ) as SseEvent.SessionError
        assertNull(e.statusCode)
        assertNull(e.retryable)
        assertEquals("plain string error", e.error)
    }
}
