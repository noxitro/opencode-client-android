package dev.opencode.android

import dev.opencode.android.data.CostCacheDto
import dev.opencode.android.data.CostDto
import dev.opencode.android.data.CostTierDto
import dev.opencode.android.data.ModelCapabilitiesDto
import dev.opencode.android.data.ModelModalityDto
import dev.opencode.android.data.ProviderDto
import dev.opencode.android.data.ProviderModelDto
import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.ui.formatCost
import dev.opencode.android.ui.selectableModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q10: `Model.cost` を Android で読めることの検出器。
 *
 * **形の正本はピン留めした `docs/spec/opencode-1.18.21-openapi.json` の
 * `components.schemas.Model.properties.cost`** である。
 *
 * ```
 * Cost = { input, output, cache:{read,write},
 *          tiers?:[{input,output,cache:{read,write}, tier:{type:"context",size}}],
 *          experimentalOver200K?:{input,output,cache:{read,write}} }   // additionalProperties:false
 * ```
 *
 * 1周目のこのテストは `models.dev` の生形(`cache_read` / `context_over_200k`)を
 * 手書きフィクスチャで固定しており、**契約と一致しない DTO をテストが守っていた**
 * (レビュー blocker-1)。serve が返すキー名と1つも一致しないので、実機では
 * `cache_read` 系が黙って常に null になる。ここで正しい形へ差し替える。
 *
 * ここで固定するのは4つ:
 *   1. DTO が**契約の形**で落ちない(`cache` はネストしたオブジェクト)
 *   2. **アプリの実経路である [contractJson]** でデコードできる(レビュー minor-6)
 *   3. 表示が「無料」と「未提供」を混同しない、かつ「安い」を「無料」に化けさせない
 *   4. 段階料金を落とさず、**閾値の数字を `tier.size` から出す**
 */
class Q10CostTest {

    private fun cost(
        input: Double,
        output: Double,
        cacheRead: Double? = null,
        cacheWrite: Double? = null,
        experimentalOver200K: CostTierDto? = null,
        tiers: List<CostTierDto>? = null,
    ) = CostDto(
        input = input,
        output = output,
        cache = if (cacheRead == null && cacheWrite == null) null else CostCacheDto(read = cacheRead, write = cacheWrite),
        tiers = tiers,
        experimentalOver200K = experimentalOver200K,
    )

    /**
     * `tiers[]` の1件を**アプリの実経路 [contractJson] で組み立てる**。
     *
     * Kotlin のコンストラクタで `CostTierRefDto(size = 200_000.0)` と書くと、
     * **`size` の型がテストのソースに焼き付く** —— `Double` を `Long` に戻す変異は
     * 「テストが落ちる」ではなく「テストがコンパイルできない」になり、
     * 変異校正で **DETECTED と NOT_WRITABLE の区別がつかなくなる**。
     * 実機で起きた障害は**デコードの失敗**なので、検出器もデコードを通す。
     */
    private fun tier(input: Double, output: Double, size: Double = 200_000.0): CostTierDto =
        contractJson.decodeFromString(
            """{"input":$input,"output":$output,"tier":{"type":"context","size":$size}}""",
        )

    // ---- formatCost ----

    @Test
    fun `null は null`() {
        assertNull((null as CostDto?).formatCost())
    }

    @Test
    fun `input が欠けたら null`() {
        assertNull(CostDto(input = null, output = 15.0).formatCost())
    }

    @Test
    fun `無料は Free`() {
        assertEquals("Free", cost(0.0, 0.0).formatCost())
        assertEquals("Free", cost(0.0, 0.0, cacheRead = 0.0).formatCost())
    }

    /**
     * **Free は `input==0 && output==0` だけで決まる**(レビュー minor-2)。
     * KDoc/契約文書はそう書いてあるのに実装は cache も見ていたので、実装を doc に合わせた。
     * cache だけ有料という組み合わせは実データに無く、あっても「本体無料」は事実である。
     */
    @Test
    fun `cache が有料でも本体が0なら Free`() {
        assertEquals("Free", cost(0.0, 0.0, cacheRead = 0.3, cacheWrite = 1.0).formatCost())
    }

