package dev.opencode.android

import dev.opencode.android.data.AgentDto
import dev.opencode.android.data.AgentModelDto
import dev.opencode.android.data.ModelCapabilitiesDto
import dev.opencode.android.data.ModelModalityDto
import dev.opencode.android.data.ModelRefDto
import dev.opencode.android.data.ProviderDto
import dev.opencode.android.data.ProviderModelDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.ui.ChatBannerKind
import dev.opencode.android.ui.ChatUi
import dev.opencode.android.ui.errorSuggestsModelChange
import dev.opencode.android.ui.canRunSession
import dev.opencode.android.ui.excludedModelCount
import dev.opencode.android.ui.filterModelChoices
import dev.opencode.android.ui.modelPillDescription
import dev.opencode.android.ui.modelPillLabel
import dev.opencode.android.ui.selectChatBanner
import dev.opencode.android.ui.selectModelPill
import dev.opencode.android.ui.selectableAgents
import dev.opencode.android.ui.selectableModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q4 の純関数(モデル/エージェントの絞り込み・ピル・バナー)。
 *
 * **数を数える**。「connected で絞る」を文章で言い直すのではなく、
 * 203プロバイダ相当の入力から**何件になるか**を主張する(QUALITY_PLAN §4.2)。
 */
class Q4ModelCatalogTest {

    /** 既定は「チャットに使える」形。`capabilities` は spec の required を満たす。 */
    private fun model(
        id: String,
        name: String = id,
        toolcall: Boolean? = true,
        outText: Boolean? = true,
        inText: Boolean? = true,
    ) = ProviderModelDto(
        id = id,
        name = name,
        status = "active",
        capabilities = ModelCapabilitiesDto(
            toolcall = toolcall,
            reasoning = false,
            input = ModelModalityDto(text = inText, audio = false, image = false, video = false, pdf = false),
            output = ModelModalityDto(text = outText, audio = false, image = false, video = false, pdf = false),
        ),
    )

    /** `capabilities` を**まったく持たない**モデル(古い/別実装のサーバー)。 */
    private fun modelWithoutCapabilities(id: String) =
        ProviderModelDto(id = id, name = "N-$id", status = "active")

    private fun provider(id: String, name: String, vararg models: String) = ProviderDto(
        id = id,
        name = name,
        source = "api",
        models = models.associateWith { model(it, "N-$it") },
    )

    private fun providerOf(id: String, name: String, vararg models: ProviderModelDto) = ProviderDto(
        id = id,
        name = name,
        source = "api",
        models = models.associateBy { it.id },
    )

    private val providers = ProvidersDto(
        all = listOf(
            provider("mistral", "Mistral", "mistral-medium-latest", "mistral-small-latest"),
            provider("cerebras", "Cerebras", "gpt-oss-120b"),
            // connected に**居ない**プロバイダ。実物では203件のうち195件がこちら側である。
            provider("anthropic", "Anthropic", "claude-x"),
            provider("hpc-ai", "HPC-AI", "a", "b", "c"),
        ),
        default = mapOf(
            "mistral" to "mistral-small-latest",
            "cerebras" to "gpt-oss-120b",
            // 実測: default は connected でないプロバイダにも載っている。
            "anthropic" to "claude-x",
            "hpc-ai" to "a",
        ),
        connected = listOf("cerebras", "mistral"),
    )

    // ---- selectableModels ----

    /**
     * **203プロバイダ・7,338モデルを画面に出さない**ことの検出器(実測値は API_CONTRACT.md)。
     * `connected` の絞り込みを外す変異は、この件数で落ちる。
     */
    @Test
    fun `connected のプロバイダだけを採る`() {
        val choices = selectableModels(providers)
        assertEquals("connected 2件ぶんのモデルだけ", 3, choices.size)
        assertEquals(setOf("cerebras", "mistral"), choices.map { it.providerId }.toSet())
        assertFalse(choices.any { it.providerId == "anthropic" })
        assertFalse(choices.any { it.providerId == "hpc-ai" })
    }

    /** サーバーが返した `connected` の並びが優先順である。アルファベット順に直さない。 */
    @Test
    fun `connected の並び順を保つ`() {
        val choices = selectableModels(providers)
        assertEquals(
            listOf("cerebras", "mistral", "mistral"),
            choices.map { it.providerId },
        )
    }

