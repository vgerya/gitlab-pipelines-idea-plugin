# GitLab MR Pipelines

A plugin for IntelliJ-based IDEs that shows the pipeline status of your open GitLab merge requests
(gitlab.com or self-hosted) right in the IDE.

You push a branch, open an MR and go back to work — but did the pipeline fail? GitLab won't tell you
unless you keep a browser tab open and refresh it. The plugin watches for you, much like the TeamCity
plugin does: a tool window with your MRs and their pipeline status, a failed-pipeline counter in the
status bar, and a notification the moment a pipeline fails or passes.

The plugin UI is in Russian. This README gives the English name of each UI element with the actual
Russian label in parentheses on first mention.

## Contents

- [Compatibility](#compatibility)
- [Features](#features)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Tool window](#tool-window)
- [Settings](#settings)
- [GitLab integration](#gitlab-integration)
- [Git integration](#git-integration)
- [Notifications and status bar](#notifications-and-status-bar)
- [Troubleshooting / FAQ](#troubleshooting--faq)
- [Development](#development)

## Compatibility

| | |
|---|---|
| **IDE** | IntelliJ-based IDEs **2024.2 and newer** (the plugin depends only on `com.intellij.modules.platform`; no upper version bound). Checked with IntelliJ Plugin Verifier against IntelliJ IDEA Community 2024.2, WebStorm 2025.2 and IntelliJ IDEA 2025.3. |
| **GitLab** | gitlab.com and self-hosted GitLab, including non-standard ports and sub-path installs (`https://example.com/gitlab`). Uses the GraphQL API, falling back to REST API v4 when GraphQL is unavailable or returns an error. |
| **Git** | The bundled Git plugin is an optional dependency (see [Git integration](#git-integration)). |
| **Network** | Uses the IDE proxy settings and the certificates the IDE trusts (*Settings → Tools → Server Certificates*). |

## Features

- **GitLab Pipelines** tool window — a tree table of open MRs with pipeline status (icon and label),
  project, MR number, title, branch, role, last update time and pipeline number.
- MRs you authored; optionally also MRs assigned to you or awaiting your review. Drafts can be hidden.
- Sort by any column; choose, reorder and resize columns — all persisted across sessions.
- Group by project, by branch, or by project and branch.
- **Current project only** — show only MRs of the projects the open IDE project's git remotes point to.
- **Show all pipelines** — the last 10 pipelines of each MR as child rows.
- Status bar widget with a failed-pipeline counter, and notifications when pipelines finish.
- Poll interval of 30–3600 s (default 60 s) plus a Refresh button.
- The token is stored in the IDE Password Safe.

## Installation

The plugin is not published on JetBrains Marketplace; install it from a file.

1. On the [Releases](https://github.com/vgerya/gitlab-pipelines-idea-plugin/releases) page, under
   **Assets**, download **`gitlab-pipelines-idea-plugin-X.Y.Z.zip`**.

   > [!WARNING]
   > **Do not download "Source code (zip/tar.gz)"** — that is the source, not the plugin. The IDE will
   > fail with *"Fail to load plugin descriptor"*.

2. **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip (no need to unpack it).
3. Restart the IDE if asked. To update, install the new zip over the old one the same way.

Alternatives: the `plugin-distribution` artifact of a GitHub Actions build, or build it yourself
(see [Development](#development)).

## Quick start

1. Create a GitLab Personal Access Token with the **`read_api`** scope ([how](#personal-access-token)).
2. Open **Settings → Tools → GitLab MR Pipelines** and enter the GitLab URL and the token.
3. Click **Test connection** («Проверить соединение») — you should see
   «Подключено как @login (Name)» ("Connected as …").
4. Click **OK** and open the **GitLab Pipelines** tool window (right side bar, or
   *View → Tool Windows → GitLab Pipelines*).

## Tool window

Polling starts as soon as any project is opened, even if the tool window is closed. Above the table
you see the last update time («Обновлено в 14:05» / «Обновление…» / «Ещё не обновлялось» — "Updated at",
"Updating…", "Not updated yet") and any error; token errors and "not configured" also get an
**Open settings** («Открыть настройки») link.

### Toolbar

| Button | Action |
|---|---|
| **Refresh** («Обновить») | Poll GitLab now. |
| **Current project only** («Только текущий проект», toggle) | Filter by the git remotes of the current IDE project. Off by default; remembered per project. |
| **Grouping** («Группировка») | None («Нет») / By project («По проекту») / By branch («По ветке») / By project and branch («По проекту и ветке»). |
| **Show all pipelines** («Показывать все пайплайны», toggle) | Recent pipelines of each MR. Off by default. |
| **Columns** («Колонки») | Checkbox list of columns. The same menu opens on right-click in the table header. |
| **Settings** («Настройки») | Open *Settings → Tools → GitLab MR Pipelines*. |

### Columns

| Column | MR row | Pipeline row | Default |
|---|---|---|---|
| **Status** («Статус») | status icon + label | icon + label + `#iid` | always visible |
| **Project** («Проект») | project path | — | visible |
| **MR** | `!123` | — | visible |
| **Title** («Название») | title + tags `[Черновик]` (draft), `[Ревью]` (review), `[Назначен]` (assigned) | short commit SHA | visible |
| **Branch** («Ветка») | source branch | pipeline ref | visible |
| **Role** («Роль») | Author («Автор») / Assignee («Исполнитель») / Reviewer («Ревьюер») | — | hidden |
| **Updated** («Обновлён») | relative time («только что», «5 мин назад», «3 ч назад», «2 дн назад»), older than a week — `dd.mm.yyyy` | pipeline time | visible |
| **Pipeline** («Пайплайн») | head pipeline `#iid` | `#iid` | hidden |

- Status cannot be hidden — it holds the tree nodes (expand arrows).
- Drag column headers to reorder and drag their edges to resize; visibility, order and widths are saved.
- The review/assigned tags are not added to MRs you authored.

### Sorting

- Click a header to sort by that column, click again to reverse; the header shows ▲ / ▼.
- Default: by status — failed → running → pending → success → other → no pipeline, ties broken by most
  recently updated first.
- Empty values (no pipeline, no time) always go last, in either direction.
- The first click on Updated sorts newest first; other columns start ascending.
- The chosen sort is persisted across sessions.

### Grouping and the tree

- A group row shows the icon of the worst status in the group, the name (project / branch) in bold and
  the MR count.
- When sorting by status, groups are ordered by their worst status; otherwise by name.
- Groups are expanded by default, MR rows (with pipelines) collapsed. Expand/collapse state survives
  data refreshes (until the IDE is restarted).

### All pipelines

With **Show all pipelines** on, each MR gets child rows — up to 10 recent pipelines, newest first.
Additional GitLab requests are made only while the option is on (see [Which APIs are called](#which-apis-are-called)).
If loading them fails, the previously loaded pipelines stay and the MR list itself updates as usual.

### Row actions

- **Double-click / Enter**: MR row — open the MR in the browser; pipeline row — open the pipeline;
  group row — expand/collapse.
- **Context menu**: Open MR in browser («Открыть MR в браузере»), Open pipeline («Открыть пайплайн»;
  disabled when the MR has no pipeline), Copy MR link («Копировать ссылку на MR»). On a pipeline row the
  MR actions refer to that pipeline's MR.
- **Speed search** — just start typing while the table has focus.

### Status legend

| Label | GitLab statuses | Icon (AllIcons) |
|---|---|---|
| «Упал» (failed) | `failed` | `TestFailed` (red cross) |
| «Выполняется» / «Отменяется» | `running`, `canceling` | `TestState.Run` |
| «В очереди» / «Создан» / «Ждёт ресурс» / «Ждёт callback» / «Подготовка» / «Запланирован» | `pending`, `created`, `waiting_for_resource`, `waiting_for_callback`, `preparing`, `scheduled` | `TestNotRan` |
| «Успешно» (success) | `success` | `TestPassed` (green check) |
| «Ожидает ручного запуска» (manual) | `manual` | `Actions.Execute` |
| «Отменён» (canceled) | `canceled` | `TestIgnored` |
| «Пропущен» (skipped) | `skipped` | `TestSkipped` |
| «Нет пайплайна» (no pipeline) | — | none |
| raw GitLab status | unknown status | `TestUnknown` |

Status sort order: failed → running (`running`, `canceling`) → pending → success → other (`manual`,
`canceled`, `skipped`, unknown) → no pipeline.

## Settings

*Settings → Tools → GitLab MR Pipelines* (shared by all IDE projects):

| Setting | Default | Description |
|---|---|---|
| GitLab URL («Адрес GitLab») | empty | `http(s)://host[:port][/sub-path]`; a trailing `/` is dropped. |
| Personal Access Token | — | `read_api` scope. Stored in Password Safe. |
| Poll interval, s («Интервал опроса») | 60 | 30 to 3600. |
| Notifications: failed / successful pipelines | on / on | `failed` / `success`. |
| Notifications: canceled, skipped and manual | off | `canceled`, `skipped`, `manual`. |
| Show MRs where I'm assignee / reviewer («Показывать MR, где я исполнитель / ревьюер») | off / off | Add MRs assigned to you / awaiting your review. |
| Show drafts («Показывать черновики») | on | When off, draft MRs are hidden in the table, the widget and notifications. |

**Where the tool window state is stored:**

- application level, file `gitlabMrPipelinesView.xml`: visible columns, their order and widths, sort,
  grouping, Show all pipelines;
- per IDE project, in the workspace file (not shared via VCS): Current project only.

## GitLab integration

### Personal Access Token

1. In GitLab: **avatar → Edit profile → Access tokens**
   (`https://<your-gitlab>/-/user_settings/personal_access_tokens`; the plugin settings show a link built
   from the URL you entered). Older GitLab versions may use a different path.
2. **Add new token**: a name (e.g. `idea-pipelines`), an expiry date, and **only the `read_api` scope**.
   The `api` scope is not needed — the plugin never changes anything in GitLab.
3. Copy the token (it is shown once), paste it into the settings and click Test connection — the check
   uses the values you entered, even before they are applied.

The plugin sees exactly what your GitLab user sees. 401 means the token is invalid or expired; 403 means
it lacks permissions (usually `read_api` is not checked).

### Which APIs are called

All requests go only to the configured GitLab URL.

**GraphQL** (`POST {url}/api/graphql`, `Authorization: Bearer <token>`):

- `currentUser.authoredMergeRequests(state: opened)` — always;
  `assignedMergeRequests` / `reviewRequestedMergeRequests` — when enabled in settings.
  `headPipeline` for each MR. Pages of 100 MRs, at most 5 pages; an MR found in several lists is shown once.
- Only with Show all pipelines: `project(fullPath).mergeRequests(iids).pipelines(first: 10)` — one
  request per project and up to 20 MRs.

**REST API v4** (`PRIVATE-TOKEN`) — the fallback when GraphQL returns `errors[]`, a malformed response,
a 4xx (other than 401/403/429) or a 5xx:

| Request | Purpose |
|---|---|
| `GET /api/v4/user` | current user; also used by Test connection |
| `GET /api/v4/merge_requests?scope=created_by_me&state=opened&per_page=100` | my MRs |
| `GET /api/v4/merge_requests?scope=assigned_to_me&state=opened&per_page=100` | assigned to me |
| `GET /api/v4/merge_requests?scope=all&reviewer_username=<login>&state=opened&per_page=100` | awaiting my review |
| `GET /api/v4/projects/:id/merge_requests/:iid` | `head_pipeline` of each MR |
| `GET /api/v4/projects/:path/merge_requests/:iid/pipelines?per_page=10` | only with Show all pipelines |

Per-MR requests run up to 4 in parallel; pagination follows `X-Next-Page`, at most 5 pages.
401 / 403 / 429 and network errors do not trigger the REST fallback — they are shown right away.

### Load and rate limits

- One poll per poll interval for the whole IDE, however many projects are open.
- GraphQL: 1–3 requests per poll (plus the pipeline requests when Show all pipelines is on).
  REST: list pages plus one request per MR.
- On **429 Too Many Requests** polling pauses for `Retry-After` (default 60 s); Refresh still polls
  immediately. If your GitLab has strict limits, increase the poll interval.

### Security

- The token lives in the IDE **Password Safe**, bound to the GitLab URL; it is not in `gitlabMrPipelines.xml`.
- The token is sent only in HTTP headers, never in URLs; HTTP redirects are not followed.
- The token is never written to the log or to error messages; the log gets only the error kind and HTTP status.
- No other hosts are contacted and there is no telemetry.

## Git integration

Used only by the **Current project only** filter.

**Where remotes come from.**

- If the bundled Git plugin (Git4Idea) is enabled — the remotes of all repositories it detected in the project.
- Otherwise (or if it returned no remotes) the plugin reads `.git/config` itself in the project directory
  and in the content roots, including worktrees and submodules (a `.git` file with `gitdir:`). Nested
  repositories in subdirectories are not searched in this mode.
- `url` and `pushurl` of every `[remote "…"]` section are used. Remotes are recomputed on every refresh
  while the filter is on.

**Matching a remote against the configured GitLab URL:**

| Format | Example | Project path |
|---|---|---|
| SSH (scp-like) | `git@gitlab.company.com:group/sub/app.git` | `group/sub/app` |
| SSH URL with port | `ssh://git@gitlab.company.com:2222/group/app.git` | `group/app` |
| HTTPS | `https://gitlab.company.com/group/app.git` | `group/app` |
| HTTPS, GitLab in a sub-path | `https://company.com/gitlab/group/app.git` with URL `https://company.com/gitlab` | `group/app` |

- The host must equal the configured host (case-insensitive); **the port is ignored**.
- For HTTP(S) remotes the GitLab sub-path is stripped (a remote without that sub-path does not match);
  SSH remotes don't use the sub-path. `git+ssh://`, `ssh+git://`, `git://` and `http://` are supported too.
- `.git`, a trailing `/` and a login (`user@`) are ignored; project paths are compared case-insensitively.
- Remotes on other hosts (e.g. GitHub) are ignored.

**Empty list.** If the filter hides every MR, the table shows «Нет MR для проектов из git remote текущего
проекта» ("No MRs for the projects of the current project's git remotes") or, if there are no matching
remotes at all, «В текущем проекте нет git remote, указывающих на {host}» ("The current project has no
git remotes pointing to {host}"), plus a **Show MRs of all projects** («Показать MR всех проектов») link
that turns the filter off.

> [!NOTE]
> If your GitLab serves SSH on a **different host name** (e.g. `ssh.gitlab.company.com` while the web UI
> is `gitlab.company.com`), those remotes will not match — a separate SSH host is not supported.
> Use an HTTPS remote or turn the filter off.

## Notifications and status bar

The Current project only filter **does not affect** the widget or notifications — they count all MRs.

### Status bar widget

| State | Widget |
|---|---|
| Some pipelines failed | red cross + number of failed |
| None failed, some running / pending | run icon + their number |
| All pipelines succeeded | green check |
| Invalid token, no permissions or not configured | error icon |
| No MRs / no data | plugin icon |

Tooltip: «Упало: N, выполняется: N, успешно: N, всего MR: N» (failed, running, succeeded, total MRs).
Click opens the tool window. To hide the widget: right-click the status bar → *GitLab Pipelines*.

### Notifications

- Sent when an MR's head pipeline reaches a final status (`failed`, `success`, `canceled`, `skipped`,
  `manual`) and that notification type is enabled. A transition is a status change or a new pipeline
  (e.g. after a push).
- **The first poll is silent** — after IDE start, after a settings change and after recovering from a 401.
  State is kept in memory only: pipelines that finished while the IDE was closed do not notify.
- A new MR notifies only if its pipeline finished after the previous poll. No repeats.
- Intermediate statuses, hidden drafts, merged and closed MRs never notify.
- Title `group/project !123`, text — the MR title and status; actions **Open pipeline** («Открыть пайплайн»)
  and **Open MR** («Открыть MR»). Display style: *Settings → Appearance & Behavior → Notifications →
  GitLab MR Pipelines*.

## Troubleshooting / FAQ

**"Fail to load plugin descriptor" on install.** You installed the source archive. Download
`gitlab-pipelines-idea-plugin-X.Y.Z.zip` from **Assets**.

**«Неверный или просроченный токен (401)»** (invalid or expired token). Create a new `read_api` token on
the GitLab whose URL is in the settings.

**«Недостаточно прав: нужен scope read_api (403)»** (insufficient permissions). Create a token with the
`read_api` scope.

**«Не удалось подключиться к GitLab: …»** (cannot connect). Check the URL and the IDE proxy
(*Settings → Appearance & Behavior → System Settings → HTTP Proxy*). If GitLab uses a corporate or
self-signed certificate, add it in *Settings → Tools → Server Certificates* and click Refresh.

**«Некорректный ответ GitLab»** (malformed response). Often a redirect: the plugin does not follow 3xx.
Enter the URL exactly as GitLab opens in the browser after all redirects (`https://`, with the sub-path if any).

**The list is empty.**
- No open MRs you authored? Assigned / review MRs must be enabled in settings.
- Show drafts is off and all your MRs are drafts.
- Current project only is on and the project's remotes don't point to your GitLab — click
  Show MRs of all projects (see [Git integration](#git-integration)).

**No child rows with Show all pipelines.** Turning the option on triggers a refresh; pipelines appear
after it. If loading fails, the previous data stays (the IDE log has `Loading recent pipelines failed: …`).

**«Превышен лимит запросов GitLab, повтор через N с»** (rate limit). The plugin waits on its own;
increase the poll interval.

**A notification arrived late.** A delay of up to one poll interval is expected.

**Where is the log?** *Help → Show Log in …* — lines `GitLab poll failed: kind=…, httpStatus=…`.
The token is never logged.

## Development

Requires **JDK 21**; the Gradle wrapper and dependencies are downloaded automatically.

```bash
./gradlew test buildPlugin verifyPlugin   # what the release CI runs
./gradlew test          # core unit tests (JUnit 5, no IDE needed)
./gradlew buildPlugin   # build/distributions/gitlab-pipelines-idea-plugin-<version>.zip
./gradlew verifyPlugin  # IntelliJ Plugin Verifier (downloads several IDEs, needs lots of disk)
./gradlew runIde        # sandbox IDE with the plugin installed
```

Stack: Kotlin 2.0.21, IntelliJ Platform Gradle Plugin 2.1.0, built against IntelliJ IDEA Community 2024.2.4
(`sinceBuild = 242`, no `untilBuild`). HTTP — `java.net.http.HttpClient`, JSON — Gson from the platform.

```
src/main/kotlin/com/github/vgerya/gitlabpipelines/
  core/     plain Kotlin, no IntelliJ: model, GitLab client (GraphQL + REST), status diff,
            sorting/grouping (MrTree), git remote parsing (GitRemotes)
  plugin/   IDE integration: settings, polling service, tool window, widget, project remotes
src/main/resources/META-INF/
  plugin.xml                  main descriptor
  gitlab-pipelines-git.xml    loaded only when Git4Idea is enabled (<depends optional="true">)
src/main/resources/messages/MyBundle.properties   UI strings (Russian)
src/test/kotlin/.../core/     core unit tests;  src/test/resources/fixtures/  GraphQL/REST responses
docs/SPEC.md                  specification
```

Rule: `core/**` imports only the Kotlin stdlib, `java.*` and `com.google.gson.*`; tests cover core.

### CI and releases

| Workflow | When | What it does |
|---|---|---|
| **Build** (`build.yml`) | push to any branch, pull request, manual | `test` + `buildPlugin`, `plugin-distribution` artifact; a separate `verifyPlugin` job |
| **Release** (`release.yml`) | push to `main`, push of a `v*` tag, manual | build, verify and GitHub Release |

Releases are **automatic when the version is bumped on `main`**: update `CHANGELOG.md`, bump
`pluginVersion` in `gradle.properties` and push to `main`. If tag `vX.Y.Z` doesn't exist yet, the
workflow runs `test buildPlugin verifyPlugin`, creates the tag and a GitHub Release with the zip; if the
tag exists, nothing is published. A manual tag (`git tag vX.Y.Z && git push origin vX.Y.Z`) also works —
the version is taken from the tag.

## License

The repository does not contain a license file yet.
