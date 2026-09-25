package dev.opencode.android

import dev.opencode.android.data.ProvidersDto
import dev.opencode.android.data.contractJson
import dev.opencode.android.ui.formatCost
import dev.opencode.android.ui.selectableModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **1モデルのデコード失敗が、他のモデルを道連れにしないこと**(Q10/Q11 差し戻し回収・回収項目B)。
 *
 * このプロジェクトは同じ形の障害を**3回**起こしている。壊れたフィールドは3回とも違うのに
 * (`cost` のキー名 → `cost.tiers[].tier.size` の型 → `capabilities` の部分木)、
 * **壊れ方は3回とも同じ**だった: `GET /provider` は1モデルの1フィールドの失敗で
 * 応答全体(実測 203プロバイダ / 7,338モデル)のデコードが落ち、
 * 一覧が丸ごと `catalog-failed` になる。
 *
 * **だからこの試験はフィールドを列挙しない。** 特定のフィールドを直す検出器は
 * 3回とも「次の1件」を防げなかった。ここで固定するのは
 * **どこが壊れても隣のモデルは残る**という性質のほうである
 * ([dev.opencode.android.data.TolerantModelMapSerializer])。
 *
 * 経路はすべて `contractJson` → `ProvidersDto` → [selectableModels]、
 * つまり**アプリが実際に通る道**である(モデル1件を直接デコードしても、
 * 実機で壊れた増幅器そのものは測れない)。
 */
class Q10ModelIsolationTest {

    /** 健全な2件と、`models` の中の壊れた1件を並べた `GET /provider` 応答を組む。 */
    private fun providerJson(broken: String): String = """
        {"all":[{"id":"p","name":"P","source":"api","models":{
           "before":{"id":"before","providerID":"p","name":"Before","status":"active",
             "capabilities":{"toolcall":true,"input":{"text":true},"output":{"text":true}},
             "cost":{"input":0.4,"output":2,"cache":{"read":0,"write":0}}},
           "broken":$broken,
           "after":{"id":"after","providerID":"p","name":"After","status":"active",
             "capabilities":{"toolcall":true,"input":{"text":true},"output":{"text":true}},
             "cost":{"input":1,"output":3,"cache":{"read":0,"write":0}}}}}],
         "default":{},"connected":["p"]}
    """.trimIndent()

    private fun decode(broken: String) =
        selectableModels(contractJson.decodeFromString<ProvidersDto>(providerJson(broken)))

    /** 隣の2件が必ず残ることを、どの変異でも同じ形で確かめる。 */
    private fun assertNeighboursSurvive(choices: List<dev.opencode.android.ui.ModelChoice>) {
        assertNotNull("前のモデルが消えている", choices.firstOrNull { it.modelId == "before" })
        assertNotNull("後ろのモデルが消えている", choices.firstOrNull { it.modelId == "after" })
        assertEquals("\$0.40 / \$2.00 per 1M", choices.single { it.modelId == "before" }.cost.formatCost())
    }

    /**
     * **`capabilities` を壊した場合**(2回目の E2E ゲートが実機で確認した形)。
     *
     * `ModelCapabilitiesDto.toolcall` は `Boolean?` なので、文字列が来ると例外になる。
     * 以前の手当ては `cost` 専用の serializer だったので、ここは素通りして**全滅**していた。
     */
    @Test
    fun `壊れた capabilities は1モデルに閉じ込める`() {
        val choices = decode(
            """{"id":"broken","providerID":"p","name":"Broken","status":"active",
                "capabilities":{"toolcall":"yes"}}""",
        )
        assertNeighboursSurvive(choices)
        val broken = choices.single { it.modelId == "broken" }
        // **そのモデル自身も残る。** capabilities は「分からない」になるだけで、
        // `canRunSession()` は null を通す(= 選ばせる)。
        assertEquals("Broken", broken.modelName)
    }

