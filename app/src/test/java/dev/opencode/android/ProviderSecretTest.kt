package dev.opencode.android

import dev.opencode.android.data.ApiError
import dev.opencode.android.data.ApiResult
import dev.opencode.android.data.CostCacheDto
import dev.opencode.android.data.CostDto
import dev.opencode.android.data.CostTierDto
import dev.opencode.android.data.CostTierRefDto
import dev.opencode.android.data.OpenCodeApi
import dev.opencode.android.data.ProviderDto
import dev.opencode.android.data.ProviderModelDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.ui.selectableModels
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **`GET /provider` の応答に平文で載っている APIキーを、アプリが materialize しないこと**の検出器。
 *
 * ## なぜこの検出器が要るのか
 *
 * spec(`docs/spec/opencode-1.18.21-openapi.json` の `components.schemas.Provider`)は
 * `key: {type: string}` を**任意フィールドとして持っている**。同じ `Provider` スキーマを
 * `GET /provider`(アプリが叩く方)と `GET /config/providers` の**両方**が返す。
 * 事前調査では実物 serve 1.18.21 の `/config/providers` が接続済みプロバイダの
 * **APIキーを平文で返す**ことが確認されている。
 *
 * アプリ側の防御は**2つとも「書かなかったこと」で成立している**:
 *
 *  1. [ProviderDto] が `key` / `options` / `env` を**宣言していない**
 *  2. [contractJson] の `ignoreUnknownKeys = true` が、宣言していないキーを黙って捨てる
 *
 * ## 同じ欠陥形が1段下にもある —— [ProviderModelDto]
 *
 * spec の `Model`(= `Provider.models.<id>`)は required に **`options`(自由形オブジェクト)と
 * `headers`(string map)**を持つ。`headers` はカスタムプロバイダの `Authorization` を、
 * `options` は `apiKey` を運びうる —— **`Provider.key` / `Provider.options` と同種の秘密の器が、
 * 同じ `/provider` 応答の1段下に存在する**。当初この検出器は `ProviderDto` だけを見ており、
 * `ProviderModelDto` に `options` / `headers` を足す変異は**全テストを素通りした**
 * (レビュー実測)。以下の `ProviderModelDto` 用のピン留めと残留チェックがその穴を塞ぐ。
 *
 * 「書かなかったこと」は**変異でいくらでも壊れる**。`ProviderDto` に
 * `val key: String? = null` を1行足すだけで、キーがオブジェクトグラフに入り、
 * `toString()`(data class 自動生成)経由でログ・例外・クラッシュレポートに載る経路が開く。
 * それを止める検出器がこのリポジトリに1本も無かった —— 「検出器の穴という欠陥形」
 * (RUN_PLAN)そのものなので、ここで塞ぐ。
 *
 * ## 何を主張しているか
 *
 *  - `ProviderDto` の宣言フィールドは4つだけである(**追加すると落ちる**)
 *  - `key` / `options` を含む応答をデコードしても、**値がどこにも残らない**
 *  - 本物の HTTP 経由([RecordingHttpServer])でも同じ
 *  - **失敗経路**でも残らない —— 壊れた JSON / 5xx のボディに秘密が載っていても、
 *    [ApiError] は固定文言とステータスコードしか運ばない
 *    (`OpenCodeApi.call` の `catch (_: SerializationException)` が
 *    `e.message` を捨てていることの検出器。ここを `e.message` に変えると落ちる)
 *
 * ## 測っていないこと
 *
 * **実物 serve の `/provider` 応答に実際に `key` が載るかは、ここでは測っていない**
 * (このテストは spec の形に基づく)。サーバーが平文で返すこと自体はアプリの制御外であり、
 * 論点は「受け取った側が保持・表示・ログしないこと」である。
 * また `README.md` / `docs/API_CONTRACT.md` が引く「実物 serve 1.18.21 の
 * `GET /config/providers` が平文でキーを返す」は**事前調査での申告**であり、
 * リポジトリ内に採取証跡(生の応答ダンプ)は無い。
 *
 * **`SerializationException` を固定文言に潰す箇所は本体に2つあるが、ここが押さえているのは
 * `OpenCodeApi.call`(JSON 応答の共通経路 = `/provider` が通る方)だけである。**
 * もう一方はファイル内容用の別経路(`readFileContent` の `decodeFileContent` を囲む
 * `catch`)で、プロバイダ応答はそこを通らないため、この検出器の対象外にしてある
 * (固定文言であること自体は同じ形で書かれている)。
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class) // descriptor.getElementName
class ProviderSecretTest {

