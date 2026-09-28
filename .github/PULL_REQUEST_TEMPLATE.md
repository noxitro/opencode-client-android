## 変更内容

## 確認したこと

- [ ] `./gradlew :app:testDebugUnitTest` が通る
- [ ] 実機またはエミュレータで自分の変更を動かした(何をどう確認したか):

## 公開リポジトリのチェック

- [ ] `python3 scripts/privacy_scan.py` が clean(ホームパス・IP・メール・証跡ファイルを含めていない)
- [ ] `docs/API_CONTRACT.md` に無いフィールドを推測で足していない
- [ ] サーバーから返る秘密情報(`Provider.key` / `Model.options` / `Model.headers`)を DTO に足していない