    /**
     * **`providers.default` を優先順位に使わない**(レビュー major-2)。
     *
     * 1周目は `default` を各プロバイダの先頭へ固定していた。実物の `default` は
     * connected 8社のうち3社で `toolcall:false`(groq=Whisper、google/openrouter=画像生成)、
     * mistral も音声モデルだったので、**替えに来たユーザーの目の前にチャットできない
     * モデルを推薦として置いていた**。この検出器は「default を先頭へ戻す」変異で落ちる。
     *
     * このフィクスチャの `default` は `mistral-small-latest` だが、並びは表示名の昇順なので
     * `N-mistral-medium-latest` < `N-mistral-small-latest` で medium が先に来る。
     */
    @Test
    fun `default は先頭に固定されない`() {
        val mistral = selectableModels(providers).filter { it.providerId == "mistral" }
        assertEquals("mistral-small-latest", providers.default["mistral"])
        assertEquals(
            listOf("mistral-medium-latest", "mistral-small-latest"),
            mistral.map { it.modelId },
        )
    }

    // ---- capabilities による絞り込み(レビュー major-2) ----

    /**
     * **実物の `default` 3件を模したフィクスチャ。**
     * 値は実測(2026-08-27、実物 serve)からそのまま採った:
     *   groq/whisper-large-v3-turbo   toolcall=false input.text=false  output.text=true
     *   google/gemini-3-pro-image-preview toolcall=false input.text=true output.text=true
     *   mistral/voxtral-small-latest  toolcall=true  input.text=true  output.text=true
     */
    private val realish = ProvidersDto(
        all = listOf(
            providerOf(
                "groq", "Groq",
                model("whisper-large-v3-turbo", "Whisper Large V3 Turbo", toolcall = false, inText = false),
                model("llama-3.3-70b", "Llama 3.3 70B"),
            ),
            providerOf(
                "google", "Google",
                model("gemini-3-pro-image-preview", "Nano Banana Pro", toolcall = false),
                model("gemini-3-pro", "Gemini 3 Pro"),
            ),
            providerOf(
                "mistral", "Mistral",
                // 音声寄りだが capabilities では落とせない。**落ちないことを固定する。**
                model("voxtral-small-latest", "Voxtral Small (latest)"),
                model("mistral-medium-latest", "Mistral Medium (latest)"),
            ),
        ),
        default = mapOf(
            "groq" to "whisper-large-v3-turbo",
            "google" to "gemini-3-pro-image-preview",
            "mistral" to "voxtral-small-latest",
        ),
        connected = listOf("groq", "google", "mistral"),
    )

    /** **toolcall:false は出さない。** これが R3 の行き止まりの付け替えを止める。 */
    @Test
    fun `ツール実行できないモデルは選択肢に出さない`() {
        val ids = selectableModels(realish).map { it.modelId }
        assertFalse("Whisper は出さない", ids.contains("whisper-large-v3-turbo"))
        assertFalse("画像生成モデルは出さない", ids.contains("gemini-3-pro-image-preview"))
        assertEquals(listOf("llama-3.3-70b", "gemini-3-pro", "mistral-medium-latest", "voxtral-small-latest"), ids)
    }

    /** 除外した件数を数えて出す(黙って短くしない)。 */
    @Test
    fun `除外した件数を数える`() {
        assertEquals(2, excludedModelCount(realish))
        assertEquals(0, excludedModelCount(providers))
    }

    /**
     * **capabilities が無ければ落とさない。** 判定できないサーバーで選択肢が0件になるほうが、
     * 不適なモデルが混ざるより悪い —— R3 の導線そのものが閉じる。
     */
    @Test
    fun `capabilities が無いモデルは残す`() {
        val p = ProvidersDto(
            all = listOf(providerOf("p", "P", modelWithoutCapabilities("m1"), modelWithoutCapabilities("m2"))),
            default = emptyMap(),
            connected = listOf("p"),
        )
        assertEquals(2, selectableModels(p).size)
        assertEquals(0, excludedModelCount(p))
    }

    /** `null` と `false` を混同しない(`isRetryable` と同じ規則)。 */
    @Test
    fun `canRunSession は null を通し false だけ落とす`() {
        assertTrue(null.canRunSession())
        assertTrue(ModelCapabilitiesDto().canRunSession())
        assertTrue(ModelCapabilitiesDto(toolcall = true).canRunSession())
        assertFalse(ModelCapabilitiesDto(toolcall = false).canRunSession())
        assertFalse(
            ModelCapabilitiesDto(toolcall = true, output = ModelModalityDto(text = false)).canRunSession(),
        )
        assertFalse(
            ModelCapabilitiesDto(toolcall = true, input = ModelModalityDto(text = false)).canRunSession(),
        )
        // input/output が有るが text を言っていない = 分からない -> 通す
        assertTrue(
            ModelCapabilitiesDto(toolcall = true, input = ModelModalityDto(audio = true)).canRunSession(),
        )
    }

