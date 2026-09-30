# 把构建产物按版本号归档到 dist/，方便区分每一轮迭代装的是哪个包。
#
#   pwsh -File tools/package-apk.ps1
#
# 版本号直接读 app/build.gradle.kts，避免两处不一致。

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root "app\build.gradle.kts"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $apk)) {
    throw "找不到构建产物：$apk（先跑 :app:assembleDebug）"
}

$content = Get-Content $gradleFile -Raw
$versionCode = [regex]::Match($content, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$versionName = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $versionName) { throw "无法从 $gradleFile 解析 versionName" }

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist "闪电搜题-v$versionName.apk"
Copy-Item $apk $target -Force

$sizeMb = [math]::Round((Get-Item $target).Length / 1MB, 2)
Write-Output "versionCode = $versionCode"
Write-Output "versionName = $versionName"
Write-Output "archived    = $target  ($sizeMb MB)"
