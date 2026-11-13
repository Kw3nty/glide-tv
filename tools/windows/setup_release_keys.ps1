# Glide TV: один раз создать ключ подписи релизов и положить его в секреты GitHub.
# Continue: в Windows PowerShell 5.1 вывод git/gh в stderr при 'Stop' считается ошибкой.
# Ошибки проверяем сами по $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root
function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Plain([Security.SecureString]$s) {
    [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}

Say "=== Ключ подписи Glide TV ===" 'Cyan'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Say "Нужен GitHub CLI: winget install GitHub.cli, затем в новом окне: gh auth login" 'Yellow'
    exit 1
}
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Say "Сначала войдите: gh auth login" 'Yellow'; exit 1 }
$repo = (gh repo view --json nameWithOwner -q .nameWithOwner).Trim()
if (-not $repo) { throw "Не найден репозиторий GitHub. Сначала запустите push_to_github.bat." }
Say "Репозиторий: $repo"

# keytool: JAVA_HOME, JDK из Android Studio или PATH
$candidates = @()
if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
$candidates += "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe"
$candidates += "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
$keytool = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $keytool) { $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source }
if (-not $keytool) { throw "keytool не найден. Установите Android Studio или JDK 17 (winget install EclipseAdoptium.Temurin.17.JDK)." }

# Ключ хранится ВНЕ папки проекта
$dir = Join-Path $env:USERPROFILE 'GlideTV-keys'
$jks = Join-Path $dir 'release.jks'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$alias = 'glide'

if (Test-Path $jks) {
    Say "Ключ уже существует: $jks — использую его (НЕ пересоздаю, иначе обновления перестанут ставиться)." 'Yellow'
    $pass = Plain (Read-Host "Пароль от существующего ключа" -AsSecureString)
} else {
    do {
        $p1 = Plain (Read-Host "Придумайте пароль для ключа (минимум 6 символов)" -AsSecureString)
        $p2 = Plain (Read-Host "Повторите пароль" -AsSecureString)
        if ($p1 -ne $p2) { Say "Пароли не совпадают." 'Red' } elseif ($p1.Length -lt 6) { Say "Слишком короткий." 'Red' }
    } until ($p1 -eq $p2 -and $p1.Length -ge 6)
    $pass = $p1
    & $keytool -genkeypair -v -keystore $jks -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 `
        -validity 36500 -storepass $pass -keypass $pass -dname "CN=Glide TV, O=Glide TV"
    if ($LASTEXITCODE -ne 0) { throw "keytool не смог создать ключ." }
    Say "Ключ создан: $jks" 'Green'
}

# Проверка пароля
& $keytool -list -keystore $jks -storepass $pass -alias $alias | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Неверный пароль или повреждён ключ." }

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
Say "Записываю секреты в $repo ..." 'Cyan'
$secrets = [ordered]@{
    GLIDE_KEYSTORE_BASE64   = $b64
    GLIDE_KEYSTORE_PASSWORD = $pass
    GLIDE_KEY_ALIAS         = $alias
    GLIDE_KEY_PASSWORD      = $pass
}
foreach ($k in $secrets.Keys) {
    gh secret set $k --repo $repo --body $secrets[$k]
    if ($LASTEXITCODE -ne 0) { throw "Не удалось записать секрет $k." }
}

Say ""
Say "Готово! Секреты записаны." 'Green'
Say "ВАЖНО: сделайте резервную копию папки $dir и запомните пароль." 'Yellow'
Say "Без этого ключа выпустить обновление для уже установленных Glide TV будет невозможно."
Say "Теперь можно выпускать релизы: make_release.bat"
