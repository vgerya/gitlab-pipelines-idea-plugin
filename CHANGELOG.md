# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.1.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/releases/tag/v1.0.0
[0.1.0]: https://github.com/vgerya/gitlab-pipelines-idea-plugin/commit/8d0bdef77f3e3ebd3cdfcffef9e0b90e8d7e6fea