    /**
     * **`voxtral` は capabilities では落とせない**ことを明示的に固定する。
     * 落とせると誤解した実装(名前で弾く等)を足したら、この検出器が知らせる。
     * だからこそ `default` を推薦に使わない —— 限界を書いておかないと、
     * 「絞り込んだから安全」という読み違いが次の欠陥になる。
     */
    @Test
    fun `音声寄りでも capabilities が揃っていれば落とせない`() {
        assertTrue(selectableModels(realish).any { it.modelId == "voxtral-small-latest" })
    }

    /** `connected` にあって `all` に無いIDは無視する(空の見出しを出さない)。 */
    @Test
    fun `all に居ない connected は無視する`() {
        val choices = selectableModels(providers.copy(connected = listOf("mistral", "ghost")))
        assertEquals(2, choices.size)
        assertEquals(setOf("mistral"), choices.map { it.providerId }.toSet())
    }

    /** `Provider.models` の**キー**が modelID である(`Model.id` が欠けてもキーへ落とせる)。 */
    @Test
    fun `Model id が空ならマップのキーを使う`() {
        val choices = selectableModels(
            ProvidersDto(
                all = listOf(
                    ProviderDto(
                        id = "p", name = "P", source = "api",
                        models = mapOf("model-from-key" to ProviderModelDto(id = "", name = "名前")),
                    ),
                ),
                default = emptyMap(),
                connected = listOf("p"),
            ),
        )
        assertEquals("model-from-key", choices.single().modelId)
    }

    /** `POST /session` / 切替に渡すのは **`id` キー**の `ModelRef`。 */
    @Test
    fun `toRef は ModelRef を作る`() {
        val choice = selectableModels(providers).first { it.modelId == "gpt-oss-120b" }
        assertEquals(ModelRefDto(id = "gpt-oss-120b", providerID = "cerebras"), choice.toRef())
    }

    // ---- selectableAgents ----

    private val agents = listOf(
        AgentDto(name = "build", description = "既定", mode = "primary"),
        AgentDto(name = "orchestrator", mode = "primary", model = AgentModelDto("m", "p")),
        AgentDto(name = "compaction", mode = "primary", hidden = true),
        AgentDto(name = "summary", mode = "primary", hidden = true),
        AgentDto(name = "explore", mode = "subagent"),
        AgentDto(name = "worker", mode = "subagent"),
        AgentDto(name = "anything", mode = "all"),
        AgentDto(name = "no-mode", mode = null),
        AgentDto(name = "", mode = "primary"),
    )

    @Test
    fun `subagent と hidden を除く`() {
        val names = selectableAgents(agents).map { it.name }
        assertEquals(listOf("anything", "build", "no-mode", "orchestrator"), names)
    }

    /** `hidden` が**欠けている**エージェントは出す(spec 上任意 = false)。 */
    @Test
    fun `hidden が null なら出す`() {
        assertTrue(selectableAgents(listOf(AgentDto(name = "x", mode = "primary", hidden = null))).size == 1)
    }

    /** 未知の mode で選択肢が黙って消えない。 */
    @Test
    fun `mode が欠けていても出す`() {
        assertEquals(1, selectableAgents(listOf(AgentDto(name = "x", mode = null))).size)
    }

    @Test
    fun `description は空文字なら null にする`() {
        assertNull(selectableAgents(listOf(AgentDto(name = "x", mode = "primary", description = "  "))).single().description)
    }

    // ---- filterModelChoices ----

    @Test
    fun `検索はモデル ID にも当たる`() {
        val all = selectableModels(providers)
        assertEquals(2, filterModelChoices(all, "mistral").size)
        assertEquals(1, filterModelChoices(all, "SMALL").size)
        assertEquals(1, filterModelChoices(all, "oss-120").size)
    }

    @Test
    fun `検索はプロバイダ名にも当たる`() {
        val all = selectableModels(providers)
        assertEquals(1, filterModelChoices(all, "cerebras").size)
    }

    @Test
    fun `空の検索語は全件`() {
        val all = selectableModels(providers)
        assertEquals(all.size, filterModelChoices(all, "   ").size)
    }

    @Test
    fun `当たらない検索は0件`() {
        assertEquals(0, filterModelChoices(selectableModels(providers), "gpt-4o").size)
    }

