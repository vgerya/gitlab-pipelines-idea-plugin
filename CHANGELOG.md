# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] — 2026-11-21

### Added

- **Filter** toolbar dropdown replacing the Current project only toggle: **All Merge Requests** /
  **Current Project** / **Current Project and Branch**. The new branch mode shows an MR only if some
  repository of the IDE project has a remote for the MR's project and has the MR's source branch checked
  out (a detached HEAD never matches). It follows `git checkout`: Git4Idea change events, a re-check every
  4 s while the tool window is visible, and after each poll. Saved per project; the 1.1 "only current
  project" setting is migrated to Current Project.
- Empty-state messages for the branch filter ("No merge requests for branch X in this project", "No branch
  is checked out in this project (detached HEAD)"), each with a **Show all merge requests** link.
- **Started**, **Duration** and **Finished** columns. Started/Finished show relative time with the absolute
  time in the tooltip; Duration is shown as `4m 12s` and counts up live for running pipelines; `—` when
  not started. Sorted chronologically / numerically, empty values last. Started and Duration are visible
  by default (and switched on once for users with customised 1.1 columns); Finished is opt-in.
- Settings: **Create token ↗** link to the token page of the entered GitLab URL.

### Changed

- The whole UI is now in English; the `success` status is shown as **Passed**.
- Toolbar: Refresh | Filter | Group By | Show All Pipelines | Columns | Settings.
- Recent pipeline refs are shown as `MR !881`, `MR !881 (merged result)`, `MR !881 (merge train)`; the
  full ref is in the tooltip.
- The Status (tree) column is 260 px wide by default.
- Settings: the token hint ("Requires a token with the read_api scope.") wraps, and the fields stretch with
  the page.

### Fixed

- The settings page scrolled horizontally because of the long token hint.
- The Group By and Columns toolbar buttons had identical icons in the new UI.

## [1.1.0] — 2026-11-21

### Added

- The tool window table is now a tree with **grouping**: none / by project / by branch / by project and
  branch. A group row shows the worst status icon, the name and the MR count; expansion is kept across
  refreshes.
- **Sorting by any column** by clicking its header (click again to reverse, ▲ / ▼). Default: by status,
  then by update time; empty values always last.
- **Column chooser**: the Columns toolbar button or right-click on the header. Columns can be reordered
  by dragging and resized. New **Role** (author / assignee / reviewer) and **Pipeline** (`#iid`) columns,
  hidden by default.
- **Current project only** toggle: shows only MRs of the projects the open IDE project's git remotes point
  to (SSH, scp-like, HTTP(S), GitLab in a sub-path; host case-insensitive, port ignored). Remembered per
  project. The status bar widget and notifications still count all MRs.
- **Show all pipelines** toggle: the last 10 pipelines of each MR as child rows. The extra requests
  (GraphQL with REST fallback) are made only while the option is on.
- Double-click / Enter on a pipeline row opens the pipeline; on a group row it expands/collapses the group.
- Optional dependency on the bundled Git plugin; without it, remotes are read from `.git/config` of the
  project directory and content roots (worktrees included).
- View settings (columns, order, widths, sort, grouping, Show all pipelines) are persisted in
  `gitlabMrPipelinesView.xml`.

### Changed

- Releases are automatic: bumping `pluginVersion` on `main` makes CI create the tag and the GitHub Release
  (a manual `vX.Y.Z` tag still works).

### Fixed

- The Status column showed internal text `MergeRequest(...)` instead of the status icon and label.

## [1.0.1] — 2026-11-21

### Changed

- New "GitLab Pipelines" tool window icon: 16×16 for the new IDE UI, with a separate dark-theme variant.

## [1.0.0] — 2026-11-20

First public release (GitHub Release with the plugin zip). The code is the same as 0.1.0.

## [0.1.0] — 2026-11-20

### Added

- First version of the plugin for IntelliJ-based IDEs 2024.2+.
- "GitLab Pipelines" tool window listing open MRs and their pipeline status.
- Status bar widget with the number of failed / running pipelines.
- Notifications when pipelines finish (failed, success, canceled, skipped, manual), configurable per type.
- Settings in *Settings → Tools → GitLab MR Pipelines*: GitLab URL, token (in Password Safe), poll
  interval, MRs where you are assignee / reviewer, hiding draft MRs, connection test.
- gitlab.com and self-hosted GitLab (including sub-path installs); GraphQL API with REST fallback.
- CI build (test, buildPlugin, verifyPlugin) and GitHub Release on a `vX.Y.Z` tag.

[1.2.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/releases/tag/v1.0.0
[0.1.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/commit/8d0bdef77f3e3ebd3cdfcffef9e0b90e8d7e6fea
