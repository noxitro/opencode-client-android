package dev.opencode.android

/**
 * Q8 のフィクスチャ。**すべて実物 serve 1.18.21 が返したバイト列である**
 * (採取手順と生の応答は `docs/API_CONTRACT.md` の「Q8 で使用する分」)。
 *
 * ## 近似した文字列を貼らない
 *
 * このプロジェクトは「**フィクスチャが実データと違う形だったので、テストは全緑のまま
 * 症状だけが残る**」を **7回** 繰り返している(P3 の role / P4 の metadata /
 * P4 の PermissionReplied / L3 の error / Q1 のページング / Q4 のモデル参照 / Q7 の patch)。
 *
 * Q7 は fable が使い捨てリポジトリで採り直して**バイト単位で一致**を確認した。
 * Q8 も同じ形にする —— ここに在る文字列は `curl` の出力そのもので、
 * 手で組んだものは1つも無い(件数を絞るために**要素を間引いた**ものはある。
 * 各定数の doc に何を間引いたかを書いた)。
 *
 * 採取コマンドは各定数の doc に載せてある。**再現できないフィクスチャは実データではない。**
 */
object Q8Fixtures {
    /**
     * `GET /file?path=app`。**ネスト1階層目**で、`build` が `ignored:true`。
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/file?path=app"
     * ```
     *
     * 見どころは**セパレータ**である: `path` は `app\\build\\` のように **`\` 区切りで、
     * ディレクトリは末尾に区切りが付く**。`absolute` は Windows の絶対パス。
     * アプリはこれを `normalizeServerPath` で `app/build` に寄せる。
     */
    const val FILE_LIST_APP_JSON: String = """[{"name":"build","path":"app\\build\\","absolute":"E:\\github\\opencode-android\\app\\build","type":"directory","ignored":true},{"name":"src","path":"app\\src\\","absolute":"E:\\github\\opencode-android\\app\\src","type":"directory","ignored":false},{"name":"build.gradle.kts","path":"app\\build.gradle.kts","absolute":"E:\\github\\opencode-android\\app\\build.gradle.kts","type":"file","ignored":false},{"name":"proguard-rules.pro","path":"app\\proguard-rules.pro","absolute":"E:\\github\\opencode-android\\app\\proguard-rules.pro","type":"file","ignored":false}]"""

    /**
     * `GET /file?path=app/src`。**ネスト2階層目**(ゲートが要求している深さ)。
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/file?path=app/src"
     * ```
     *
     * **入力は `/` 区切りで通る**(`?path=app%5Csrc` と同一の応答であることも実測済み)。
     */
    const val FILE_LIST_APP_SRC_JSON: String = """[{"name":"main","path":"app\\src\\main\\","absolute":"E:\\github\\opencode-android\\app\\src\\main","type":"directory","ignored":false},{"name":"test","path":"app\\src\\test\\","absolute":"E:\\github\\opencode-android\\app\\src\\test","type":"directory","ignored":false}]"""

    /**
     * `GET /find?pattern=the` の1件。**同じ行に `the` が2回**出る
     * (ゲートが名指ししている「1行に同じ語が2回出るケース」)。
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/find?pattern=the"
     * ```
     *
     * 10件のうち submatches が2件のものを1件だけ抜いた(他の要素は間引いた)。
     * `lines.text` が **`\n` で終わっている**ことにも注意 —— そのまま描くと行が1つ余る。
     */
    const val FIND_TWO_SUBMATCHES_JSON: String = """[{"path":{"text":"gradlew.bat"},"lines":{"text":"@rem Licensed under the Apache License, Version 2.0 (the \"License\");\n"},"line_number":4,"absolute_offset":62,"submatches":[{"match":{"text":"the"},"start":20,"end":23},{"match":{"text":"the"},"start":53,"end":56}]}]"""

    /**
     * `GET /find?pattern=path` の1件。**同じ行に4回**。
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/find?pattern=path"
     * ```
     *
     * 10件のうち submatches が4件のものを1件だけ抜いた。
     */
    const val FIND_FOUR_SUBMATCHES_JSON: String = """[{"path":{"text":"scripts/q5_mutations.py"},"lines":{"text":"ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))\n"},"line_number":15,"absolute_offset":529,"submatches":[{"match":{"text":"path"},"start":10,"end":14},{"match":{"text":"path"},"start":26,"end":30},{"match":{"text":"path"},"start":42,"end":46},{"match":{"text":"path"},"start":50,"end":54}]}]"""

    /**
     * `GET /find?pattern=差分ビューア` の1件。**`start`/`end` がバイトオフセットである証拠。**
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/find?pattern=%E5%B7%AE%E5%88%86%E3%83%93%E3%83%A5%E3%83%BC%E3%82%A2"
     * ```
     *
     * `差分ビューア` は **6文字 / 18バイト**で、`start=5` / `end=23`。
     * 文字インデックスとして切ると **`**: unified `** が出る(実測)——
     * **英語のテストしか書かないと絶対に気付かない**種類の欠陥である。
     */
    const val FIND_JAPANESE_JSON: String = """[{"path":{"text":"docs/QUALITY_PLAN.md"},"lines":{"text":"1. **差分ビューア**: unified patch をパースし、`@@` ハンクごとに行を色分け描画(追加=緑背景、削除=赤背景、\n"},"line_number":409,"absolute_offset":30351,"submatches":[{"match":{"text":"差分ビューア"},"start":5,"end":23}]}]"""

