# Glide TV: выпустить новую версию (поднять версию, тег vX.Y.Z -> GitHub Actions соберёт и опубликует APK).
# Continue: в Windows PowerShell 5.1 вывод git/gh в stderr при 'Stop' считается ошибкой.
# Ошибки проверяем сами по $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root
function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Run($exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe $($arguments -join ' ') завершился с кодом $LASTEXITCODE" }
}

$gradleFile = Join-Path $root 'app\build.gradle.kts'
$text = [IO.File]::ReadAllText($gradleFile)
$code = [int]([regex]::Match($text, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
Say "Текущая версия: $name (код $code)" 'Cyan'

$parts = $name.Split('.')
while ($parts.Count -lt 3) { $parts += '0' }
$suggest = "$($parts[0]).$($parts[1]).$([int]$parts[2] + 1)"
$new = Read-Host "Новая версия [$suggest]"
if ([string]::IsNullOrWhiteSpace($new)) { $new = $suggest }
if ($new -notmatch '^\d+\.\d+\.\d+$') { throw "Версия должна быть вида 1.2.3" }
$tag = "v$new"
if (git tag --list $tag) { throw "Тег $tag уже существует." }

$text = [regex]::Replace($text, 'versionCode\s*=\s*\d+', "versionCode = $($code + 1)")
$text = [regex]::Replace($text, 'versionName\s*=\s*"[^"]+"', "versionName = `"$new`"")
[IO.File]::WriteAllText($gradleFile, $text, (New-Object Text.UTF8Encoding($false)))

Run git @('add', '-A')
Run git @('commit', '-m', "Release $tag")
Run git @('tag', '-a', $tag, '-m', "Glide TV $new")
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
Run git @('push', 'origin', $branch)
Run git @('push', 'origin', $tag)

$web = (git remote get-url origin).Trim() -replace '\.git$', '' -replace '^git@github\.com:', 'https://github.com/'
Say ""
Say "Релиз $tag отправлен." 'Green'
Say "Сборка:  $web/actions"
Say "Релиз появится здесь через несколько минут: $web/releases"
