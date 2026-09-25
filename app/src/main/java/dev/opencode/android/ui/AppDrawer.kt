package dev.opencode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.data.SessionDto

/** ドロワーの「最近の項目」に出す件数(§5 Q5 スコープ1「一覧上位N件」)。 */
const val DRAWER_RECENT_COUNT = 5

/** ドロワーが指せる行き先。選択中の項目を**角丸ピルで強調**する(§2.2)。 */
enum class DrawerDestination { SESSIONS, SETTINGS }

/**
 * モーダルドロワーの中身(§5 Q5 スコープ1 / §2.2 の参照UI)。
 *
 * ## 「最近の項目」は一覧の状態をそのまま使う。**取り直さない。**
 *
 * ここで `refreshSessions()` を呼ぶと `limit` が1ページ目へ戻り、120件を末尾まで開いていた
 * ユーザーが48件目付近へ飛ぶ(Q1レビュー major-1。`ensureSessionsLoaded` と `refreshSessions` を
 * 分けた理由がこれ)。**この Composable は通信を1つも起こさない** —— 渡された [sessions] を
 * 上位N件だけ描く。一覧をまだ一度も開いていなければ空で、その旨を出す。
 *
 * @param sessions 一覧が既に持っている行(**表示順のまま**。ここで並べ替えない)
 * @param hostLabel 接続先の表示名。null なら未設定
 * @param serverInfo health 由来のサーバー素性。**取得中と取得失敗を区別して出す**
 *   (Q5 レビュー minor-2: 1周目は失敗しても「取得中…」と言い続けた)
 */