    /** **本物のキーではない。** 形だけ真似た固定値(AGENTS.md「シークレットをコミットしない」)。 */
    private val fakeKey = "FAKE-PROVIDER-KEY-do-not-use-0123456789"

    /** `key` / `options` / `env` を**全部載せた** `GET /provider` 応答。 */
    private val payloadWithSecret = """
        {
          "all": [
            {
              "id": "mistral",
              "name": "Mistral",
              "source": "api",
              "env": ["MISTRAL_API_KEY"],
              "key": "$fakeKey",
              "options": {"apiKey": "$fakeKey", "baseURL": "https://api.mistral.ai/v1"},
              "models": {
                "mistral-medium-2508": {
                  "id": "mistral-medium-2508",
                  "name": "Mistral Medium 3.1",
                  "capabilities": {
                    "toolcall": true,
                    "input": {"text": true},
                    "output": {"text": true}
                  }
                }
              }
            }
          ],
          "default": {"mistral": "mistral-medium-2508"},
          "connected": ["mistral"]
        }
    """.trimIndent()

    /** **本物のキーではない。** モデル階層(`Provider.models.<id>`)側の器に入れる固定値。 */
    private val fakeModelKey = "FAKE-MODEL-LEVEL-KEY-do-not-use-9876543210"

    /**
     * `Provider.models.<id>` に **`options` と `headers` を載せた** `GET /provider` 応答。
     *
     * spec の `Model` はこの2つを required に持つ。`headers` はカスタムプロバイダの
     * `Authorization` を、`options` は `apiKey` を運びうる。
     */
    private val payloadWithModelSecret = """
        {
          "all": [
            {
              "id": "custom",
              "name": "Custom",
              "source": "config",
              "models": {
                "custom-medium": {
                  "id": "custom-medium",
                  "name": "Custom Medium",
                  "options": {"apiKey": "$fakeModelKey", "baseURL": "https://example.invalid/v1"},
                  "headers": {"Authorization": "Bearer $fakeModelKey"},
                  "capabilities": {
                    "toolcall": true,
                    "input": {"text": true},
                    "output": {"text": true}
                  }
                }
              }
            }
          ],
          "default": {"custom": "custom-medium"},
          "connected": ["custom"]
        }
    """.trimIndent()

    private lateinit var server: RecordingHttpServer
    private lateinit var api: OpenCodeApi

