# OpenCode Client (unofficial) for Android

> **非公式クライアントです。** 本プロジェクトは [opencode](https://opencode.ai) プロジェクト
> および Anomaly とは無関係で、承認・提携・保証を受けていません。「opencode」の名称は
> 接続先ソフトウェアを指すためにのみ用いています。
>
> **This is an unofficial client.** It is not affiliated with, endorsed by, or supported by the
> opencode project. The name "opencode" is used only to refer to the server software it connects to.

[opencode](https://opencode.ai) (`opencode serve`) をAndroid端末から操作するクライアント。
Tailscale 等のプライベートネットワーク経由で自宅PCの opencode サーバーに接続する。

- ライセンス: MIT(`LICENSE`)。同梱する第三者成果物は `THIRD_PARTY_NOTICES.md` を参照
- アプリID: `io.github.noxitro.opencodeclient`(opencode プロジェクトのドメインは名乗らない)

## 構成

```
[Android] --Tailscale--> [PC: opencode serve --hostname 0.0.0.0]
                          Basic認証 (OPENCODE_SERVER_PASSWORD)
```

- REST + SSE を OkHttp 直叩きで消費(公式SDKはJS系のみのため手書き)
- API契約は `docs/API_CONTRACT.md` にピン留め(opencode 1.18.21 の実機specから採取)

## 開発

```
gradlew.bat :app:assembleDebug        # ビルド
gradlew.bat :app:testDebugUnitTest    # ユニットテスト
```

- ツールチェーン: Gradle 8.14.4 / AGP 8.13.2 / Kotlin 2.4.10 / compileSdk 36 / minSdk 26
- `local.properties` に `sdk.dir` が必要(コミットされない)
- JDK 17 が PATH に無い場合は `gradle.properties` の `org.gradle.java.home` 行を有効にして各自の JDK を指す

## CI(GitHub Actions)

| ワークフロー | 内容 |
|---|---|
| `ci.yml` | gitleaks による秘密情報スキャン / debug APK ビルド / ユニットテスト(Robolectric)。APK とテストレポートを artifact に保存。`main` では依存グラフを送信して Dependabot alerts を有効化 |
| `e2e.yml` | エミュレータ(API 34)を起動し、`e2e-stub/server.mjs` に接続するスモーク(`scripts/e2e_smoke.py`)。設定入力→接続テスト→セッション一覧→クラッシュ無しを adb で検証し、dump/スクショ/logcat を artifact に保存 |
| `dependabot.yml` | Actions と Gradle 依存の週次更新。AGP / Kotlin はツールチェーン方針により対象外 |

## 開発体制

フェーズゲート方式(実装→レビュー→E2Eゲート→PASSのみ次フェーズ)。手順は `HARNESS.md`、
進行状況は `docs/TEST_REPORT.md` に記録する。workflowツールは使わず、オーケストレータが
サブエージェントを直接派遣する文書駆動方式。

## 配布(APK)

Play ストアでは配布していない。手元でビルドした APK を直接インストールする前提。

```
gradlew.bat assembleDebug assembleRelease
```

- release は `signingConfig = signingConfigs.getByName("debug")` で署名しているのでそのまま install できる。
  **ストア配布や第三者への配布に使う署名ではない**。配布するなら専用の鍵に切り替えること
- ビルドログはコミットしない(`.gitignore` 済み)。ローカル環境のパスや署名情報が混入し得る

## セットアップ(実機接続)

1. PC: `opencode serve --hostname 0.0.0.0 --port 4096` + `OPENCODE_SERVER_PASSWORD` 設定
2. 端末: Tailscale接続 → アプリ内で `http://<tailscale-ip>:4096` + パスワードを設定

## セキュリティ(このアプリを使う前に必ず読むこと)

### 前提: `opencode serve` を信頼できないネットワークに晒さないこと

`OPENCODE_SERVER_PASSWORD` を設定せずに `opencode serve` を起動すると、
**認証なしで 200 が返る**(P5 で実測済み)。その状態でネットワークに晒すと、
到達できる誰でも以下を取れる:

- **サーバー上での任意コマンド実行**(`POST /pty` は本物のシェルを起動する。下記)
- **接続済みプロバイダの APIキーそのもの**。spec の `Provider` は任意フィールド
  `key: string` を持ち、`GET /provider` も `GET /config/providers` も同じ `Provider` を返す。
  実物 serve 1.18.21 の `/config/providers` がこれを平文で返す、というのは
  **事前調査での申告に基づく**もので、リポジトリ内に採取証跡(生の応答ダンプ)は無い。
  spec の形からは平文で載りうるので、**載っている前提**で扱う

**したがって Tailscale 等のプライベートネットワーク内でのみ動かす前提**である。
`--hostname 0.0.0.0` は「Tailscale インターフェイスからも見える」ためのものであって、
公開ネットワークへ出す許可ではない。この前提を変えるなら、別途設計合意を取ること。

**アプリ側は受け取ったキーを保持・表示・ログしない**: `ProviderDto` は `key` / `options` / `env` を
宣言せず(`ignoreUnknownKeys` が捨てる)、OkHttp の logging-interceptor は依存にも入れず、
パース失敗・HTTP 異常のエラーにも応答ボディを載せない。検出器は `ProviderSecretTest`。
根拠と陽性コントロールは `docs/API_CONTRACT.md`「`Provider.key` は APIキーそのもの」。

### ターミナル(PTY)は任意コマンド実行である

**Q9 で足したターミナル(PTY)は、サーバー上で任意のコマンドを実行する。**
アプリはサーバーの `POST /pty` を叩いて本物のシェルを起動し、WebSocket で入力を送る。

- アプリはターミナル画面の先頭に同じ注意を常設で表示する(`pty-security-note`)。
- アプリは `POST /pty` に **`cwd` も `env` も送らない**(端末から任意のパスや
  環境変数を注入する導線を作らない)。起動できるのは `GET /pty/shells` が返した
  シェルだけである。

### ターミナルの制限(実装上の割り切り)

**完全な端末エミュレータではない。** ANSI は色(SGR)だけを解釈し、
カーソル移動・画面消去・代替画面は解釈せずに落とす。したがって:

- `vim` / `top` などの**全画面TUIは動かない**(画面に「代替画面へ切り替えようとした」
  という注記が出る)
- Windows の `cmd.exe` は起動直後から全画面制御を出すので、
  **プロンプト行の見た目は実物の端末と一致しない**

落とした制御シーケンスは**種別ごとに数えて画面に出す** ——
「サーバーが何も出していない」と「こちらが解釈しなかった」を混同させないため。
