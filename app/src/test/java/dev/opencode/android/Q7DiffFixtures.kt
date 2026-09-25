package dev.opencode.android

/**
 * Q7 のフィクスチャ。**実物 serve 1.18.21 が返したバイト列をそのまま貼る。**
 *
 * ## なぜ「そのまま」でなければならないか
 *
 * このプロジェクトは「**フィクスチャが実データと違う形だったのでテストは全緑のまま
 * 症状が残る**」を7回繰り返している:
 *
 * | 段 | 症状 | フィクスチャの嘘 |
 * |---|---|---|
 * | P3 | 自分の発言が assistant として左寄せ | role の載り方 |
 * | P4 | permission ダイアログが一度も出ない | `metadata` を文字列で流していた |
 * | P4 | 応答成功後もダイアログが閉じない | `id` だけの replied を「Ignored であるべき」と固定 |
 * | L3 | エラーを出すための変更がエラーを消した | `"error": "文字列"` を流していた |
 *
 * したがってここに**手で書いた patch を置かない**。
 *
 * ## 採取手順(再現できること)
 *
 * 1. 使い捨ての git リポジトリを作り、5つの形の変更を入れる
 *    (追加のみ / 削除のみ / 通常の変更 / 末尾改行なし / バイナリ)
 * 2. 実物 serve(1.18.21、認証あり)に対して
 *    `GET /vcs/diff?mode=git&context=3&directory=<そのリポジトリ>` を発行する
 * 3. 応答の `patch` を**エスケープだけして**貼る
 *
 * `?directory=` が効くことは実測済み(本リポジトリを cwd に起動した serve が、
 * 別ディレクトリの git 状態を返した)。**本リポジトリの作業ツリーは使っていない。**
 * 詳細と生の応答は `docs/API_CONTRACT.md` §Q7。
 */
object Q7DiffFixtures {

    /** 追加のみのファイル。`--- /dev/null` と `@@ -0,0 +1,2 @@`。 */
    const val ADDED = "diff --git a/added2.txt b/added2.txt\n" +
        "new file mode 100644\n" +
        "index 0000000..ce2b18a\n" +
        "--- /dev/null\n" +
        "+++ b/added2.txt\n" +
        "@@ -0,0 +1,2 @@\n" +
        "+brand new\n" +
        "+file here\n"

    /** 削除のみのファイル。`+++ /dev/null` と `@@ -1,6 +0,0 @@`。 */
    const val DELETED = "diff --git a/modified.txt b/modified.txt\n" +
        "deleted file mode 100644\n" +
        "index 624784e..0000000\n" +
        "--- a/modified.txt\n" +
        "+++ /dev/null\n" +
        "@@ -1,6 +0,0 @@\n" +
        "-line1\n" +
        "-line2 CHANGED\n" +
        "-line3\n" +
        "-line4\n" +
        "-line5\n" +
        "-line6 added\n"

    /**
     * 2ハンク。2つ目の `@@ ... @@` の**後ろに関数名の見出し**が付く形
     * (`context=3`。git の既定と同じ)。
     */
    const val TWO_HUNKS = "diff --git a/big.py b/big.py\n" +
        "index 24a0c4f..c96f0aa 100644\n" +
        "--- a/big.py\n" +
        "+++ b/big.py\n" +
        "@@ -1,5 +1,5 @@\n" +
        " def f1():\n" +
        "-    return 111\n" +
        "+    return 111222\n" +
        " \n" +
        " def f2():\n" +
        "     return 2\n" +
        "@@ -50,7 +50,7 @@ def f17():\n" +
        "     return 17\n" +
        " \n" +
        " def f18():\n" +
        "-    return 999\n" +
        "+    return 999888\n" +
        " \n" +
        " def f19():\n" +
        "     return 19\n"

    /**
     * 同じファイルの `context=0`。**件数が省略され(`@@ -2 +2 @@`)**、
     * 見出しが付く。省略は「1行」を意味する。
     */
    const val ZERO_CONTEXT = "diff --git a/big.py b/big.py\n" +
        "index 24a0c4f..c96f0aa 100644\n" +
        "--- a/big.py\n" +
        "+++ b/big.py\n" +
        "@@ -2 +2 @@ def f1():\n" +
        "-    return 111\n" +
        "+    return 111222\n" +
        "@@ -53 +53 @@ def f18():\n" +
        "-    return 999\n" +
        "+    return 999888\n"

    /**
     * 末尾改行なし。**マーカーが2回出る**(削除側の最終行と追加側の最終行の両方)。
     * これが「近似した文字列」では絶対に作れない形である。
     */
    const val NO_NEWLINE = "diff --git a/nonewline.txt b/nonewline.txt\n" +
        "index 27a7ea6..0fec236 100644\n" +
        "--- a/nonewline.txt\n" +
        "+++ b/nonewline.txt\n" +
        "@@ -1,4 +1,5 @@\n" +
        " a\n" +
        " b\n" +
        " c\n" +
        "-d\n" +
        "\\ No newline at end of file\n" +
        "+d\n" +
        "+e\n" +
        "\\ No newline at end of file\n"

    /** バイナリ。**ハンクが1つも無い1行だけの本文。** */
    const val BINARY = "diff --git a/image.bin b/image.bin\n" +
        "index 4eed854..d20f4c9 100644\n" +
        "Binary files a/image.bin and b/image.bin differ\n"

    /** `GET /vcs` の応答(実物、本リポジトリ)。 */
    const val VCS_INFO_JSON = """{"branch":"master","default_branch":"master"}"""

    /** `GET /vcs/status` の応答(実物、使い捨てリポジトリ)。 */
    const val VCS_STATUS_JSON = """[{"file":"added2.txt","additions":2,"deletions":0,"status":"added"},""" +
        """{"file":"big.py","additions":2,"deletions":2,"status":"modified"},""" +
        """{"file":"image.bin","additions":0,"deletions":0,"status":"modified"},""" +
        """{"file":"modified.txt","additions":0,"deletions":6,"status":"deleted"},""" +
        """{"file":"nonewline.txt","additions":2,"deletions":1,"status":"modified"}]"""

    /**
     * `GET /vcs/diff` を `mode` 無しで叩いたときの応答(実測 **400**)。
     * `mode` が required であることの証拠であり、**契約文書の記述の裏**でもある。
     */
    const val VCS_DIFF_MISSING_MODE_JSON =
        """{"name":"BadRequest","data":{"message":"Missing key\n  at [\"mode\"]","kind":"Query"}}"""
}
