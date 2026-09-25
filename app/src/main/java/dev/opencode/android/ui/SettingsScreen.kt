package dev.opencode.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.BuildConfig
import dev.opencode.android.data.AppPreferences
import dev.opencode.android.data.ColorMode

/**
 * 設定画面(§5 Q5 スコープ2 / 参照UI §2.3)。
 *
 * P1 の `ConnectionScreen`(URL+パスワード+health)を**「接続」セクションとして取り込み**、
 * 表示(カラーモード)・操作(触覚フィードバック)・情報(アプリ/サーバーのバージョン)を足した。
 *
 * ## 回転耐性
 *
 * 入力中の URL/パスワードは `rememberSaveable`(P1 から)。画面位置そのものは `AppRoot` の
 * `screenKey` が `rememberSaveable` で持つ(P1/P2 レビュー指摘2 の回帰)。**どちらも壊さないこと。**
 *
 * [onOpenDrawer] が null なら navigationIcon を出さない(未設定の初回起動時 ——
 * ドロワーから行ける先が何も無い状態でハンバーガーを出しても押せる意味が無い)。
 *
 * [drawerOpen] が true の間、この画面の BACK 導線は黙る([screenBackEnabled])。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBackToSessions: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
    drawerOpen: Boolean = false,
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()

    // **まだ取れていなければ引く。** 判定は ServerInfoController が持つので
    // ここに条件を書かない(Q1/Q2 のレビューが変異で素通しを示した場所そのもの)。
    LaunchedEffect(Unit) { viewModel.ensureServerInfoLoaded() }

    // DataStore初回読み込み後に一度だけprefillする(null=未初期化)。
    var url by rememberSaveable { mutableStateOf<String?>(null) }
    var password by rememberSaveable { mutableStateOf<String?>(null) }
    // **`remember` であること(`rememberSaveable` ではない)。** プロセス再起動やコンフィグ変更を
    // またいで「表示」状態が復元されると、画面に平文のパスワードが出たまま戻ってくる
    // (レビュー minor-4)。表示切替は一時的な操作なので、復元しないのが正しい。
    var passwordVisible by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        val s = saved ?: return@LaunchedEffect
        if (url == null) url = s.baseUrl
        if (password == null) password = s.password
    }

    val canGoBack = saved?.isConfigured == true
    // **ドロワーが開いている間は黙る**(E2E 所見B)。開いているシートを BACK で閉じるのが
    // Android の慣習で、そのときこの導線が横取りすると「ドロワーが閉じずに一覧へ飛ぶ」。
    // 判定は `screenBackEnabled` の1か所にあり、AppRoot 側の `drawerBackEnabled` と排他。
    BackHandler(enabled = screenBackEnabled(canGoBack, drawerOpen), onBack = onBackToSessions)

    val testing = health is HealthUiState.Testing
    val canSubmit = !testing && !url.isNullOrBlank()
    val current = prefs ?: AppPreferences()

    Scaffold(
        topBar = {
            TopAppBar(
                // §5 Q5 スコープ3: ハンバーガー+タイトル構成に統一する。
                title = { Text("設定") },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.semantics { contentDescription = "メニューを開く" },
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ================= 接続 =================
            SectionHeader("接続")

            Text(
                "opencode serve への接続先を設定します。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = url.orEmpty(),
                onValueChange = { url = it },
                label = { Text("サーバーURL") },
                placeholder = { Text("http://10.0.2.2:4097") },
                // H1a: ポートを書かなければ 4097 を補う(Urls.normalize)。
                // 「書かなくても動く」ことを画面から見えるようにしておく。
                supportingText = { Text("ポートを省略すると :4097 を補います(https は 443 のまま)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("settings-url-field"),
                enabled = !testing,
            )

            OutlinedTextField(
                value = password.orEmpty(),
                onValueChange = { password = it },
                label = { Text("パスワード(Basic認証)") },
                singleLine = true,
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                // 中身は [PasswordVisibilityToggle] に出してある(検出器を Compose 層に置くため。
                // `TailscaleSection` と同じ理由・同じやり方)。
                trailingIcon = {
                    PasswordVisibilityToggle(
                        visible = passwordVisible,
                        onToggle = { passwordVisible = !passwordVisible },
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("settings-password-field"),
                enabled = !testing,
            )

            Button(
                onClick = { viewModel.saveAndTestHealth(url.orEmpty(), password.orEmpty()) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存して接続テスト")
            }

            // Q11: Tailscale 起動ボタン。**中身は [TailscaleSection] に出してある**
            // (レビュー major-2/minor-5/minor-8 の検出器を Compose 層に置くため)。
            val context = LocalContext.current
            TailscaleSection(onLaunch = { launchTailscale(context) })

            when (val h = health) {
                HealthUiState.Idle -> {
                    if (saved?.isConfigured == true) {
                        Text(
                            "保存済みの設定があります。必要なら修正して再テストしてください。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HealthUiState.Testing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("接続テスト中…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                is HealthUiState.Ok -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("✅ 接続OK", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "healthy=${h.healthy}  version=${h.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .semantics { contentDescription = "settings-health-ok:${h.healthy}/${h.version}" },
                            )
                            Button(
                                onClick = onBackToSessions,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text("セッション一覧へ")
                            }
                        }
                    }
                }
                is HealthUiState.Failed -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("❌ 接続できません", style = MaterialTheme.typography.titleMedium)
                            Text(
                                h.message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ================= 表示 =================
            SectionHeader("表示")

            // 現在値をラベルの横に出す(§2.3「アイコン+ラベル+現在値(グレーの小文字)」)。
            SettingRowHeader(
                label = "カラーモード",
                value = colorModeLabel(current.colorMode),
                description = "settings-color-mode:${current.colorMode.name}",
            )
            // 中身は [ColorModeOptions] に出してある(同上)。
            ColorModeOptions(
                current = current.colorMode,
                onSelect = { viewModel.setColorMode(it) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ================= 操作 =================
            SectionHeader("操作")

            HapticsSettingRow(
                enabled = current.hapticsEnabled,
                onChange = viewModel::setHapticsEnabled,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ================= 情報 =================
            SectionHeader("情報")

            SettingRowHeader(
                label = "アプリバージョン",
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                description = "settings-app-version:${BuildConfig.VERSION_NAME}",
            )
            SettingRowHeader(
                label = "サーバーバージョン",
                // 状態は「取れたか」だけを持ち、文言は**ドロワーと共有の関数**が決める
                // (Q5 レビュー minor-2: 2か所に書いたら片方だけ嘘をついた)。
                value = serverVersionLabel(serverInfo),
                description = "settings-server-version:" + (serverInfo.version ?: "none"),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 「ラベル+現在値(グレーの小文字)」の行(§2.3)。 */