    // ---- ピル ----

    /** **null は「モデル未指定」ではなく「サーバー既定」**。 */
    @Test
    fun `モデル未設定は既定モデルと出す`() {
        assertEquals("既定モデル", modelPillLabel(null))
    }

    @Test
    fun `ピルはモデルIDを出し、default 以外の variant を添える`() {
        assertEquals("m1", modelPillLabel(ModelRefDto(id = "m1", providerID = "p")))
        assertEquals("m1", modelPillLabel(ModelRefDto(id = "m1", providerID = "p", variant = "default")))
        assertEquals("m1 (thinking)", modelPillLabel(ModelRefDto(id = "m1", providerID = "p", variant = "thinking")))
    }

    /** dump から引用できる形であること(HARNESS「引用できる証拠」)。 */
    @Test
    fun `ピルの contentDescription は provider とモデルとエージェントを含む`() {
        assertEquals(
            "chat-model-pill:mistral/mistral-medium-latest:agent=build",
            modelPillDescription(ModelRefDto(id = "mistral-medium-latest", providerID = "mistral"), "build"),
        )
        assertEquals("chat-model-pill:default:agent=default", modelPillDescription(null, null))
    }

    @Test
    fun `削除済みセッションではピルを出さない`() {
        assertNull(selectModelPill(ChatUi(sessionDeleted = true)))
    }

    @Test
    fun `切替の往復中はピルを押せない`() {
        assertFalse(selectModelPill(ChatUi(switchingModel = true))!!.enabled)
        assertTrue(selectModelPill(ChatUi())!!.enabled)
    }

    @Test
    fun `ピルはセッションのモデルを出す`() {
        val pill = selectModelPill(
            ChatUi(sessionModel = ModelRefDto(id = "m1", providerID = "p"), sessionAgent = "plan"),
        )!!
        assertEquals("m1", pill.label)
        assertEquals("chat-model-pill:p/m1:agent=plan", pill.description)
    }

    // ---- session.error バナー(R3 の中心) ----

    @Test
    fun `isRetryable false はモデル変更を促す`() {
        assertTrue(errorSuggestsModelChange(statusCode = 402, retryable = false))
        assertTrue(errorSuggestsModelChange(statusCode = null, retryable = false))
    }

    @Test
    fun `認証と課金のステータスはモデル変更を促す`() {
        assertTrue(errorSuggestsModelChange(401, null))
        assertTrue(errorSuggestsModelChange(402, null))
        assertTrue(errorSuggestsModelChange(403, null))
        assertTrue(errorSuggestsModelChange(404, null))
    }

    /** **null は「分からない」であって「再試行するな」ではない。** */
    @Test
    fun `材料が無ければモデル変更を促さない`() {
        assertFalse(errorSuggestsModelChange(null, null))
        assertFalse(errorSuggestsModelChange(429, null))
        assertFalse(errorSuggestsModelChange(500, true))
    }

    /**
     * **P5 の行き止まりの検出器**: 402 のとき「再試行」しか出ない画面に戻したら落ちる。
     */
    @Test
    fun `402 のバナーは再試行を出さずモデル変更を出す`() {
        val banner = selectChatBanner(
            ChatUi(sessionError = "Payment Required", sessionErrorStatusCode = 402, sessionErrorRetryable = false),
            nowMs = 0L,
        )!!
        assertEquals(ChatBannerKind.SESSION_ERROR, banner.kind)
        assertNull("402 に再試行を出さない", banner.actionLabel)
        assertEquals("モデルを変更", banner.modelActionLabel)
    }

    @Test
    fun `一時的なエラーでは再試行とモデル変更の両方を出す`() {
        val banner = selectChatBanner(
            ChatUi(sessionError = "rate limited", sessionErrorStatusCode = 429, sessionErrorRetryable = true),
            nowMs = 0L,
        )!!
        assertEquals("再試行", banner.actionLabel)
        assertEquals("モデルを変更", banner.modelActionLabel)
    }

    /** **帯は1本のまま**。session.error 以外にモデル変更の導線を足していない。 */
    @Test
    fun `session error 以外の帯はモデル変更を出さない`() {
        val deleted = selectChatBanner(ChatUi(sessionDeleted = true), 0L)!!
        assertNull(deleted.modelActionLabel)
        val send = selectChatBanner(ChatUi(sendError = "失敗"), 0L)!!
        assertNull(send.modelActionLabel)
        assertNotNull(send.text)
    }
}
