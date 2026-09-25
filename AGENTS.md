# opencode-android エージェント規約

## プロジェクトの目的

opencode serve (REST/SSE) を消費するAndroidクライアント。最小MVPスコープ:
サーバー接続設定 / セッション一覧 / チャットストリーミング / permission承認 / abort。

## ハードな制約

- **API契約は `docs/API_CONTRACT.md` を唯一の真実とする**。opencode 1.18.21 の実機specから採取済み。
  推測でフィールド名を書かない。契約に無いものが必要になったら、実機serve(`/doc`)で確認してから
  契約文書を更新し、その更新をレビューが検証する。
- **シークレットをコミットしない**。`OPENCODE_SERVER_PASSWORD` の値をソース・テスト・ログに書かない。
  テストはローカルのスタブサーバーで行い、実物serveへのスモークは環境変数から取得する。
- **サーバーが返すシークレットをアプリに取り込まない**。spec の `Provider` は任意フィールド
  `key`(= プロバイダのAPIキー平文)を持ち、`GET /provider` / `GET /config/providers` の
  両方がこれを返しうる。`ProviderDto` に `key` / `options` / `env` を足さない。
  **同じ形が1段下(`Provider.models.<id>` = spec の `Model`)にもある。**`Model` は required に
  `options`(自由形オブジェクト。`apiKey` を運びうる)と `headers`(string map。カスタムプロバイダの
  `Authorization` を運びうる)を持つ。**`ProviderModelDto` に `options` / `headers` を足さない。**
  **OkHttp の logging-interceptor を依存に戻さない**(Basic認証ヘッダとキーを logcat に流す経路のうち、
  コードから最も容易に開くものがこれ。「唯一」ではない —— デバッグビルドでは Android Studio の
  Network Inspector がコードと無関係に応答全文を見られるし、将来 `Log.d` 等が足されれば経路は開く)。
  エラー経路に応答ボディ・例外メッセージを載せない。検出器は `ProviderSecretTest`、
  根拠は `docs/API_CONTRACT.md`「`Provider.key` は APIキーそのもの」。
- **`opencode serve` は Tailscale 等の信頼できるプライベートネットワーク内でのみ動かす前提**で設計する
  (**`OPENCODE_SERVER_PASSWORD` を3スコープとも未設定のまま起動した serve** は認証を一切要求せず、
  `GET /global/health` も `GET /provider` も無認証で 200 を返す —— P5 で実測、2026-08-30 に再実測。
  PTY の任意コマンド実行と上記キーが同時に晒される)。公開ネットワーク前提の変更は別途設計合意を取る。

  **この「200」は起動条件の話であって、serve 一般の話ではない。** 同じ 1.18.21 でも
  `OPENCODE_SERVER_PASSWORD` を設定して起動した serve は、無認証・誤パスワードのいずれも
  `401` + `www-authenticate: Basic realm="Secure Area"` を返す。`scripts/serve.ps1` は
  パスワードが無ければ起動を拒否する(exit 2)ので、**このスクリプト経由で起こした serve は必ず後者**である。
  「serve.ps1 で起こしたら 401 だった」は挙動の変化ではなく、AGENTS.md の記述と条件が違うだけ。
  実測は `docs/API_CONTRACT.md`「認証の挙動は起動条件で決まる」節。
- **ツールチェーンを勝手に上げない**(Gradle 8.14.4 / AGP 8.13.2 / Kotlin 2.4.10 / compileSdk 36)。
  バージョン上げは独立フェーズとして、ビルドゲートを通してから。
- `usesCleartextTraffic=true` はTailscale内平文HTTPのための現行方針。外部証明書を要求する変更は
  設計判断として別途合意を取る。

## コマンド

```
gradlew.bat :app:assembleDebug       # ビルド(完了条件の最低ライン)
gradlew.bat :app:testDebugUnitTest   # ユニットテスト
adb (フルパス: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe)
```

## 完了条件

「ビルド成功」でなく「**実機/エミュレータで自分の変更を動かした**」までを実装側の完了とする。
報告の「確認済み」欄には**測定手段と測定対象**を書く(何をどう測ったか)。

## 開発体制

`HARNESS.md` に従う。フェーズ状態・証跡は全てファイルに置く
(`docs/TEST_REPORT.md`、`e2e-artifacts/`)。セッションが途切れてもファイルから再開できること。
