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
3. セッション一覧: カード1枚以上か空状態 `sessions-empty`。`sessions-unauthorized` / `sessions-failed` は失敗
4. logcat にアプリの `FATAL EXCEPTION` が無い

証跡(dump / スクショ / logcat)は tailnet のホスト名・アドレスを含むので **artifact に上げない**。
ログにはランナー側で解決した tailnet アドレスも出さない(`::add-mask::`)。

### 用意するもの

**Tailscale 側**

- ACL にタグを定義し、`tag:ci` から serve のポートだけへ到達できるようにする(PTY の任意コマンド実行と
  プロバイダキーを持つ serve なので、ランナーに tailnet 全体を見せない):

  ```jsonc
  "tagOwners": { "tag:ci": ["autogroup:admin"] },
  "grants": [
    { "src": ["tag:ci"], "dst": ["<serve を動かすマシン>"], "ip": ["tcp:4097"] }
  ]
  ```
- OAuth クライアントを作る(スコープ `auth_keys` の書き込み、タグ `tag:ci`)。
  ランナーは ephemeral ノードとして参加し、ジョブ終了でログアウトする

**GitHub 側**(Settings → Secrets and variables → Actions)

| Secret | 値 |
|---|---|
| `TS_OAUTH_CLIENT_ID` / `TS_OAUTH_SECRET` | 上の OAuth クライアント |
| `OPENCODE_E2E_SERVER_URL` | `http://<マシン名 or 100.x>:4097`(アプリに入れるのと同じもの) |
| `OPENCODE_SERVER_PASSWORD` | serve 起動時と同じ値 |

**実行**: serve を `scripts/serve.ps1` で起動した状態で、Actions → *E2E live (Tailscale → real opencode serve)* →
*Run workflow*。serve が落ちていれば手順1で止まる。
