# Security Policy

## 前提

このアプリは `opencode serve` に接続するクライアントで、サーバー側は **任意コマンド実行(PTY)と
接続済みプロバイダの API キー**を扱います。**serve を Tailscale 等の信頼できるプライベート
ネットワーク内でのみ動かし、`OPENCODE_SERVER_PASSWORD` を必ず設定してください。**
公開ネットワークに晒す構成は想定していません。詳細は README の「セキュリティ」を参照。

## 脆弱性の報告

公開 Issue には書かないでください。GitHub の **Security → Report a vulnerability**
(Private vulnerability reporting)から報告をお願いします。

## リポジトリ側の対策

| 対策 | 仕組み |
|---|---|
| 秘密情報の混入防止 | `privacy-scan.yml`(独自スキャン + gitleaks 全履歴)。GitHub の Secret scanning / Push protection も有効化する |
| 個人環境情報の混入防止 | `scripts/privacy_scan.py` がホームパス・メール・プライベート IP・tailnet アドレス・証跡ファイルを検出 |
| 静的解析 | `codeql.yml`(java-kotlin, security-and-quality) |
| 依存の脆弱性 | `dependency-review.yml`(PR)、Dependabot alerts(`ci.yml` の dependency submission) |
| アプリ側 | `ProviderSecretTest` がサーバーから返る API キーを DTO に取り込まないことを検証。logging-interceptor は依存に含めない |
