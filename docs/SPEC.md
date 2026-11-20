# GitLab MR Pipelines — IntelliJ Platform plugin specification

Goal: inside any JetBrains IDE, show the pipeline status of the user's open GitLab merge requests
(self-hosted or gitlab.com) in a tool window, a status bar widget and balloon notifications —
the GitLab analogue of the TeamCity IDE integration.

Constraints: Kotlin, IntelliJ Platform Gradle Plugin 2.x, `sinceBuild=242` (2024.2), no
`untilBuild`, depends only on `com.intellij.modules.platform`. JSON via `com.google.gson`
(bundled with the platform). HTTP via `java.net.http.HttpClient`. No network traffic except
to the configured GitLab. UI strings Russian (resource bundle).

Base package: `com.github.gitlabmrpipelines` (below abbreviated `…`). Plugin id: same.

## 1. File tree

```
gitlab-mr-pipelines/
  settings.gradle.kts  build.gradle.kts  gradle.properties
  gradle/wrapper/…  gradlew  gradlew.bat
  README.md                                 # install from disk, PAT creation, usage
  src/main/kotlin/…/core/                   # PURE: no com.intellij.* imports
    Model.kt          # PipelineStatus, Category, Role, Pipeline, MergeRequest, User
    GitLabClient.kt   # GraphQL + REST fallback, uses HttpTransport
    HttpTransport.kt  # interface + HttpRequestSpec/HttpResponseSpec + JdkHttpTransport
    GitLabException.kt
    Json.kt           # Gson-based parsing helpers (GraphQL + REST → model)
    StatusDiff.kt     # diff previous vs current → events (pure)
    Summary.kt        # counts, sorting, filtering (pure)
    UrlUtil.kt        # normalizeBaseUrl, joinUrl, projectPathFromRest
  src/main/kotlin/…/plugin/                 # IntelliJ-dependent
    MyBundle.kt                       # DynamicBundle("messages.MyBundle")
    settings/GitLabSettings.kt        # PersistentStateComponent (app level)
    settings/TokenStore.kt            # PasswordSafe wrapper
    settings/GitLabConfigurable.kt    # Settings | Tools | GitLab MR Pipelines (UI DSL v2)
    service/PipelinesService.kt       # app service: polling loop, state, notifications
    service/PipelinesListener.kt      # app message bus Topic
    service/StartupActivity.kt        # ProjectActivity → service.ensureStarted()
    service/IdeTransport.kt           # JdkHttpTransport with IDE SSL context + proxy
    ui/StatusIcons.kt                 # PipelineStatus → Icon, Category → color
    ui/PipelinesToolWindowFactory.kt  ui/PipelinesPanel.kt  ui/MrTableModel.kt
    ui/actions/RefreshAction.kt  OpenMrAction.kt  OpenPipelineAction.kt  OpenSettingsAction.kt
    ui/PipelinesStatusBarWidgetFactory.kt  ui/PipelinesStatusBarWidget.kt
  src/main/resources/
    META-INF/plugin.xml  META-INF/pluginIcon.svg
    messages/MyBundle.properties      # Russian (default), UTF-8
    messages/MyBundle_en.properties   # optional English
    icons/gitlabPipelines.svg  (13x13 tool window icon)
  src/test/kotlin/…/core/
    StatusDiffTest.kt  SummaryTest.kt  UrlUtilTest.kt  JsonParsingTest.kt
    GitLabClientTest.kt               # fake HttpTransport
    GitLabClientHttpServerTest.kt     # com.sun.net.httpserver + JdkHttpTransport
  src/test/resources/fixtures/*.json  # graphql_page1.json, graphql_page2.json, rest_*.json
```

Rule: `core/**` imports only Kotlin stdlib, `java.*`, `com.google.gson.*`. Tests touch only core.

## 2. Build

