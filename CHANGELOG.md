# Changelog

Все заметные изменения проекта описываются в этом файле.
Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии — [SemVer](https://semver.org/lang/ru/).

## [1.0.1] — 2026-11-21

### Изменено

- Новая иконка окна «GitLab Pipelines»: 16×16 под новый интерфейс IDE, отдельный вариант для тёмной темы.
- Автоматический релиз: при повышении `pluginVersion` в `main` CI сам создаёт тег и GitHub Release.

## [0.1.0] — 2026-11-20

### Добавлено

- Первый выпуск плагина для IDE на платформе IntelliJ 2024.2+.
- Окно инструментов «GitLab Pipelines» со списком открытых MR и статусами их пайплайнов.
- Виджет в строке состояния с количеством упавших / выполняющихся пайплайнов.
- Уведомления о завершении пайплайнов (failed, success, canceled, skipped, manual) с настройкой типов.
- Настройки в *Settings → Tools → GitLab MR Pipelines*: URL GitLab, токен (в Password Safe),
  интервал опроса, MR где вы assignee / reviewer, скрытие draft-MR, проверка соединения.
- Поддержка gitlab.com и self-hosted GitLab (включая установку в подпути); GraphQL API с откатом на REST.
- CI-сборка (test, buildPlugin, verifyPlugin) и автоматический GitHub Release по тегу `vX.Y.Z`.