@Composable
private fun SettingRowHeader(label: String, value: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 触覚フィードバックの行。**`internal` にしてあるのは Compose テストから直接叩くため**。
 *
 * ## Q6 レビュー2周目の走査で見つけた欠陥
 *
 * **押せるのはスイッチなのに、`content-desc` は囲みの `Row` に付いていた。**
 * 実機の dump ではスイッチ本体が `143x96px = 52 x 34.9dp` の**無名ノード**として現れ、
 * a11y 走査の `unlabeled` に引っかかった —— Q3 の「測られる物と押される物を一致させる」に
 * 反する形が、Q6 が同じ規則で一覧とチャットを直した後も設定画面に残っていた。
 *
 * 文字列は Q5 の証跡と同じ `settings-haptics:on|off` のままにして、**載せる場所だけを移す**
 * (証跡の連続性は、後から「同じものを測っているか」を確かめる唯一の手がかりである)。
 */
@Composable
internal fun HapticsSettingRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("触覚フィードバック", style = MaterialTheme.typography.bodyLarge)
            Text(
                "送信・permission応答・作成・中断のときに短く振動します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            modifier = Modifier
                .testTag("settings-haptics-switch")
                .heightIn(min = MIN_TOUCH_TARGET)
                .semantics {
                    contentDescription = "settings-haptics:" + if (enabled) "on" else "off"
                },
        )
    }
}

/**
 * パスワードの表示切替。**`content-desc` は子を持たないクリック面に載せる。**
 *
 * `IconButton { Text("表示") }` は「意味論を持つ子」を抱えるので、`IconButton` の
 * `modifier` に `contentDescription` を直付けすると、実機の dump では
 * clickable=false の fake node へ移る(機構の説明は [DescribedClickSurface])。
 * これは `open-tailscale` がゲートを3回落とした欠陥形と**同じもの**である。
 */