    @Test
    fun `基本料金は per 1M で2桁`() {
        assertEquals("\$3.00 / \$15.00 per 1M", cost(3.0, 15.0).formatCost())
        assertEquals("\$0.30 / \$1.20 per 1M", cost(0.3, 1.2).formatCost())
    }

    /**
     * **サブセント価格を `$0.00` に潰さない**(レビュー minor-3)。
     * `%.2f` 固定だと $0.005 が `$0.00` になり、有料モデルが無料に見える。
     * 境界: 0.01 はちょうど2桁で表せるので2桁、0.009 は4桁に落ちる。
     */
    @Test
    fun `サブセントは桁を増やして0に潰さない`() {
        assertEquals("\$0.01 / \$0.02 per 1M", cost(0.01, 0.02).formatCost())
        assertEquals("\$0.0050 / \$0.0075 per 1M", cost(0.005, 0.0075).formatCost())
        assertEquals("\$0.0001 / \$3.00 per 1M", cost(0.0001, 3.0).formatCost())
    }

    @Test
    fun `cache read があれば追記する`() {
        assertEquals("\$3.00 / \$15.00 per 1M · cached \$0.30", cost(3.0, 15.0, cacheRead = 0.3).formatCost())
    }

    @Test
    fun `cache write も追記する`() {
        assertEquals(
            "\$3.00 / \$15.00 per 1M · cached \$0.30 · write \$3.75",
            cost(3.0, 15.0, cacheRead = 0.3, cacheWrite = 3.75).formatCost(),
        )
    }

    /** 実測(`e2e-artifacts/Q10Q11/provider.json`)の全モデルがこの形: `cache` はあるが 0/0。 */
    @Test
    fun `cache が0のときは追記しない`() {
        assertEquals("\$0.40 / \$2.00 per 1M", cost(0.4, 2.0, cacheRead = 0.0, cacheWrite = 0.0).formatCost())
    }

