# Third-party notices

本リポジトリ自体は MIT ライセンス(`LICENSE`)。以下は同梱・依存する第三者成果物とそのライセンス。

## opencode (MIT)

- `docs/spec/opencode-1.18.21-openapi.json` は opencode 1.18.21 の `opencode serve` が
  `GET /doc` で返す OpenAPI 仕様をそのまま保存したもの。
- `docs/API_CONTRACT.md` の JSON 例は上記仕様および実サーバー応答から抜粋している。
- opencode は https://github.com/anomalyco/opencode で MIT ライセンスのもと公開されている。
  「opencode」はそのプロジェクトの名称であり、本リポジトリはそのプロジェクトと無関係の非公式クライアントである。

```
MIT License

Copyright (c) 2025 opencode

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## ビルド時に取得する依存ライブラリ(ソースは同梱しない)

| ライブラリ | ライセンス |
|---|---|
| AndroidX (Compose, Activity, Core, Lifecycle, DataStore) | Apache-2.0 |
| OkHttp / okhttp-sse (Square) | Apache-2.0 |
| kotlinx.serialization / kotlinx.coroutines (JetBrains) | Apache-2.0 |
| multiplatform-markdown-renderer (Mike Penz) | Apache-2.0(一部 MIT) |
| intellij-markdown (JetBrains) | Apache-2.0 |
| JUnit 4(テストのみ) | EPL-1.0 |
| Robolectric(テストのみ) | MIT |
| Gradle wrapper | Apache-2.0 |

## 商標

Tailscale は Tailscale Inc. の商標。本アプリは Tailscale アプリの起動・導入案内のために名称と
パッケージ ID を参照するのみで、Tailscale Inc. とは無関係。