@Composable
internal fun PasswordVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
    DescribedClickSurface(
        description = if (visible) "hide-password" else "show-password",
        clickModifier = Modifier.clickable(role = Role.Button) { onToggle() },
        shape = CircleShape,
    ) {
        IconButton(
            onClick = onToggle,
            // 見た目専用。ラベルは description と二重に読み上げられるので消す。
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            Text(if (visible) "隠す" else "表示", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * カラーモードの選択肢。**`content-desc` は子を持たないクリック面に載せる**
 * (`RadioButton` と `Text` はどちらも意味論を持つ子である)。[DescribedClickSurface] 参照。
 *
 * 選択状態は**クリック面の `selectable` が持つ**ので、見た目の `Row` の意味論は消してある。
 */
@Composable
internal fun ColorModeOptions(current: ColorMode, onSelect: (ColorMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ColorMode.entries.forEach { mode ->
            val selected = current == mode
            DescribedClickSurface(
                description = "color-mode-option:${mode.name}:" +
                    if (selected) "selected" else "normal",
                clickModifier = Modifier.selectable(
                    selected = selected,
                    onClick = { onSelect(mode) },
                    role = Role.RadioButton,
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        colorModeLabel(mode),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 「Tailscaleを開く」のボタンと説明文(Q11 レビュー minor-5 / minor-8)。
 *
 * **`SettingsScreen` から切り出してあるのは検出器を置くためである。** 画面本体は
 * `AppViewModel` → `AppContainer` → DataStore/OkHttp を要求するので Robolectric に載せると
 * 測っているものが増えすぎる(Q6 のレビューで同じ判断をしている)。ここだけ切り出せば
 * **本物のコントロールを描いて押す**ことができる。
 *
 * @param onLaunch 押されたときに実行し、**開けたら true**。本番は [launchTailscale]。
 */
@Composable
fun TailscaleSection(onLaunch: () -> Boolean) {
    // **`remember`。** 失敗表示はその場限りの結果で、復元して残すものではない。
    var failed by remember { mutableStateOf(false) }
    // **`content-desc` は「意味論的な子を持たないクリックノード」に載せる。**
    //
    // 実機の `uiautomator dump` は Compose の意味論ツリーそのものではなく
    // `AccessibilityNodeInfo` ツリーである。Compose UI (1.7 系) は
    // **「descendants を merge するノード」が「意味論を持つ子」を1つでも抱えていると、
    // その `contentDescription` を子側の "fake node" に切り出す**。
    // `Modifier.clickable` は `shouldMergeDescendantSemantics = true` なので、
    // `OutlinedButton { Text(...) }` のようにラベルを内側に持つ形では、
    // `content-desc` は必ず **clickable=false の子ノード**へ落ちる。
    //
    // 経緯(すべて実機 dump で失敗を確認済み):
    //   1度目 子 `Text` に直付け / 2度目 `semantics(mergeDescendants = true)` /
    //   3度目 `OutlinedButton` の `modifier` に直付け。
    // 4度目に4案を同時に実機へ載せて計測した結果:
    //   Row+clickable+semantics                     -> clickable=false
    //   Row+clickable+semantics, 子 Text を clearAndSetSemantics -> clickable=false
    //   OutlinedButton+semantics, 子 Text を clearAndSetSemantics -> clickable=false
    //   見た目の Button の意味論を消し、**子を持たないクリック面を重ねる** -> clickable=true
    // (`clearAndSetSemantics` された子も「意味論を持つ子」として数えられるため、
    //  子を持たせない以外に手が無い。効いている `メニューを開く` の `IconButton` も、
    //  中身が `Icon(contentDescription = null)` で**意味論を持つ子が無い**から効いている。)
    //
    // よってここは「見た目の `OutlinedButton`(意味論なし)」+「クリック面(子なし)」の
    // 2枚重ねにする。押下の実体はクリック面側にあり、見た目のボタンは描画専用。
    // 機構そのものは [DescribedClickSurface] に出してある(同じ欠陥形が他に3箇所あったため)。
    DescribedClickSurface(
        description = "open-tailscale",
        clickModifier = Modifier.clickable(role = Role.Button) { failed = !onLaunch() },
        modifier = Modifier.fillMaxWidth(),
        shape = ButtonDefaults.outlinedShape,
    ) {
        OutlinedButton(
            onClick = { failed = !onLaunch() },
            // 見た目専用。意味論を消して**クリック面と二重に読み上げられないように**する。
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        ) {
            Text("Tailscaleを開く")
        }
    }
    Text(
        if (failed) {
            "Tailscaleもストアも開けませんでした(この端末にストアアプリもブラウザも無い可能性があります)。"
        } else {
            "Tailscaleが未インストールの場合はストアを開きます。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        // **黙って失敗させない**(レビュー minor-8)。dump から読める形にしておく。
        modifier = Modifier.semantics {
            contentDescription = if (failed) "tailscale-launch:failed" else "tailscale-launch:idle"
        },
    )
}
