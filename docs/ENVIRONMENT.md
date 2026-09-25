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