    /**
     * **`capabilities.input` / `capabilities.output` の2段目**(`ModelModalityDto`)を壊した場合。
     *
     * 実機で壊されたのはこのネストである。1段目だけを手当てしても意味が無いことを固定する。
     */
    @Test
    fun `壊れた capabilities の入れ子も1モデルに閉じ込める`() {
        val choices = decode(
            """{"id":"broken","providerID":"p","name":"Broken","status":"active",
                "capabilities":{"toolcall":true,"input":{"text":"yes"},"output":{"text":true}}}""",
        )
        assertNeighboursSurvive(choices)
        assertEquals(3, choices.size)
    }

    /** `capabilities` がオブジェクトですらない場合(配列)。 */
    @Test
    fun `capabilities が配列でも1モデルに閉じ込める`() {
        val choices = decode(
            """{"id":"broken","providerID":"p","name":"Broken","status":"active",
                "capabilities":[]}""",
        )
        assertNeighboursSurvive(choices)
        assertEquals(3, choices.size)
    }

    /** `cost` を壊した場合(1回目・2回目の blocker の形)。`cost` だけが null になる。 */
    @Test
    fun `壊れた cost も1モデルに閉じ込める`() {
        val choices = decode(
            """{"id":"broken","providerID":"p","name":"Broken","status":"active",
                "capabilities":{"toolcall":true},
                "cost":{"input":"free","output":15.0}}""",
        )
        assertNeighboursSurvive(choices)
        assertNull(choices.single { it.modelId == "broken" }.cost)
    }

    /** `cost` と `capabilities` が**同時に**壊れていても同じ(片方ずつの手当てでは足りない)。 */
    @Test
    fun `cost と capabilities が同時に壊れても1モデルに閉じ込める`() {
        val choices = decode(
            """{"id":"broken","providerID":"p","name":"Broken","status":"active",
                "capabilities":{"toolcall":"yes"},
                "cost":{"input":"free","output":15.0}}""",
        )
        assertNeighboursSurvive(choices)
        assertNull(choices.single { it.modelId == "broken" }.cost)
    }

    /**
     * **モデルの骨格そのものが契約と違う場合**(`id` が数値)。
     *
     * ここは部分木を落としても読めないので、**その1件だけを捨てる**。
     * 捨てても隣は残る —— 固定したいのは「捨てる/残す」ではなく**巻き添えが無いこと**である。
     */
    @Test
    fun `読めないモデルはその1件だけ捨てる`() {
        val choices = decode("""{"id":123,"providerID":"p","name":"Broken"}""")
        assertNeighboursSurvive(choices)
        assertEquals(2, choices.size)
        assertTrue(choices.none { it.modelId == "broken" })
    }

    /** モデルの値がオブジェクトですらない場合(文字列)。同じく1件だけ捨てる。 */
    @Test
    fun `モデルがオブジェクトでなくても隣は残る`() {
        val choices = decode("\"nonsense\"")
        assertNeighboursSurvive(choices)
        assertEquals(2, choices.size)
    }

    /** 健全な応答では**何も変わらない**(隔離が普通のデコードを歪めていないこと)。 */
    @Test
    fun `健全な応答は今までどおり読める`() {
        val choices = decode(
            """{"id":"broken","providerID":"p","name":"Fine","status":"active",
                "capabilities":{"toolcall":true,"input":{"text":true},"output":{"text":true}},
                "cost":{"input":2,"output":6,"cache":{"read":0,"write":0}}}""",
        )
        assertEquals(3, choices.size)
        assertEquals("\$2.00 / \$6.00 per 1M", choices.single { it.modelId == "broken" }.cost.formatCost())
        // **`toolcall:false` は今までどおり落とす**(隔離が絞り込みを無効化していないこと)。
        val filtered = decode(
            """{"id":"broken","providerID":"p","name":"Voice","status":"active",
                "capabilities":{"toolcall":false}}""",
        )
        assertEquals(2, filtered.size)
    }
}