    /**
     * `GET /find/file?query=Theme&limit=100`。**セパレータが混在する。**
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/find/file?query=Theme&limit=100"
     * ```
     *
     * 同じ配列の中に `"app/src/main/res/values/themes.xml"`(`/` 区切り)と
     * `"app/src/main/java/dev/opencode/android/ui/theme\\"`(**ディレクトリは末尾に `\`**)が並ぶ。
     */
    const val FIND_FILE_THEME_JSON: String = """["app/src/main/res/values/themes.xml","e2e-artifacts/Q6/156-theme-dark.png","e2e-artifacts/Q6/157-theme-light.xml","e2e-artifacts/Q6/157-theme-light.png","e2e-artifacts/Q6/159-theme-restored.xml","e2e-artifacts/Q6/158-theme-persisted.xml","app/src/main/java/dev/opencode/android/ui/theme\\","app/src/main/java/dev/opencode/android/ui/theme/Theme.kt","app/src/main/java/dev/opencode/android/ui/theme/Contrast.kt","e2e-artifacts/Q5/35-scheme-noport.xml","e2e-artifacts/Q5/38-scheme-noport-result.xml","e2e-artifacts/Q5/r3-38-scheme-noport.xml","e2e-artifacts/Q5/r3-39-scheme-noport-result.xml","e2e-artifacts/Q0/03-chat-streamed.png","e2e-artifacts/Q0/08-chat-streamed-default.png","e2e-artifacts/Q6/03-chat-empty-messages.xml","e2e-artifacts/Q6/03-chat-empty-messages.png","e2e-artifacts/Q3/56-three-more-pending.xml","e2e-artifacts/Q4/55-real-search-gemini-image.png","e2e-artifacts/Q4/55-real-search-gemini-image.xml"]"""

    /**
     * `GET /find/symbol?query=DiffController`。**この環境では常に `[]`。**
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/find/symbol?query=DiffController"   # -> []
     * curl -u opencode:*** "http://127.0.0.1:4097/find/symbol?query=a"                # -> []
     * ```
     *
     * `a` / `e` / `class` / 空文字でも同じ。**200 と `[]` からは「無い」と「索引が使えない」が
     * 区別できない**ので、Controller が校正クエリで区別を作る。
     */
    const val SYMBOL_EMPTY_JSON: String = """[]"""

    /**
     * `GET /file/content?path=one.txt&directory=<q7repo>`。**キーは `type` と `content` の2つだけ。**
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/file/content?path=one.txt&directory=<q7repo>"
     * ```
     *
     * **`diff` も `patch` も来ない。** 変更済みファイル(`git status` が `M` と言うファイル)でも同じだった。
     */
    const val FILE_CONTENT_TEXT_JSON: String = """{"type":"text","content":"ONLY"}"""

    /**
     * `GET /file/content?path=image.bin&directory=<q7repo>`。**`type:"binary"`。**
     *
     * ```
     * curl -u opencode:*** "http://127.0.0.1:4097/file/content?path=image.bin&directory=<q7repo>"
     * ```
     *
     * キーは `type` / `content` / `encoding` / `mimeType` の4つで、
     * **`encoding` と `mimeType` は `content` の後ろに在る** —— 受信を打ち切るとこの2つが失われる。
     * `content` は base64。**画面に出してはならない**(§5b の陰性側ゲート)。
     */
    const val FILE_CONTENT_BINARY_JSON: String = """{"type":"binary","content":"iVBORw0KGgoAAQIDQ0hBTkdFRDL/","encoding":"base64","mimeType":"application/octet-stream"}"""

    /**
     * `GET /file/content?path=big.py&directory=<q7repo>`。
     * **`git status` が `M` と言うファイルでも `diff` が来ない**ことの証拠。
     *
     * ```
     * git -C <q7repo> status --short   # -> " M big.py"
     * curl -u opencode:*** "http://127.0.0.1:4097/file/content?path=big.py&directory=<q7repo>"
     * ```
     *
     * したがって §5b スコープ2 の「`diff`/`patch` を持つ場合は変更ありトグル」は
     * **この serve では一度も出ない**。機構はスタブで測る。
     */
    const val FILE_CONTENT_MODIFIED_JSON: String = """{"type":"text","content":"def f1():\n    return 111222\n\ndef f2():\n    return 2\n\ndef f3():\n    return 3\n\ndef f4():\n    return 4\n\ndef f5():\n    return 5\n\ndef f6():\n    return 6\n\ndef f7():\n    return 7\n\ndef f8():\n    return 8\n\ndef f9():\n    return 9\n\ndef f10():\n    return 10\n\ndef f11():\n    return 11\n\ndef f12():\n    return 12\n\ndef f13():\n    return 13\n\ndef f14():\n    return 14\n\ndef f15():\n    return 15\n\ndef f16():\n    return 16\n\ndef f17():\n    return 17\n\ndef f18():\n    return 999888\n\ndef f19():\n    return 19"}"""

}
