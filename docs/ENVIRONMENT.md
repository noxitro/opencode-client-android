# このプロジェクトの計測環境

## 専用エミュレータ(2026-09-04 に用意)

| | |
|---|---|
| AVD 名 | `opencode_dev` |
| 起動ポート | **5570**(`adb` からは `emulator-5570`) |
| イメージ | `system-images;android-35;google_apis;x86_64` |
| プロファイル | pixel_7(1080x2400 / density 420) |

起動:

```
"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd opencode_dev -port 5570
```

## なぜ専用にしたか(実際に起きたこと)

以前は他プロジェクトと同じエミュレータを使っていた。2026-09-04 の計測中に:

- 別プロジェクトのアプリが**繰り返し前面を奪った**
- 別プロジェクトの計測テストが走って**ANR** を招き、System UI まで応答不能になった
- 途中で**2台目のエミュレータが起動**し、`adb` が `more than one device` で全部失敗した

**最も危険なのは遅さではない。** 前面を奪われた状態で撃った `uiautomator dump` は
**別アプリの画面を「今の画面」として読ませる**。実際に一度読み違えかけた。
証拠を捏造する経路なので、共有をやめた。

## 計測時の規則

1. **`adb` は必ず `-s emulator-5570` で対象を固定する。** 素の `adb` は台数が増えた瞬間に落ちる
2. **dump の `package=` を毎回確かめる。** 自分のアプリでなければその dump は捨てる
3. **dump の前にリモートのファイルを消す。** `uiautomator dump` が失敗しても
   `/sdcard/*.xml` には前回のファイルが残っており、`adb pull` は成功する ——
   **1つ前の画面を今の画面として読む**。`rm -f` してから撃ち、
   出力が `UI hierchary dumped to:` であることを確認してから pull する
4. `null root node returned by UiTestAutomationBridge` は画面がアニメーション中の一時失敗。
   数秒あけて撃ち直す
5. **測る前にビルドして `install -r`、`pm path` で引き戻してハッシュ照合**(既存の規則)

## CI からの実物 serve 接続テスト(Tailscale 経由)

`.github/workflows/e2e-live.yml`(**手動実行のみ**)。ユーザーと同じ経路 ——
tailnet 越しに `opencode serve` へ —— で、次だけを確かめる。**チャット送信はしない**(LLM 費用が掛かる)。

1. ランナーから `GET /global/health`: 無認証で `401`、正しいパスワードで `200` かつ `healthy: true`
   (serve がパスワード付きで起動していることの確認を兼ねる。AGENTS.md「認証の挙動は起動条件で決まる」)
2. エミュレータ上のアプリで「保存して接続テスト」→ `settings-health-ok:true/<version>`
3. セッション一覧: カード1枚以上か空状態 `sessions-empty`。`sessions-unauthorized` は即失敗、
   `sessions-failed` はタイムアウト(60秒)まで待ってから失敗
4. logcat にアプリの `FATAL EXCEPTION` が無い

証跡(dump / スクショ / logcat)は tailnet のホスト名・アドレスを含むので **artifact に上げない**。
ログにはランナー側で解決した tailnet アドレスとホスト名も出さない(`::add-mask::`、解決失敗時もホスト名を表示しない)。
adb が失敗したときの表示にも入力文字列(パスワード)を載せない。

### 用意するもの

**Tailscale 側**

- ACL にタグを定義し、`tag:ci` から serve のポートだけへ到達できるようにする(PTY の任意コマンド実行と
  プロバイダキーを持つ serve なので、ランナーに tailnet 全体を見せない)。`dst` にマシン名は書けないので
  `hosts` で別名を付ける:

  ```jsonc
  "hosts":     { "serve-pc": "100.x.y.z" },            // serve を動かすマシンの tailnet アドレス
  "tagOwners": { "tag:ci": ["autogroup:admin"] },
  "grants": [
    { "src": ["tag:ci"], "dst": ["serve-pc"], "ip": ["tcp:4097"] }
  ]
  ```
- **既定の全許可ルールを残したままでは意味がない。** 新しい tailnet のポリシーには
  `{"action": "accept", "src": ["*"], "dst": ["*:*"]}` 相当があり、これが残っていると `tag:ci` の
  ランナーも全マシンの全ポートに届く。`*` を `autogroup:member` などに絞り、`tag:ci` が上の grant
  以外に一致しないようにする(grant は一方向なので、serve 側からランナーへは開かない)
- OAuth クライアントを作る(スコープ `auth_keys` の書き込み、タグ `tag:ci`)。
  ランナーは ephemeral ノードとして参加し、ジョブ終了でログアウトする

**GitHub 側**(Settings → Environments → `e2e-live`。ワークフローの job がこの Environment を使う)

| Secret | 値 |
|---|---|
| `TS_OAUTH_CLIENT_ID` / `TS_OAUTH_SECRET` | 上の OAuth クライアント |
| `OPENCODE_E2E_SERVER_URL` | `http://<マシン名 / MagicDNS 名 / 100.x>:4097`(アプリに入れるのと同じもの。port 省略時は 4097) |
| `OPENCODE_SERVER_PASSWORD` | serve 起動時と同じ値。`adb shell input text` で打つので **ASCII のみ・`%s` を含まない・`-` で始まらない** |

- **Environment に Required reviewers を付ける。** 書き込み権限のある人は、ブランチ上でワークフローを
  書き換えて dispatch すれば Secrets を取り出せる(マスクは base64 等で回避できる)。serve のパスワードは
  PTY とプロバイダキーの鍵なので、実行前に人の承認を挟む。Secrets は Repository secrets ではなく
  この Environment の secrets に置く(Repository secrets に置いても動くが、承認を迂回される)
- Environment `e2e-live` を作らずに実行すると、GitHub が保護なしで自動作成する

**実行**: serve を `scripts/serve.ps1` で起動した状態で、Actions → *E2E live (Tailscale → real opencode serve)* →
*Run workflow*。serve が落ちていれば手順1で止まる。
