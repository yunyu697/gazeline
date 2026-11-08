$ErrorActionPreference = 'Stop'
$proj = $PSScriptRoot
. (Join-Path $proj 'version-lib.ps1')
$info = Get-VersionInfo -ProjectRoot $proj

$fail = @()

Write-Output "version.txt      : $($info.Name)"
Write-Output "computed versionCode: $($info.Code) (alpha stage=$($info.Stage))"

# 1. The manifest must not carry a second copy of versionName / versionCode.
$manifest = Join-Path $proj 'app\src\main\AndroidManifest.xml'
$manifestText = Get-Content $manifest -Raw
if ($manifestText -match 'android:versionName|android:versionCode') {
    $fail += 'AndroidManifest.xml contains android:versionName/android:versionCode; version must only come from version.txt'
}

# 2. No other tracked file may hardcode the version string.
$escaped = [regex]::Escape($info.Name)
$isRepo = Test-Path (Join-Path $proj '.git')
if ($isRepo) {
    $files = @(& git -C $proj ls-files)
} else {
    $files = @(Get-ChildItem $proj -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\(build|\.git)\\' } |
        ForEach-Object { $_.FullName.Substring($proj.Length + 1) })
}
$stray = @()
foreach ($rel in $files) {
    if ($rel -eq 'version.txt') { continue }
    if ($rel -match '\.(apk|png|jpg|jpeg|webp|keystore|jks)$') { continue }
    $path = Join-Path $proj $rel
    if (-not (Test-Path $path)) { continue }
    if ((Get-Content $path -Raw -ErrorAction SilentlyContinue) -match $escaped) {
        $stray += $rel
    }
}
if ($stray.Count -gt 0) {
    $fail += "version string '$($info.Name)' hardcoded outside version.txt: $($stray -join ', ')"
}

# 3. If the annotated tag exists, it must be annotated and point at HEAD.
$tag = "v$($info.Name)"
if ($isRepo) {
    & git -C $proj rev-parse -q --verify "refs/tags/$tag" | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $type = (& git -C $proj cat-file -t $tag).Trim()
        if ($type -ne 'tag') { $fail += "tag $tag is not annotated (type=$type)" }
        $tagCommit = (& git -C $proj rev-list -n 1 $tag).Trim()
        $head = (& git -C $proj rev-parse HEAD).Trim()
        if ($tagCommit -ne $head) { $fail += "tag $tag does not point at HEAD" }
        Write-Output "tag $tag : annotated, commit $tagCommit"
    } else {
        Write-Output "tag $tag : not created yet (skipped)"
    }
}

# 4. If the release APK exists, its badging must match version.txt.
$apk = Join-Path $proj "build\gazeline-$($info.Name)-release.apk"
if (Test-Path $apk) {
    $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path (Split-Path $proj -Parent) '.tools\sdk' }
    $aapt2 = Join-Path $sdk 'build-tools\35.0.0\aapt2.exe'
    $badging = (& $aapt2 dump badging $apk) -join "`n"
    if ($badging -notmatch ("versionName='" + $escaped + "'")) {
        $fail += "APK versionName does not match $($info.Name)"
    }
    if ($badging -notmatch ("versionCode='" + $info.Code + "'")) {
        $fail += "APK versionCode does not match $($info.Code)"
    }
    Write-Output "APK badging checked: $([System.IO.Path]::GetFileName($apk))"
} else {
    Write-Output 'APK: not built yet (skipped)'
}

if ($fail.Count -gt 0) {
    $fail | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}
Write-Output 'PASS: version is consistent across version.txt, manifest, tag and APK.'
