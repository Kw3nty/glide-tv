# Glide TV: первая заливка / обновление кода на GitHub.
# Запуск: двойной клик по push_to_github.bat в корне проекта.
# Continue: в Windows PowerShell 5.1 вывод git/gh в stderr при 'Stop' считается ошибкой.
# Ошибки проверяем сами по $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Ask($question, $default = '') {
    $suffix = if ($default) { " [$default]" } else { '' }
    $a = Read-Host "$question$suffix"
    if ([string]::IsNullOrWhiteSpace($a)) { return $default } else { return $a.Trim() }
}
function Run($exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe $($arguments -join ' ') завершился с кодом $LASTEXITCODE" }
}

Say "=== Glide TV -> GitHub ===" 'Cyan'
Say "Папка проекта: $root"

# 1. Git
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Say "Git не найден." 'Yellow'
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        if ((Ask "Установить Git через winget? (y/n)" 'y') -eq 'y') {
            winget install --id Git.Git -e --source winget
            Say "Git установлен. Закройте это окно и запустите push_to_github.bat ещё раз." 'Green'
            exit 0
        }
    }
    Say "Скачайте Git: https://git-scm.com/download/win и запустите скрипт снова." 'Yellow'
    exit 1
}

# 2. Имя и почта для коммитов (только для этого репозитория, если глобально не заданы)
$name = (git config user.name) 2>$null
$email = (git config user.email) 2>$null
if (-not (Test-Path .git)) {
    Run git @('init')
    Run git @('checkout', '-B', 'main')
}
if (-not $name)  { git config user.name  (Ask "Ваше имя для коммитов" 'Glide TV Dev') }
if (-not $email) { git config user.email (Ask "Ваш e-mail для коммитов (можно e-mail GitHub)" 'dev@example.com') }

# Ключи подписи никогда не должны попасть в репозиторий
$leaked = git ls-files --others --cached --exclude-standard | Where-Object { $_ -match '\.(jks|keystore)$' -or $_ -match 'keystore\.properties$' }
if ($leaked) { throw "В проекте лежат файлы ключей ($($leaked -join ', ')). Уберите их из папки проекта." }

# 3. Коммит
Run git @('add', '-A')
# в Windows у gradlew нет флага «исполняемый» — ставим его в git для сборки на GitHub (Linux)
git update-index --chmod=+x gradlew 2>$null
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    $msg = Ask "Описание изменений (commit message)" 'Glide TV: обновление'
    Run git @('commit', '-m', $msg)
} else {
    Say "Новых изменений нет — просто отправляю на GitHub."
}
$branch = (git rev-parse --abbrev-ref HEAD).Trim()

# 4. Репозиторий на GitHub
$origin = (git remote get-url origin) 2>$null
if (-not $origin) {
    $hasGh = [bool](Get-Command gh -ErrorAction SilentlyContinue)
    if ($hasGh) { gh auth status 2>$null | Out-Null; $ghOk = ($LASTEXITCODE -eq 0) } else { $ghOk = $false }

    if ($ghOk) {
        Say "Найден GitHub CLI — могу создать репозиторий сам." 'Green'
        $repo = Ask "Имя репозитория" 'glide-tv'
        Say "Автообновления в приложении читают релизы без авторизации, поэтому репозиторий должен быть ПУБЛИЧНЫМ." 'Yellow'
        $vis = Ask "Видимость: public / private" 'public'
        Run gh @('repo', 'create', $repo, "--$vis", '--source', '.', '--remote', 'origin', '--description', 'Glide TV — оптимизатор для Android TV / Google TV / Fire TV')
    } else {
        Say ""
        Say "1) Откройте https://github.com/new" 'Cyan'
        Say "2) Имя: glide-tv, видимость Public (нужно для автообновлений), БЕЗ README/.gitignore/лицензии"
        Say "3) Скопируйте адрес вида https://github.com/<логин>/glide-tv.git"
        Say "(Совет: с GitHub CLI — winget install GitHub.cli, затем gh auth login — скрипт создаст репозиторий сам.)"
        $url = Ask "Адрес репозитория"
        if (-not $url) { throw "Адрес репозитория не указан." }
        Run git @('remote', 'add', 'origin', $url)
    }
    $origin = (git remote get-url origin).Trim()
}

# 5. Отправка (при первом push откроется окно входа в GitHub — Git Credential Manager)
# Если на GitHub уже есть новые коммиты (слитый Pull Request и т. п.) — сначала забираем их
git ls-remote --exit-code --heads origin $branch *> $null
if ($LASTEXITCODE -eq 0) {
    Say "Забираю изменения с GitHub..." 'Cyan'
    Run git @('pull', '--no-rebase', '--no-edit', 'origin', $branch)
}
Say "Отправляю ветку $branch в $origin ..." 'Cyan'
Run git @('push', '-u', 'origin', $branch)

$web = $origin -replace '\.git$', '' -replace '^git@github\.com:', 'https://github.com/'
Say ""
Say "Готово!" 'Green'
Say "Репозиторий: $web"
Say "Сборка APK:  $web/actions  (артефакт glide-tv-debug появится через несколько минут)"
Say "Следующий шаг для релизов: setup_release_keys.bat, затем make_release.bat"