`gradle.properties`:
```
pluginGroup=com.github.gitlabmrpipelines
pluginVersion=1.0.0
platformType=IC
platformVersion=2024.2.4
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
```
`build.gradle.kts` outline:
```kotlin
plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
  id("org.jetbrains.intellij.platform") version "2.2.1"   // any 2.x >= 2.1
}
group = "com.github.gitlabmrpipelines"; version = "1.0.0"
kotlin { jvmToolchain(21) }
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
dependencies {
  intellijPlatform {
    intellijIdeaCommunity("2024.2.4")
    pluginVerifier()
    zipSigner()
    instrumentationTools()        // omit if the 2.x version reports it deprecated
  }
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("com.google.code.gson:gson:2.10.1")  // platform jars may not be on test runtime
}
intellijPlatform {
  pluginConfiguration {
    id = "com.github.gitlabmrpipelines"; name = "GitLab MR Pipelines"; version = "1.0.0"
    ideaVersion { sinceBuild = "242"; untilBuild = provider { null } }
  }
  pluginVerification { ides { recommended() } }
}
tasks.test { useJUnitPlatform() }
```
Do not add kotlinx-coroutines as a dependency (platform provides it; compile against it only).

## 3. plugin.xml

```xml
<idea-plugin>
  <id>com.github.gitlabmrpipelines</id>
  <name>GitLab MR Pipelines</name>
  <vendor>gitlab-mr-pipelines</vendor>
  <description><![CDATA[Статусы пайплайнов ваших merge request'ов GitLab прямо в IDE:
    окно инструментов, виджет в строке состояния и уведомления.<br/>
    Shows pipeline statuses of your GitLab merge requests.]]></description>
  <depends>com.intellij.modules.platform</depends>
  <resource-bundle>messages.MyBundle</resource-bundle>
  <extensions defaultExtensionNs="com.intellij">
    <applicationService serviceImplementation="….plugin.settings.GitLabSettings"/>
    <applicationConfigurable parentId="tools" id="gitlab.mr.pipelines"
        instance="….plugin.settings.GitLabConfigurable"
        bundle="messages.MyBundle" key="settings.displayName"/>
    <toolWindow id="GitLab Pipelines" anchor="right" secondary="false"
        icon="/icons/gitlabPipelines.svg"
        factoryClass="….plugin.ui.PipelinesToolWindowFactory"/>
    <statusBarWidgetFactory id="GitLabPipelinesWidget" order="after positionWidget"
        implementation="….plugin.ui.PipelinesStatusBarWidgetFactory"/>
    <notificationGroup id="GitLab MR Pipelines" displayType="BALLOON"
        bundle="messages.MyBundle" key="notification.group"/>
    <postStartupActivity implementation="….plugin.service.StartupActivity"/>
  </extensions>
  <actions>
    <action id="GitLabPipelines.Refresh" class="….plugin.ui.actions.RefreshAction"
        icon="AllIcons.Actions.Refresh"/>
  </actions>
</idea-plugin>
```
`PipelinesService` is a light service (`@Service(Service.Level.APP)`), not registered in XML.
Action texts come from bundle keys `action.GitLabPipelines.Refresh.text`.
`toolWindow` title text: bundle key `toolwindow.stripe.GitLab_Pipelines` = «GitLab Pipelines».

## 4. Settings

`GitLabSettings` — `@State(name="GitLabMrPipelinesSettings", storages=[Storage("gitlabMrPipelines.xml")])`,
`SimplePersistentStateComponent<GitLabSettings.State>`:

| field | type | default | validation |
|---|---|---|---|
| baseUrl | String | "" | `http(s)://host[:port][/path]`, trailing `/` stripped, subpath kept |
| pollSeconds | Int | 60 | 30..3600 |
| notifyFailed | Boolean | true | |
| notifySuccess | Boolean | true | |
| notifyCanceled | Boolean | false | covers canceled, skipped, manual |
| includeAssigned | Boolean | false | MRs where I'm assignee |
| includeReview | Boolean | false | MRs where I'm requested reviewer |
| showDrafts | Boolean | true | false → drafts excluded from table, widget, notifications |