    @Test
    fun `experimentalOver200K があれば 200K超を付ける`() {
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >200K \$6.00 / \$22.50",
            cost(3.0, 15.0, experimentalOver200K = CostTierDto(input = 6.0, output = 22.5)).formatCost(),
        )
    }

    @Test
    fun `tiers が experimentalOver200K より優先される`() {
        val c = cost(
            3.0, 15.0,
            experimentalOver200K = CostTierDto(input = 9.0, output = 99.0),
            tiers = listOf(tier(6.0, 22.5)),
        )
        assertEquals("\$3.00 / \$15.00 per 1M · >200K \$6.00 / \$22.50", c.formatCost())
    }

    /**
     * **閾値は `tier.size` の実際の数字から作る**(レビュー minor-1)。
     * 以前は `tiers.firstOrNull()` を取るだけで見出しは `>200K` 固定だったので、
     * 128K や 1M で段が変わるモデルに嘘の閾値を表示していた。
     */
    @Test
    fun `閾値は tier size から作る`() {
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >128K \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(tier(6.0, 22.5, size = 128_000.0))).formatCost(),
        )
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >1M \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(tier(6.0, 22.5, size = 1_000_000.0))).formatCost(),
        )
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >123456 \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(tier(6.0, 22.5, size = 123_456.0))).formatCost(),
        )
    }

    /**
     * **`tier.size` は `number` であって `integer` ではない**(ピン留め済み spec)。
     * 非整数は表示のときだけ四捨五入する。0以下は既定(`>200K`)に落とす。
     * (`NaN` / `Infinity` も同じ既定に落とすが、[contractJson] は特殊浮動小数点を受け付けない
     * ので**サーバー経由では到達しない**。ここで固定できるのは JSON が運べる形だけである。)
     */
    @Test
    fun `非整数の size は表示のときだけ丸める`() {
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >200001 \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(tier(6.0, 22.5, size = 200_000.5))).formatCost(),
        )
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >128K \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(tier(6.0, 22.5, size = 127_999.6))).formatCost(),
        )
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >200K \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(tier(6.0, 22.5, size = -1.0))).formatCost(),
        )
    }

    /** `tier` が欠けた `tiers[]` 要素は `>200K` に落とす(推測せず既定へ)。 */
    @Test
    fun `tier が欠けたら 200K に落とす`() {
        assertEquals(
            "\$3.00 / \$15.00 per 1M · >200K \$6.00 / \$22.50",
            cost(3.0, 15.0, tiers = listOf(CostTierDto(input = 6.0, output = 22.5))).formatCost(),
        )
    }

    @Test
    fun `Free でも tiers は付けない`() {
        val c = cost(0.0, 0.0, tiers = listOf(tier(6.0, 22.5)))
        assertEquals("Free", c.formatCost())
    }

    // ---- selectableModels が cost を引き継ぐ ----

    @Test
    fun `selectableModels は cost を保持する`() {
        val c = cost(3.0, 15.0, cacheRead = 0.3)
        val providers = ProvidersDto(
            all = listOf(
                ProviderDto(
                    id = "p", name = "P", source = "api",
                    models = mapOf(
                        "m1" to ProviderModelDto(id = "m1", name = "M1", status = "active", capabilities = ModelCapabilitiesDto(toolcall = true, input = ModelModalityDto(text = true), output = ModelModalityDto(text = true)), cost = c),
                    ),
                ),
            ),
            default = emptyMap(),
            connected = listOf("p"),
        )
        val choice = selectableModels(providers).single()
        assertEquals(c, choice.cost)
        assertEquals("\$3.00 / \$15.00 per 1M · cached \$0.30", choice.cost.formatCost())
    }

    @Test
    fun `cost が無いモデルは null のまま（無料と区別する）`() {
        val providers = ProvidersDto(
            all = listOf(
                ProviderDto(
                    id = "p", name = "P", source = "api",
                    models = mapOf("m1" to ProviderModelDto(id = "m1", name = "M1", status = "active", capabilities = ModelCapabilitiesDto(toolcall = true))),
                ),
            ),
            default = emptyMap(),
            connected = listOf("p"),
        )
        val choice = selectableModels(providers).single()
        assertNull(choice.cost)
        assertNull(choice.cost.formatCost())
    }

    // ---- JSON パース（**契約の形**で落ちないこと。デコーダはアプリの実経路 contractJson） ----

    /**
     * ピン留め済み spec の `Cost` をそのまま書いたフィクスチャ。
     * `cache` が**ネストしたオブジェクト**であること、`experimentalOver200K` という
     * キー名であることが要点(ここが blocker-1 の本体)。
     */
    @Test
    fun `契約の形の JSON を CostDto で読める`() {
        val json = """
            {"input":3.0,"output":15.0,"cache":{"read":0.3,"write":3.75},
             "experimentalOver200K":{"input":6.0,"output":22.5,"cache":{"read":0.6,"write":7.5}},
             "tiers":[{"input":6.0,"output":22.5,"cache":{"read":0.6,"write":7.5},
                       "tier":{"type":"context","size":200000}}]}
        """.trimIndent()
        val decoded = contractJson.decodeFromString<CostDto>(json)
        assertEquals(3.0, decoded.input!!, 0.0001)
        val cache = decoded.cache!!
        assertEquals(0.3, cache.read!!, 0.0001)
        assertEquals(3.75, cache.write!!, 0.0001)
        val over = decoded.experimentalOver200K!!
        assertEquals(6.0, over.input!!, 0.0001)
        assertEquals(0.6, over.cache!!.read!!, 0.0001)
        val tiers = decoded.tiers!!
        assertEquals(1, tiers.size)
        // 型を焼き付けない形で値だけ見る(上の `tier()` の doc と同じ理由)。
        assertEquals("context", tiers[0].tier!!.type)
        assertEquals(
            "\$3.00 / \$15.00 per 1M · cached \$0.30 · write \$3.75 · >200K \$6.00 / \$22.50",
            decoded.formatCost(),
        )
    }

    /**
     * **`models.dev` の生形はバインドされない**(そういう契約ではない)。
     *
     * これが blocker-1 の回帰検出器である。1周目の DTO はこの形でキーを宣言していたので、
     * 実物 serve の応答からは何も取れていなかった。逆向きに固定しておく:
     * models.dev 形を流し込んでも `ignoreUnknownKeys` に落ちて null のままになる。
     */
    @Test
    fun `models_dev の生形のキーは契約ではないので入らない`() {
        val json = """
            {"input":3.0,"output":15.0,"cache_read":0.3,"cache_write":3.75,
             "context_over_200k":{"input":6.0,"output":22.5}}
        """.trimIndent()
        val decoded = contractJson.decodeFromString<CostDto>(json)
        assertNull(decoded.cache)
        assertNull(decoded.experimentalOver200K)
        assertEquals("\$3.00 / \$15.00 per 1M", decoded.formatCost())
    }

    /**
     * 採取証跡(`e2e-artifacts/Q10Q11/provider.json`)に実在した形をそのまま。
     * 全モデルが `cache:{read:0,write:0}` だった。
     */
    @Test
    fun `採取した実応答の形を読める`() {
        val json = """{"input":0.4,"output":2,"cache":{"read":0,"write":0}}"""
        val decoded = contractJson.decodeFromString<CostDto>(json)
        assertEquals(0.4, decoded.input!!, 0.0001)
        assertEquals(2.0, decoded.output!!, 0.0001)
        assertEquals(0.0, decoded.cache!!.read!!, 0.0001)
        assertEquals("\$0.40 / \$2.00 per 1M", decoded.formatCost())
    }

    @Test
    fun `tiers が null でも落ちない`() {
        val json = """{"input":3.0,"output":15.0,"tiers":null}"""
        val decoded = contractJson.decodeFromString<CostDto>(json)
        assertEquals(3.0, decoded.input!!, 0.0001)
        assertEquals("\$3.00 / \$15.00 per 1M", decoded.formatCost())
    }

    /** `GET /provider` 丸ごとの経路でも cost が届く(DTO 単体ではなく実際の入れ子で)。 */
    @Test
    fun `provider 応答の入れ子から cost が届く`() {
        val json = """
            {"all":[{"id":"mistral","name":"Mistral","source":"api","models":{
               "mistral-medium-latest":{"id":"mistral-medium-latest","providerID":"mistral",
                 "name":"Mistral Medium","status":"active",
                 "capabilities":{"toolcall":true,"input":{"text":true},"output":{"text":true}},
                 "cost":{"input":0.4,"output":2,"cache":{"read":0,"write":0}},
                 "limit":{"context":262144,"output":262144}}}}],
             "default":{"mistral":"mistral-medium-latest"},"connected":["mistral"]}
        """.trimIndent()
        val providers = contractJson.decodeFromString<ProvidersDto>(json)
        val choice = selectableModels(providers).single()
        assertEquals("\$0.40 / \$2.00 per 1M", choice.cost.formatCost())
        assertTrue(choice.cost!!.cache != null)
    }

    /**
     * **`tier.size` が非整数でもデコードが成功する**(E2E ゲートが実機で見つけた blocker)。
     *
     * ピン留め済み spec の `...cost.items.properties.tier.properties.size` は
     * `{"type":"number"}`。`Long` で宣言していると kotlinx.serialization がここで例外を投げ、
     * 実機では `GET /provider` の応答**全体**が落ちてカタログが丸ごと消えた
     * (`200000.5` → `200000` に戻すだけで復帰することを対照実験で確認済み)。
     *
     * `CostDto` を**直接**読むので、[dev.opencode.android.data.TolerantModelMapSerializer] の
     * 握り潰しを経由しない —— 型を `Long` に戻す変異はここで必ず落ちる。
     */
    @Test
    fun `非整数の tier size でもデコードできる`() {
        val json = """
            {"input":3.0,"output":15.0,
             "tiers":[{"input":6.0,"output":22.5,"tier":{"type":"context","size":200000.5}}]}
        """.trimIndent()
        val decoded = contractJson.decodeFromString<CostDto>(json)
        assertEquals("200000.5", decoded.tiers!![0].tier!!.size.toString())
        assertEquals("\$3.00 / \$15.00 per 1M · >200001 \$6.00 / \$22.50", decoded.formatCost())
    }

    /** 上と同じものを `GET /provider` 丸ごとの経路で(実機で壊れたのはこの経路である)。 */
    @Test
    fun `非整数の tier size は provider 応答全体を落とさない`() {
        val json = """
            {"all":[{"id":"p","name":"P","source":"api","models":{
               "m1":{"id":"m1","providerID":"p","name":"M1","status":"active",
                 "capabilities":{"toolcall":true},
                 "cost":{"input":3.0,"output":15.0,
                   "tiers":[{"input":6.0,"output":22.5,"tier":{"type":"context","size":200000.5}}]}}}}],
             "default":{},"connected":["p"]}
        """.trimIndent()
        val providers = contractJson.decodeFromString<ProvidersDto>(json)
        val choice = selectableModels(providers).single()
        assertEquals("\$3.00 / \$15.00 per 1M · >200001 \$6.00 / \$22.50", choice.cost.formatCost())
    }

    /**
     * **1モデルの `cost` が壊れていても、他のモデルは消えない**(回収の設計側)。
     *
     * 型を1つ直すだけでは次の1件を防げない。効いていた増幅器は
     * 「1フィールドの失敗が応答全体を道連れにする」ほうなので、
     * [dev.opencode.android.data.TolerantModelMapSerializer] で**モデル単位**に閉じ込めた
     * (回収の1周目は `cost` 専用の serializer だったが、3件目の欠陥は `capabilities` に来た。
     * `Q10ModelIsolationTest` がフィールドを列挙しない形の検出器を持っている)。
     * 壊れたモデルは**一覧から消えず**、`cost` だけが null(= 未取得。「無料」ではない)になる。
     */
    @Test
    fun `壊れた cost は1モデルに閉じ込める`() {
        val json = """
            {"all":[{"id":"p","name":"P","source":"api","models":{
               "bad":{"id":"bad","providerID":"p","name":"Bad","status":"active",
                 "capabilities":{"toolcall":true},
                 "cost":{"input":"free","output":15.0}},
               "good":{"id":"good","providerID":"p","name":"Good","status":"active",
                 "capabilities":{"toolcall":true},
                 "cost":{"input":0.4,"output":2,"cache":{"read":0,"write":0}}}}}],
             "default":{},"connected":["p"]}
        """.trimIndent()
        val providers = contractJson.decodeFromString<ProvidersDto>(json)
        val choices = selectableModels(providers)
        assertEquals(2, choices.size)
        val bad = choices.single { it.modelId == "bad" }
        val good = choices.single { it.modelId == "good" }
        assertNull(bad.cost)
        assertNull(bad.cost.formatCost())
        assertEquals("\$0.40 / \$2.00 per 1M", good.cost.formatCost())
    }

    /** 明示的な `"cost":null` は素通りする(`explicitNulls=false` の下でも例外にしない)。 */
    @Test
    fun `cost が明示 null でも落ちない`() {
        val json = """
            {"all":[{"id":"p","name":"P","source":"api","models":{
               "m1":{"id":"m1","providerID":"p","name":"M1","status":"active",
                 "capabilities":{"toolcall":true},"cost":null}}}],
             "default":{},"connected":["p"]}
        """.trimIndent()
        val providers = contractJson.decodeFromString<ProvidersDto>(json)
        assertNull(selectableModels(providers).single().cost)
    }
}
