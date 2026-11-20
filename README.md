# GitLab MR Pipelines

Плагин для IDE на платформе IntelliJ (IntelliJ IDEA, PyCharm, GoLand, WebStorm, PhpStorm и др., версии
**2024.2 и новее**), который показывает статусы пайплайнов ваших открытых merge request'ов в GitLab
(gitlab.com или self-hosted) прямо в IDE.

## Возможности

- Окно инструментов **«GitLab Pipelines»** (справа) со списком открытых MR: проект, номер, заголовок,
  ветка, статус пайплайна, время обновления. Сортировка: failed → running → pending → success → прочие →
  без пайплайна.
- Двойной клик / Enter открывает MR в браузере; контекстное меню — открыть пайплайн, скопировать ссылку на MR.
- **Виджет в строке состояния**: количество упавших (или выполняющихся / ожидающих) пайплайнов;
  клик открывает окно инструментов.
- **Уведомления** (balloon) при завершении пайплайна: failed / success / canceled, skipped, manual —
  каждый тип можно отключить. Первый опрос после запуска IDE или изменения настроек — «тихий» (без уведомлений).
- MR, где вы автор; опционально — где вы assignee или запрошены как reviewer. Опция скрытия draft-MR.
- Настраиваемый интервал опроса (30–3600 с).
- GitLab GraphQL API с автоматическим откатом на REST API.
- Токен хранится в защищённом хранилище IDE (Password Safe: системная связка ключей / KeePass),
  а не в XML-файлах настроек.
- Учитываются настройки прокси IDE; корпоративный CA можно добавить в *Settings → Tools → Server Certificates*.
  Плагин обращается только к указанному хосту GitLab.

## Скриншоты

Пока нет.

## Установка с диска

1. Скачайте `gitlab-pipelines-idea-plugin-<версия>.zip` со страницы
   [Releases](https://github.com/vgerya/gitlab-pipelines-idea-plugin/releases) (или артефакт
   `plugin-distribution` из сборки CI, или соберите сами — см. ниже).
2. В IDE откройте **Settings → Plugins → ⚙ → Install Plugin from Disk…**, выберите zip-файл
   (распаковывать не нужно).
3. Перезапустите IDE, если она попросит.

## Создание Personal Access Token в GitLab

1. Откройте в GitLab **аватар → Edit profile → Access tokens**
   (`https://<ваш-gitlab>/-/user_settings/personal_access_tokens`).
2. Нажмите **Add new token**, укажите имя (например, `idea-pipelines`) и срок действия.
3. Отметьте единственный scope **`read_api`** — плагину нужно только чтение.
4. Нажмите **Create personal access token** и скопируйте токен (он показывается один раз).

## Настройка

1. Откройте **Settings → Tools → GitLab MR Pipelines**.
2. Укажите URL GitLab: `https://gitlab.com` или `https://gitlab.company.ru[/подпуть]`.
3. Вставьте токен и нажмите **«Проверить соединение»** — должно отобразиться имя вашего пользователя.
4. При необходимости настройте интервал опроса, включение MR, где вы assignee / reviewer, скрытие draft-MR
   и типы уведомлений.
5. Нажмите **Apply** / **OK** и откройте окно инструментов «GitLab Pipelines».

## Сборка

Требуется JDK 21 (Gradle и зависимости скачиваются автоматически).

```bash
./gradlew test          # юнит-тесты ядра (JUnit 5, IDE не нужна)
./gradlew buildPlugin   # zip плагина -> build/distributions/
./gradlew verifyPlugin  # проверка совместимости IntelliJ Plugin Verifier (скачивает IDE)
./gradlew runIde        # запуск IDE-песочницы с плагином
```

Структура: `src/main/kotlin/.../core` — чистый Kotlin без зависимостей от IntelliJ (клиент GitLab,
сравнение статусов, сводка); `.../plugin` — интеграция с IDE. Подробная спецификация —
[docs/SPEC.md](docs/SPEC.md).

## Процесс релиза

1. Обновите `CHANGELOG.md` (и при желании `pluginVersion` в `gradle.properties`).
2. Создайте и отправьте тег вида `vX.Y.Z`:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. Workflow **Release** (`.github/workflows/release.yml`) запустит тесты, `buildPlugin` и `verifyPlugin`
   с версией из тега (`v0.2.0` → `0.2.0`) и создаст GitHub Release с прикреплённым zip-файлом плагина.

Каждый push в любую ветку проверяется workflow **Build** (`.github/workflows/build.yml`).