Token: never in the XML state. `TokenStore`:
`CredentialAttributes(generateServiceName("GitLab MR Pipelines", baseUrl))`,
`PasswordSafe.instance.getPassword(attrs)` / `setPassword(attrs, token)`. Read/write only on a
background thread (may block). Token is never logged, never in URLs, never in exception messages.

`GitLabConfigurable` (UI DSL v2 `panel {}`): rows — URL text field (hint «https://gitlab.com
или https://gitlab.company.ru»), token `JBPasswordField` + comment with link
`{base}/-/user_settings/personal_access_tokens` (scope `read_api`), poll interval spinner (sec),
three notify checkboxes, two include checkboxes, show drafts checkbox, button «Проверить
соединение» + result label. `isModified/apply/reset` standard; `apply()` validates
(throws `ConfigurationException` with bundle text), saves state + token, then
`PipelinesService.getInstance().settingsChanged()` (resets baseline, restarts loop, polls now).

Test connection: runs `GitLabClient(url, typedToken, transport).getUser()` on a pooled thread
(`ApplicationManager.getApplication().executeOnPooledThread`), updates label via
`invokeLater(…, ModalityState.any())`: OK → «Подключено как @{username} ({name})»; auth →
«Неверный токен (401)»; forbidden → «Недостаточно прав: нужен scope read_api (403)»; network →
«Не удалось подключиться: {message}». Uses the values currently typed (not yet applied).

## 5. Core model (`core/Model.kt`)

```kotlin
enum class Category(val order: Int) { FAILED(0), RUNNING(1), PENDING(2), SUCCESS(3), OTHER(4), NONE(5) }
enum class PipelineStatus(val category: Category, val terminal: Boolean) {
  CREATED(PENDING,false), WAITING_FOR_RESOURCE(PENDING,false), PREPARING(PENDING,false),
  WAITING_FOR_CALLBACK(PENDING,false), PENDING(PENDING,false), SCHEDULED(PENDING,false),
  RUNNING(RUNNING,false), CANCELING(RUNNING,false),
  SUCCESS(SUCCESS,true), FAILED(FAILED,true),
  CANCELED(OTHER,true), SKIPPED(OTHER,true), MANUAL(OTHER,true), UNKNOWN(OTHER,false);
  companion object { fun parse(s: String?): PipelineStatus }  // case-insensitive, else UNKNOWN
}
enum class Role { AUTHOR, ASSIGNEE, REVIEWER }
data class Pipeline(val id: String, val iid: String?, val status: PipelineStatus, val rawStatus: String,
  val label: String?, val webUrl: String?, val updatedAt: Instant?, val finishedAt: Instant?)
data class MergeRequest(val key: String, val iid: Int, val title: String, val webUrl: String,
  val draft: Boolean, val sourceBranch: String, val projectPath: String, val updatedAt: Instant?,
  val roles: Set<Role>, val pipeline: Pipeline?)
data class User(val id: Long, val username: String, val name: String)
```
`MergeRequest.category = pipeline?.status?.category ?: Category.NONE`.
Keys: GraphQL global ids as-is (`gid://gitlab/MergeRequest/123`, `gid://gitlab/Ci::Pipeline/456`);
REST builds the same strings from numeric ids so both paths are identical.

## 6. Status mapping (core enum + `ui/StatusIcons.kt` + bundle)

| GitLab status | category | terminal | icon (AllIcons) | bundle key → Russian |
|---|---|---|---|---|
| failed | FAILED | yes | RunConfigurations.TestFailed | status.failed = Упал |
| running | RUNNING | no | RunConfigurations.TestState.Run | status.running = Выполняется |
| canceling | RUNNING | no | RunConfigurations.TestState.Run | status.canceling = Отменяется |
| pending | PENDING | no | RunConfigurations.TestNotRan | status.pending = В очереди |
| created | PENDING | no | RunConfigurations.TestNotRan | status.created = Создан |
| waiting_for_resource | PENDING | no | RunConfigurations.TestNotRan | status.waiting_for_resource = Ждёт ресурс |
| waiting_for_callback | PENDING | no | RunConfigurations.TestNotRan | status.waiting_for_callback = Ждёт callback |
| preparing | PENDING | no | RunConfigurations.TestNotRan | status.preparing = Подготовка |
| scheduled | PENDING | no | RunConfigurations.TestNotRan | status.scheduled = Запланирован |
| success | SUCCESS | yes | RunConfigurations.TestPassed | status.success = Успешно |
| manual | OTHER | yes | Actions.Execute | status.manual = Ожидает ручного запуска |
| canceled | OTHER | yes | RunConfigurations.TestIgnored | status.canceled = Отменён |
| skipped | OTHER | yes | RunConfigurations.TestSkipped | status.skipped = Пропущен |
| (no pipeline) | NONE | — | General.Note (or EmptyIcon) | status.none = Нет пайплайна |
| unknown | OTHER | no | RunConfigurations.TestUnknown | shows rawStatus |

If any AllIcons constant above is missing in 242, use the nearest `RunConfigurations.Test*` icon.

## 7. HTTP transport & GitLab client

```kotlin
data class HttpRequestSpec(val method: String, val url: String, val headers: Map<String,String>, val body: String? = null)
data class HttpResponseSpec(val status: Int, val headers: Map<String,List<String>>, val body: String)
fun interface HttpTransport { fun send(req: HttpRequestSpec): HttpResponseSpec }   // IOException on network failure
class JdkHttpTransport(client: HttpClient = default, timeout: Duration = 20s) : HttpTransport
```
`IdeTransport` (plugin side) builds `HttpClient.newBuilder().sslContext(CertificateManager.getInstance().sslContext)
.proxy(ProxySelector.getDefault())` — honours IDE-trusted corporate CAs and IDE proxy settings.

`GitLabException(kind: ErrorKind, httpStatus: Int? = null, retryAfterSec: Long? = null, message)`,
`ErrorKind = AUTH(401 / currentUser null) | FORBIDDEN(403) | RATE_LIMIT(429) | NETWORK(IOException/timeout)
| SERVER(5xx) | GRAPHQL(errors[]) | BAD_RESPONSE(non-JSON / unexpected shape) | NOT_CONFIGURED`.

`class GitLabClient(baseUrl: String, token: String, transport: HttpTransport)`:
- `getUser(): User` — `GET {base}/api/v4/user`, header `PRIVATE-TOKEN`.
- `fetchMergeRequests(includeAssigned: Boolean, includeReview: Boolean): FetchResult(mrs, source: GRAPHQL|REST)`.
  GraphQL first; on `GRAPHQL`, `BAD_RESPONSE`, HTTP 400/404/5xx from `/api/graphql` → REST.
  `AUTH`, `FORBIDDEN`, `RATE_LIMIT`, `NETWORK` propagate (no fallback).

### 7.1 GraphQL
`POST {base}/api/graphql`, headers `Authorization: Bearer <token>`, `Content-Type: application/json`,
body `{"query": …, "variables": {"after": cursor|null}}`. One query per list field
(`authoredMergeRequests`, `assignedMergeRequests`, `reviewRequestedMergeRequests`):
```graphql
query($after: String) {
  currentUser {
    username
    authoredMergeRequests(state: opened, first: 100, after: $after) {
      pageInfo { hasNextPage endCursor }
      nodes {
        id iid title webUrl draft sourceBranch updatedAt
        project { fullPath }
        headPipeline {
          id iid status path updatedAt finishedAt
          detailedStatus { text label group icon detailsPath }
        }
      }
    }
  }
}
```
Verified against docs.gitlab.com GraphQL reference: `currentUser` (type `CurrentUser`; `UserCore`
on older versions) exposes `authoredMergeRequests`, `assignedMergeRequests`,
`reviewRequestedMergeRequests` with args `state`, `draft`, `sort`, `first`, `after`; `Pipeline` has
`id: ID!`, `iid: String!`, `status: PipelineStatusEnum!`, `detailedStatus: DetailedStatus!`,
`path: String` (relative path to pipeline page), `updatedAt: Time!`, `finishedAt: Time`;
`PipelineStatusEnum` = CANCELED, CANCELING, CREATED, FAILED, MANUAL, PENDING, PREPARING, RUNNING,
SCHEDULED, SKIPPED, SUCCESS, WAITING_FOR_CALLBACK, WAITING_FOR_RESOURCE.
Pagination: loop while `hasNextPage`, max 5 pages/list. `pipeline.webUrl = joinUrl(base, path)`
(GitLab under a subpath: `path` already contains it → join with origin only if `path` starts with
the base path; `joinUrl` handles this). Merge lists, dedupe by `key`, union `roles`.
`iid` of MergeRequest is a String in GraphQL → parse to Int.

### 7.2 REST fallback (header `PRIVATE-TOKEN`)
1. `GET /api/v4/user` (username for reviewer filter).
2. `GET /api/v4/merge_requests?scope=created_by_me&state=opened&per_page=100&page=N`;
   assigned: `scope=assigned_to_me`; review: `scope=all&reviewer_username={username}`.
   Follow `X-Next-Page` (stop when absent/empty), max 5 pages.
3. Per MR: `GET /api/v4/projects/{project_id}/merge_requests/{iid}` → `head_pipeline`
   (`id, iid, status, web_url, updated_at, finished_at, detailed_status.label`); at most 4 in
   parallel (fixed thread pool or sequential is acceptable; tests assert correctness, not timing).
4. `projectPath` = `references.full` minus trailing `!{iid}`; fallback parse `web_url` before
   `/-/merge_requests/`. `draft = draft ?: work_in_progress ?: false`.

Response handling (both paths): 401 → AUTH; 403 → FORBIDDEN; 429 → RATE_LIMIT with `Retry-After`
seconds (default 60); 5xx → SERVER; `IOException`/timeout → NETWORK. Timestamps parsed with
`Instant.parse` (null-safe).

## 8. Diff & summary (pure)

```kotlin
data class Seen(val pipelineId: String?, val status: PipelineStatus?)
data class NotifyPrefs(val failed: Boolean, val success: Boolean, val canceled: Boolean)
data class PipelineEvent(val mr: MergeRequest, val status: PipelineStatus, val url: String)
data class DiffResult(val next: Map<String, Seen>, val events: List<PipelineEvent>)
object StatusDiff { fun diff(prev: Map<String, Seen>?, current: List<MergeRequest>, prefs: NotifyPrefs, lastPollAt: Instant?): DiffResult }
```
Rules:
- `prev == null` → baseline: `events` empty.
- `next` contains only current MRs (merged/closed disappear silently).
- Event when current status S is terminal, enabled by prefs (FAILED→failed, SUCCESS→success,
  CANCELED/SKIPPED/MANUAL→canceled), and either
  (a) prev entry exists and (`pipelineId` changed or `status` changed), or
  (b) prev entry missing (new MR after baseline) and `finishedAt != null && finishedAt > lastPollAt`.
- Same pipelineId and same status → never an event (no repeats).
- `url = pipeline.webUrl ?: mr.webUrl`.

`object Summary`:
- `filter(mrs, showDrafts)`; `sort(mrs)` by `category.order`, then `(pipeline?.updatedAt ?: updatedAt)` desc.
- `compute(mrs) -> Counts(failed, running, pending, success, other, none, total)`
  (running = RUNNING category; pending separate).
- `widgetState(counts, error: ErrorKind?) -> WidgetState(kind, number)`:
  error AUTH/FORBIDDEN/NOT_CONFIGURED → ERROR; failed>0 → FAILED(failed);
  running+pending>0 → RUNNING(running+pending); success>0 → OK; else EMPTY.
  Transient errors with cached data keep counts-based state.

## 9. PipelinesService (app-level)

`@Service(Service.Level.APP) class PipelinesService(private val cs: CoroutineScope)`:
- State (`@Volatile`): `snapshot: Snapshot(mrs: List<MergeRequest>, counts, lastSuccess: Instant?,
  error: GitLabException?, loading: Boolean)`, `seen: Map<String,Seen>?` (null = baseline pending),
  `lastPollAt: Instant?`, `backoffUntil: Instant?`. State is in memory only: after IDE restart the
  first poll is a new baseline (no notifications), consistent with the TeamCity plugin.
- `ensureStarted()` (idempotent, from `StartupActivity`): `cs.launch(Dispatchers.IO) { loop }`.
  Loop: `pollOnce()`, then wait `pollSeconds` or until a refresh request arrives
  (`Channel<Unit>(CONFLATED)` + `select`/`withTimeoutOrNull`). Cancelled with the service scope.
- `refreshNow()` → send to channel (ignores backoff). `settingsChanged()` → `seen=null`, clear
  error/backoff, refreshNow.
- `pollOnce()`: not configured (empty URL or token) → error NOT_CONFIGURED; backoff active and not
  manual → skip; else fetch → `Summary.filter` → `StatusDiff.diff` → publish notifications →
  update snapshot, `seen`, `lastPollAt=now` → publish `PipelinesListener.TOPIC`.
  On error: keep previous `mrs`, set `error`; RATE_LIMIT → `backoffUntil = now + retryAfter`;
  `lastPollAt` not advanced; AUTH → stop notifications until settings change (still polls).
  Errors logged via `thisLogger().info(kind + status)` only, never headers or token.
- Topic: `interface PipelinesListener { fun snapshotChanged(s: Snapshot) }`,
  `Topic.create("GitLabPipelines", PipelinesListener::class.java)`, published on application bus
  (subscribers marshal to EDT with `invokeLater`).
- Notifications: `NotificationGroupManager.getInstance().getNotificationGroup("GitLab MR Pipelines")
  .createNotification(title, content, type)` with title `"{projectPath} !{iid}"`, content
  `"{escaped mr.title}<br>{status label}"`, type ERROR for FAILED, INFORMATION otherwise;
  `.addAction(NotificationAction.createSimpleExpiring(«Открыть пайплайн») { BrowserUtil.browse(url) })`,
  plus «Открыть MR». `.notify(project)` where project = last focused open project
  (`ProjectManager.getInstance().openProjects.firstOrNull()`), or `null`.

## 10. Tool window «GitLab Pipelines»

`PipelinesToolWindowFactory : ToolWindowFactory, DumbAware` creates `PipelinesPanel(project)`
(`SimpleToolWindowPanel(vertical=true)`), subscribes to topic with the content's disposable.
- Toolbar (`ActionManager.createActionToolbar("GitLabPipelines", group, true)`): Refresh
  (`AllIcons.Actions.Refresh`, disabled while loading), Open settings (`AllIcons.General.Settings`
  → `ShowSettingsUtil.getInstance().showSettingsDialog(project, GitLabConfigurable::class.java)`).
- Header label: «Обновлено в HH:mm» / «Обновление…» / «Ещё не обновлялось».
- Error banner (`EditorNotificationPanel`-style or `JBLabel` with error icon) above table showing
  bundle text for error kind and link «Открыть настройки» for AUTH/FORBIDDEN/NOT_CONFIGURED.
- `JBTable` with `MrTableModel` (rows pre-sorted by `Summary.sort`); columns: Статус (icon,
  tooltip = Russian label), Проект, MR (`!iid`), Название (plus «[Черновик]», «[Ревью]»/«[Назначен]»
  suffixes), Ветка, Обновлён (relative: «только что», «N мин назад», «N ч назад», «N дн назад»,
  else dd.MM.yyyy). Speed search on title (`TableSpeedSearch.installOn`).
- Empty text (`table.emptyText`): not configured → «Укажите адрес GitLab и токен» + clickable
  «Открыть настройки»; no MRs → «Нет открытых merge request'ов».
- Double-click / Enter → `BrowserUtil.browse(mr.webUrl)`. Context menu (`PopupHandler.installPopupMenu`):
  «Открыть MR в браузере», «Открыть пайплайн» (disabled when no pipeline), «Копировать ссылку на MR».
- Initial render from `PipelinesService.snapshot`; opening the tool window triggers `ensureStarted()`.

## 11. Status bar widget

`PipelinesStatusBarWidgetFactory : StatusBarWidgetFactory` (id `GitLabPipelinesWidget`,
display name bundle `widget.name` = «GitLab Pipelines», available always). Widget implements
`StatusBarWidget.IconPresentation` + text via `CustomStatusBarWidget` (a `JBLabel` with icon+text)
— implementation choice free. Content from `Summary.widgetState`:
FAILED(n) → TestFailed icon + "n"; RUNNING(n) → TestState.Run icon + "n"; OK → TestPassed, no
text; ERROR → `AllIcons.General.Error`; EMPTY → tool window icon, no text.
Tooltip: «Упало: F, выполняется: R, успешно: S, всего MR: N» or the error text.
Click → `ToolWindowManager.getInstance(project).getToolWindow("GitLab Pipelines")?.activate(null)`.
Updates on topic (EDT) via `statusBar.updateWidget(ID)`.

## 12. Russian strings (`messages/MyBundle.properties`, UTF-8)

```
settings.displayName=GitLab MR Pipelines
settings.url=Адрес GitLab
settings.url.hint=https://gitlab.com или https://gitlab.company.ru
settings.token=Personal Access Token
settings.token.comment=Создайте токен со scope read_api: {0}
settings.poll=Интервал опроса (сек):
settings.notify.group=Уведомления
settings.notify.failed=Об упавших пайплайнах
settings.notify.success=Об успешных пайплайнах
settings.notify.canceled=Об отменённых, пропущенных и ручных
settings.include.assigned=Показывать MR, где я исполнитель
settings.include.review=Показывать MR, где я ревьюер
settings.showDrafts=Показывать черновики
settings.test=Проверить соединение
settings.test.running=Проверка…
settings.test.ok=Подключено как @{0} ({1})
settings.error.url=Введите корректный адрес (http:// или https://)
settings.error.token=Укажите токен
settings.error.poll=Интервал — от 30 до 3600 секунд
toolwindow.stripe.GitLab_Pipelines=GitLab Pipelines
toolwindow.updated=Обновлено в {0}
toolwindow.updating=Обновление…
toolwindow.neverUpdated=Ещё не обновлялось
toolwindow.empty.noMrs=Нет открытых merge request'ов
toolwindow.empty.notConfigured=Укажите адрес GitLab и токен
toolwindow.openSettings=Открыть настройки
column.status=Статус
column.project=Проект
column.mr=MR
column.title=Название
column.branch=Ветка
column.updated=Обновлён
tag.draft=Черновик
tag.reviewer=Ревью
tag.assignee=Назначен
time.justNow=только что
time.minutes={0} мин назад
time.hours={0} ч назад
time.days={0} дн назад
action.GitLabPipelines.Refresh.text=Обновить
action.GitLabPipelines.Refresh.description=Обновить статусы пайплайнов
action.openMr=Открыть MR в браузере
action.openPipeline=Открыть пайплайн
action.copyMrLink=Копировать ссылку на MR
action.settings=Настройки
notification.group=GitLab MR Pipelines
notification.openPipeline=Открыть пайплайн
notification.openMr=Открыть MR
widget.name=GitLab Pipelines
widget.tooltip=Упало: {0}, выполняется: {1}, успешно: {2}, всего MR: {3}
error.NOT_CONFIGURED=Плагин не настроен
error.AUTH=Неверный или просроченный токен (401)
error.FORBIDDEN=Недостаточно прав: нужен scope read_api (403)
error.RATE_LIMIT=Превышен лимит запросов GitLab, повтор через {0} с
error.NETWORK=Не удалось подключиться к GitLab: {0}
error.SERVER=Ошибка сервера GitLab ({0})
error.GRAPHQL=Ошибка GraphQL API GitLab
error.BAD_RESPONSE=Некорректный ответ GitLab
```
Plus `status.*` keys from §6. `MyBundle_en.properties` (optional) mirrors keys in English.

## 13. Acceptance criteria

AC1 Plugin builds with `./gradlew buildPlugin`; installs in IDEA 2024.2+ and any IDE with only the platform module.
AC2 Settings page at Settings | Tools | GitLab MR Pipelines; token persisted in PasswordSafe and absent from `gitlabMrPipelines.xml`.
AC3 «Проверить соединение» shows «Подключено как @user (Name)» for a valid token; 401 → error.AUTH text.
AC4 After apply, within one poll the tool window lists all open authored MRs (status icon, project, !iid, title, branch, updated).
AC5 Row order: failed → running → pending → success → other → no pipeline; within a group most recently updated first.
AC6 Double-click opens MR URL; «Открыть пайплайн» opens `base + path`; disabled when no pipeline.
AC7 Status bar: 2 failed → failed icon + "2"; 0 failed, 3 running/pending → running icon + "3"; all success → passed icon; click activates tool window.
AC8 First poll after IDE start or settings change produces no notifications.
AC9 running→failed between polls → exactly one ERROR balloon titled `{project} !{iid}` with MR title and «Упал», action «Открыть пайплайн»; unchanged later polls → none.
AC10 notify toggles suppress respective notifications; showDrafts=false hides drafts everywhere.
AC11 includeAssigned / includeReview add those MRs, deduplicated, with role tag.
AC12 GraphQL `errors[]` → REST fallback yields equal `List<MergeRequest>` (same keys, statuses, URLs).
AC13 401 → error banner + widget error icon; 429 → no requests until `Retry-After` elapses unless «Обновить».
AC14 Token never appears in logs, URLs, exception messages or persisted XML.
AC15 Only the configured GitLab host is contacted.
AC16 Self-hosted with IDE-trusted custom CA works (IdeTransport uses CertificateManager SSL context).

## 14. Test plan

### 14.1 Unit tests (JUnit 5, `./gradlew test`, no IDE needed — core only)
- `UrlUtilTest`: normalizeBaseUrl (trailing slash, subpath, port, invalid scheme → null), joinUrl with subpath, projectPathFromRest.
- `ModelTest`/`SummaryTest`: `PipelineStatus.parse` for all 13 values (upper/lower case) + unknown + null; categories/terminal flags per §6; sort; compute; widgetState for each rule; draft filter.
- `StatusDiffTest`: baseline → no events; running→failed; failed→failed same id → none; success→new pipeline failed → event; same status new id → event; new MR after baseline with finishedAt after/before lastPollAt; prefs toggles; removed MR dropped from `next`; MR without pipeline.
- `JsonParsingTest`: fixtures → model (GraphQL nodes, REST list + detail), null headPipeline, missing optional fields.
- `GitLabClientTest` (fake `HttpTransport` recording requests): Bearer header for GraphQL and PRIVATE-TOKEN for REST, token never in URL; GraphQL pagination over 2 pages; authored+assigned dedupe with roles; `currentUser:null` → AUTH; 401/403/429 (Retry-After)/500/IOException mapping; `errors[]` → REST with `X-Next-Page` pagination and detail calls; GraphQL vs REST results equal for the same fixture data.
- `GitLabClientHttpServerTest`: `com.sun.net.httpserver.HttpServer` on 127.0.0.1:0 implementing `/api/v4/user`, `/api/graphql`, `/api/v4/merge_requests`, `/api/v4/projects/{id}/merge_requests/{iid}`; real `JdkHttpTransport`; scenarios: happy GraphQL, GraphQL error → REST, 401, 429, connection refused (stopped server) → NETWORK.

### 14.2 Build & verification
- `./gradlew test buildPlugin` → `build/distributions/gitlab-mr-pipelines-1.0.0.zip`.
- `./gradlew verifyPluginProjectConfiguration verifyPlugin` (needs network to download IDEs; if
  blocked, report as skipped). If JetBrains repositories are unreachable but Maven Central is,
  core + tests must still compile in isolation (core has no IntelliJ imports): tester may run them
  via a temporary Gradle build without the IntelliJ plugin.
- Manual smoke (if an IDE is available): `./gradlew runIde` against the HttpServer mock or gitlab.com.