    @Before
    fun setUp() {
        server = RecordingHttpServer()
        api = OpenCodeApi(server.baseUrl, "test-pass")
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---------------------------------------------------------------------
    // 1. DTO の形そのもの —— ここが変異の入口
    // ---------------------------------------------------------------------

    /**
     * `ProviderDto` が宣言するのは `id` `name` `source` `models` の**4つだけ**。
     *
     * spec の `Provider` は required に `env` `options` を、任意に `key` を持つが、
     * **アプリは1つも宣言しない**。`key` を足す変異はここで落ちる。
     */
    @Test
    fun `ProviderDto は key も options も env も宣言しない`() {
        val descriptor = ProviderDto.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertEquals(listOf("id", "name", "source", "models"), names)
    }

    /** `ProvidersDto` 側も同様(`all` `default` `connected` の3つだけ)。 */
    @Test
    fun `ProvidersDto は3フィールドだけ`() {
        val descriptor = ProvidersDto.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertEquals(listOf("all", "default", "connected"), names)
    }

    /**
     * `ProviderModelDto` が宣言するのは `id` `providerID` `name` `family` `status`
     * `capabilities` `cost` の**7つだけ**。
     *
     * spec の `Model` は required に `options`(自由形オブジェクト)と `headers`(string map)を
     * 持つが、**アプリはどちらも宣言しない**。`val options: JsonObject? = null` /
     * `val headers: Map<String, String>? = null` を足す変異はここで落ちる。
     */
    @Test
    fun `ProviderModelDto は options も headers も宣言しない`() {
        val descriptor = ProviderModelDto.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertEquals(
            listOf("id", "providerID", "name", "family", "status", "capabilities", "cost"),
            names,
        )
    }

    /**
     * `CostDto` の階層(`CostDto` → `CostCacheDto` / `CostTierDto` → `CostTierRefDto`)にも
     * 同じピンを打つ(Q10 レビュー major-1)。
     *
     * Q10 で `Model.cost` を宣言したことで、**`/provider` 応答から materialize される
     * オブジェクトが1階層増えた**。ピン留めが `ProviderDto` / `ProviderModelDto` 止まりだと、
     * `CostDto` に `val apiKey: String? = null` を足す変異が全テストを素通りする ——
     * `ProviderModelDto` に `options` を足す変異が素通りしていたのと**同じ穴**である。
     *
     * ピン留めしているのは同時に**契約の形**でもある(blocker-1 の回帰検出):
     * `cache` は**ネストしたオブジェクト**であり、`cache_read` のようなフラットな
     * フィールドを足す変異はここで落ちる。
     */
    @Test
    fun `CostDto の階層は契約のフィールドしか宣言しない`() {
        fun names(d: kotlinx.serialization.descriptors.SerialDescriptor) =
            (0 until d.elementsCount).map { d.getElementName(it) }

        assertEquals(
            listOf("input", "output", "cache", "tiers", "experimentalOver200K"),
            names(CostDto.serializer().descriptor),
        )
        assertEquals(listOf("read", "write"), names(CostCacheDto.serializer().descriptor))
        assertEquals(
            listOf("input", "output", "cache", "tier"),
            names(CostTierDto.serializer().descriptor),
        )
        assertEquals(listOf("type", "size"), names(CostTierRefDto.serializer().descriptor))
    }

    // ---------------------------------------------------------------------
    // 2. デコード結果に残らない
    // ---------------------------------------------------------------------

    /**
     * `cost` の中に秘密を混ぜた応答をデコードしても、**値がどこにも残らない**。
     *
     * `Cost` は `additionalProperties:false` の契約なのでサーバーが余計なキーを載せることは
     * 無いはずだが、この検出器が押さえているのは「サーバーが何を載せてくるか」ではなく
     * 「**アプリが何を materialize するか**」である(`CostDto` に自由形フィールドを足す変異)。
     */
    @Test
    fun `cost に混ざった値もデコード結果に残らない`() {
        val payload = payloadWithModelSecret.replace(
            """"name": "Custom Medium",""",
            """"name": "Custom Medium",
                  "cost": {"input": 3.0, "output": 15.0, "cache": {"read": 0.3, "write": 3.75},
                           "apiKey": "$fakeModelKey"},""",
        )
        val decoded = contractJson.decodeFromString<ProvidersDto>(payload)
        val model = decoded.all.single().models.getValue("custom-medium")
        val cost = model.cost!!
        assertEquals(3.0, cost.input!!, 0.0001)
        assertEquals(0.3, cost.cache!!.read!!, 0.0001)
        assertFalse("cost の toString に値が出ている", decoded.toString().contains(fakeModelKey))
        assertFalse(selectableModels(decoded).toString().contains(fakeModelKey))
    }

    /**
     * `key` / `options` を含む応答を**契約の Json でデコードできる**(= `ignoreUnknownKeys` が効いている)
     * うえで、**キーの値がオブジェクトグラフのどこにも残らない**。
     *
     * `ignoreUnknownKeys = false` への変異は「デコードできる」側で、
     * `ProviderDto` に `key` を足す変異は「残らない」側で落ちる。
     */
    @Test
    fun `key を含む応答をデコードしてもキーは残らない`() {
        val decoded = contractJson.decodeFromString<ProvidersDto>(payloadWithSecret)

        assertEquals(listOf("mistral"), decoded.connected)
        assertEquals(1, decoded.all.size)
        assertFalse("DTO の toString にキーが出ている", decoded.toString().contains(fakeKey))
    }

    /** 画面に出す [dev.opencode.android.ui.ModelChoice] にも載らない(UI 露出の経路)。 */
    @Test
    fun `モデル選択に出る文字列にキーは載らない`() {
        val decoded = contractJson.decodeFromString<ProvidersDto>(payloadWithSecret)
        val choices = selectableModels(decoded)

        assertEquals(1, choices.size)
        assertEquals("Mistral / Mistral Medium 3.1", choices[0].label)
        assertFalse(choices.toString().contains(fakeKey))
    }

    /**
     * モデル階層の `options` / `headers` を含む応答も**デコードでき**、
     * かつ**値がオブジェクトグラフのどこにも残らない**。
     *
     * `ProviderModelDto` に `options` / `headers` を足す変異は、descriptor のピン留めと
     * ここの `toString()` 残留チェックの**両方**で落ちる。
     */
    @Test
    fun `モデル階層の options と headers を含む応答をデコードしても値は残らない`() {
        val decoded = contractJson.decodeFromString<ProvidersDto>(payloadWithModelSecret)

        assertEquals(listOf("custom"), decoded.connected)
        val model = decoded.all.single().models.getValue("custom-medium")
        assertEquals("Custom Medium", model.name)
        assertFalse("モデル DTO の toString に値が出ている", model.toString().contains(fakeModelKey))
        assertFalse("DTO の toString に値が出ている", decoded.toString().contains(fakeModelKey))
    }

    /** 画面に出す [dev.opencode.android.ui.ModelChoice] にもモデル階層の秘密は載らない。 */
    @Test
    fun `モデル選択に出る文字列にモデル階層の秘密は載らない`() {
        val decoded = contractJson.decodeFromString<ProvidersDto>(payloadWithModelSecret)
        val choices = selectableModels(decoded)

        assertEquals(1, choices.size)
        assertEquals("Custom / Custom Medium", choices[0].label)
        assertFalse(choices.toString().contains(fakeModelKey))
    }

    // ---------------------------------------------------------------------
    // 3. 本物の HTTP 経由でも同じ
    // ---------------------------------------------------------------------

    @Test
    fun `listProviders の戻り値にキーは載らない`() = runTest {
        server.respond("/provider", payloadWithSecret)

        val result = api.listProviders()

        assertTrue("$result", result is ApiResult.Ok)
        val value = (result as ApiResult.Ok).value
        assertEquals(listOf("mistral"), value.connected)
        assertFalse(value.toString().contains(fakeKey))
    }

    /** モデル階層の `options` / `headers` も、本物の HTTP 経由で戻り値に載らない。 */
    @Test
    fun `listProviders の戻り値にモデル階層の秘密は載らない`() = runTest {
        server.respond("/provider", payloadWithModelSecret)

        val result = api.listProviders()

        assertTrue("$result", result is ApiResult.Ok)
        val value = (result as ApiResult.Ok).value
        assertEquals(listOf("custom"), value.connected)
        assertFalse(value.toString().contains(fakeModelKey))
    }

    // ---------------------------------------------------------------------
    // 4. 失敗経路 —— 例外メッセージにボディを載せない
    //
    // ここが押さえているのは `OpenCodeApi.call`(JSON 応答の共通経路)側だけである。
    // 本体には `SerializationException` を固定文言に潰す箇所がもう1つ
    // (`readFileContent` の `decodeFileContent` を囲む catch)あるが、
    // プロバイダ応答はそこを通らないのでこの検出器の対象外。
    // ---------------------------------------------------------------------

    /**
     * **壊れた JSON** のボディに秘密が載っていても、`ApiError` は固定文言しか運ばない。
     *
     * kotlinx.serialization の `SerializationException` は**ボディの中身を引用した**
     * メッセージを出す(`Unexpected JSON token at offset ...`)。`OpenCodeApi.call` は
     * それを `catch (_: SerializationException)` で**捨てて**固定文言に置き換えている。
     * ここを `e.message` に変える変異(= 一見親切なエラー表示)は、この検出器で落ちる。
     */
    @Test
    fun `パース失敗のエラー文言にボディは載らない`() = runTest {
        server.respond("/provider", """{"all": [{"key": "$fakeKey", broken}]""")

        val result = api.listProviders()

        assertTrue("$result", result is ApiResult.Err)
        val error = (result as ApiResult.Err).error
        assertTrue("$error", error is ApiError.Network)
        assertFalse((error as ApiError.Network).message.contains(fakeKey))
        assertFalse(error.toString().contains(fakeKey))
    }

    /** **5xx のボディ**に秘密が載っていても、運ぶのはステータスコードだけ。 */
    @Test
    fun `HTTPエラーはステータスコードしか運ばない`() = runTest {
        server.respond("/provider", """{"key": "$fakeKey"}""", status = 500)

        val result = api.listProviders()

        assertEquals(ApiResult.Err(ApiError.Http(500)), result)
        assertFalse(result.toString().contains(fakeKey))
    }
}