@Composable
fun AppDrawerContent(
    hostLabel: String?,
    serverInfo: ServerInfoUi,
    selected: DrawerDestination,
    sessions: List<SessionDto>,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Q8 スコープ1: ファイルブラウザを開く。 */
    onOpenFiles: () -> Unit,
    /** Q9 スコープ4: ターミナル(PTY)を開く。 */
    onOpenTerminal: () -> Unit,
    onOpenSession: (String) -> Unit,
    /**
     * Q7 スコープ5: 作業ツリーの変更ファイル(`GET /vcs/status`)。
     * **この Composable は通信を1つも起こさない**(上の doc)ので、
     * 引き直しは呼び出し側が [AppViewModel.refreshWorkingTree] で行う。
     */
    workingTree: WorkingTreeUi,
    /** 変更中のファイルをタップした。差分ビューアを開く。 */
    onOpenWorkingTreeFile: (String) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.testTag("app-drawer")) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            // ---- ヘッダー: 接続先ホスト名 + バージョン(§5 Q5 スコープ1) ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .semantics {
                        // 実機 dump から**状態ごと**引用できるようにする。
                        // 「取れた版」と「取れなかった」を content-desc で区別する。
                        contentDescription = "drawer-header:" + (hostLabel ?: "未設定") +
                            "/" + (serverInfo.version ?: "none") +
                            "/" + serverVersionLabel(serverInfo)
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("opencode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    hostLabel ?: "接続先が未設定です",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 文言は設定画面と**同じ関数**が決める(AppearanceModels.serverVersionLabel)。
                    drawerServerLine(serverInfo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            NavigationDrawerItem(
                label = { Text("新規セッション") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                selected = false,
                onClick = onNewSession,
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .semantics { contentDescription = "drawer-new-session" },
            )
            NavigationDrawerItem(
                label = { Text("セッション一覧") },
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                // 選択中は角丸ピルで強調(§2.2)。NavigationDrawerItem の selected がそれを描く。
                selected = selected == DrawerDestination.SESSIONS,
                onClick = onOpenSessions,
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .semantics {
                        contentDescription =
                            "drawer-sessions:" + if (selected == DrawerDestination.SESSIONS) "selected" else "normal"
                    },
            )
            NavigationDrawerItem(
                label = { Text("ファイル") },
                icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                // Q8 スコープ1・3〜5: ツリー閲覧と3種類の検索が同じ画面に居るので
                // ラベルは「ファイル」1つにした。**選択状態を持たない** ——
                // [DrawerDestination] は一覧/設定の2値で、増やすと Q5 の
                // 「どの面に居るか」の判定が3か所に散る。
                selected = false,
                onClick = onOpenFiles,
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .semantics { contentDescription = "drawer-files" },
            )
            NavigationDrawerItem(
                label = { Text("ターミナル") },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                // Q9: ファイルと同じく**選択状態を持たない**(上のコメントと同じ理由)。
                selected = false,
                onClick = onOpenTerminal,
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .semantics { contentDescription = "drawer-terminal" },
            )
            NavigationDrawerItem(
                label = { Text("接続設定") },
                icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                // 接続設定は**設定画面の先頭セクション**になった(§5 Q5 スコープ2)。
                // 行き先はフッターのギアと同じ画面で、違うのは「どこを見に行くか」の呼び方だけ。
                selected = selected == DrawerDestination.SETTINGS,
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .semantics {
                        contentDescription =
                            "drawer-connection:" + if (selected == DrawerDestination.SETTINGS) "selected" else "normal"
                    },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // ---- Q7 スコープ5: 変更中のファイル ----
            Text(
                workingTreeHeading(workingTree),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 28.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
                    .semantics { contentDescription = "drawer-worktree-heading:${workingTree.files.size}" },
            )
            // **空のときに何を出すかは [workingTreeEmptyState] が決める。**
            // 「まだ引いていない」と「引いたが0件」を画面側の `if` で分けない。
            workingTreeEmptyState(workingTree)?.let { spec ->
                Text(
                    spec.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 28.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)
                        .semantics { contentDescription = "empty-state:${spec.key}" },
                )
            }
            workingTree.files.forEach { file ->
                NavigationDrawerItem(
                    label = {
                        Text(file.file, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    badge = {
                        Text(
                            "+${file.additions} -${file.deletions}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    selected = false,
                    onClick = { onOpenWorkingTreeFile(file.file) },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .semantics {
                            contentDescription =
                                "drawer-worktree:${file.file}:${file.status ?: "unknown"}:" +
                                    "+${file.additions}-${file.deletions}"
                        },
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // ---- 最近の項目(§2.2)----
            Text(
                "最近の項目",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )
            // **上位N件を切るのは純関数**(空のときの文言もここで決まらない)。
            val recent = recentSessions(sessions)
            if (recent.isEmpty()) {
                Text(
                    "まだセッションがありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 28.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)
                        .semantics { contentDescription = "drawer-recent-empty" },
                )
            } else {
                recent.forEachIndexed { index, session ->
                    NavigationDrawerItem(
                        label = {
                            Text(session.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        selected = false,
                        onClick = { onOpenSession(session.id) },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .semantics { contentDescription = "drawer-recent-$index:${session.id}" },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- フッター: 設定(ギア)(§2.2)----
            NavigationDrawerItem(
                label = { Text("設定") },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                selected = selected == DrawerDestination.SETTINGS,
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .semantics { contentDescription = "drawer-settings" },
            )
        }
    }
}

/**
 * 「変更中のファイル」の見出し(Q7 スコープ5)。**件数と増減を1行に載せる。**
 *
 * 見出しに数字を入れるのは、折り畳まれていても「何か変わっている」ことが
 * ドロワーを開いた瞬間に読めるようにするため。0件のときに数字を出さないのは、
 * `+0 -0` が「変更なし」より弱い言い方になるからである。
 */
fun workingTreeHeading(ui: WorkingTreeUi): String =
    if (ui.files.isEmpty()) {
        "変更中のファイル"
    } else {
        "変更中のファイル ${ui.files.size}件 (+${ui.totalAdditions} -${ui.totalDeletions})"
    }
