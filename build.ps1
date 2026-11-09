param(
    [ValidateSet('all', 'release', 'debug')]
    [string]$Variant = 'all',
    [string]$SdkPath = ''
)

$ErrorActionPreference = 'Stop'
$proj = $PSScriptRoot
. (Join-Path $proj 'version-lib.ps1')
$info = Get-VersionInfo -ProjectRoot $proj
$version = $info.Name
$versionCode = $info.Code

if ($SdkPath) {
    $sdk = $SdkPath
} elseif ($env:ANDROID_SDK_ROOT) {
    $sdk = $env:ANDROID_SDK_ROOT
} else {
    $sdk = Join-Path (Split-Path $proj -Parent) '.tools\sdk'
}

$bt = Join-Path $sdk 'build-tools\35.0.0'
$jar = Join-Path $sdk 'platforms\android-35\android.jar'
if (-not (Test-Path $jar)) {
    throw "Android SDK not found at '$sdk'. Set ANDROID_SDK_ROOT or pass -SdkPath. Need platform 35 and build-tools 35.0.0."
}

$src = Join-Path $proj 'app\src\main'
$out = Join-Path $proj 'build'
$resZip = Join-Path $out 'res.zip'
$dbgResZip = Join-Path $out 'debug-res.zip'
$gen = Join-Path $out 'gen'
$keystore = Join-Path $proj 'debug.keystore'

New-Item -ItemType Directory -Force $out, $gen | Out-Null
if (Test-Path $resZip) { Remove-Item $resZip -Force }
if (Test-Path $dbgResZip) { Remove-Item $dbgResZip -Force }

Write-Output "== version $version (versionCode $versionCode) =="
Write-Output '== aapt2 compile =='
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $src 'res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

# The debug APK uses a different package name, so it installs beside the release
# APK. Its label must say so, otherwise the launcher shows two identical icons.
$dbgRes = Join-Path $src '..\debug\res'
$dbgRes = (Resolve-Path $dbgRes).Path
& (Join-Path $bt 'aapt2.exe') compile --dir $dbgRes -o $dbgResZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile (debug overlay) failed' }

function Link-Apk([string]$variant) {
    $base = Join-Path $out "base-$variant.apk"
    if (Test-Path $base) { Remove-Item $base -Force }
    $linkArgs = @(
        'link', '-o', $base, '-I', $jar,
        '--manifest', (Join-Path $src 'AndroidManifest.xml'),
        '-R', $resZip,
        '--java', $gen,
        '--min-sdk-version', '26',
        '--target-sdk-version', '35',
        '--version-code', "$versionCode",
        '--version-name', $version,
        '--auto-add-overlay'
    )
    if ($variant -eq 'debug') {
        $linkArgs += @('--rename-manifest-package', 'com.yunyu697.gazeline.debug')
        $linkArgs += @('-R', $dbgResZip)
        # The debug build exists for on-device inspection: make it debuggable so
        # `adb shell run-as` can place test media in the app's private storage.
        $linkArgs += @('--debug-mode')
    }
    & (Join-Path $bt 'aapt2.exe') @linkArgs
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link ($variant) failed" }
    return $base
}

$variants = if ($Variant -eq 'all') { @('release', 'debug') } else { @($Variant) }
$bases = @{}
foreach ($v in $variants) {
    Write-Output "== aapt2 link ($v) =="
    $bases[$v] = Link-Apk $v
}

Write-Output '== javac =='
$classes = Join-Path $out 'classes'
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Force $classes | Out-Null
$javaFiles = @(Get-ChildItem (Join-Path $src 'java\com\yunyu697\gazeline') -Filter *.java | ForEach-Object { $_.FullName })
$javaFiles += (Join-Path $gen 'com\yunyu697\gazeline\R.java')
& javac -encoding UTF-8 --release 11 -cp $jar -d $classes @javaFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Output '== d8 =='
$dexDir = Join-Path $out 'dex'
if (Test-Path $dexDir) { Remove-Item $dexDir -Recurse -Force }
New-Item -ItemType Directory -Force $dexDir | Out-Null
# D8 takes a class container; passing every .class path overflowed the Windows
# command line once the app grew past its first handful of classes.
$classesJar = Join-Path $out 'classes.jar'
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
& jar cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& (Join-Path $bt 'd8.bat') --release --min-api 26 --lib $jar --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Output '== keystore =='
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$built = @()
foreach ($v in $variants) {
    Write-Output "== package ($v) =="
    $base = $bases[$v]
    $unsigned = Join-Path $out "unsigned-$v.apk"
    Copy-Item $base $unsigned -Force
    $zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
        $dst = $entry.Open()
        try {
            $dexStream = [System.IO.File]::OpenRead((Join-Path $dexDir 'classes.dex'))
            $dexStream.CopyTo($dst)
            $dexStream.Dispose()
        } finally { $dst.Dispose() }
    } finally { $zip.Dispose() }

    $aligned = Join-Path $out "aligned-$v.apk"
    if (Test-Path $aligned) { Remove-Item $aligned -Force }
    & (Join-Path $bt 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign ($v) failed" }

    $final = Join-Path $out "gazeline-$version-$v.apk"
    if (Test-Path $final) { Remove-Item $final -Force }
    & (Join-Path $bt 'apksigner.bat') sign --ks $keystore --ks-pass 'pass:android' --key-pass 'pass:android' --out $final $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign ($v) failed" }
    & (Join-Path $bt 'apksigner.bat') verify $final
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify ($v) failed" }

    $built += Get-Item $final
}

$sums = Join-Path $out 'SHA256SUMS'
$sumTargets = @($built | Where-Object { $_.Name -match '-release\.apk$' })
if ($sumTargets.Count -eq 0) { $sumTargets = $built }
$lines = @()
foreach ($f in $sumTargets) {
    $hash = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower()
    $lines += "$hash  $($f.Name)"
}
Set-Content -Path $sums -Value $lines -Encoding UTF8

Write-Output '== badging =='
foreach ($f in $built) {
    Write-Output "--- $($f.Name) ---"
    & (Join-Path $bt 'aapt2.exe') dump badging $f.FullName | Select-String -Pattern "package:|application-label|sdkVersion"
    'SIZE  : ' + $f.Length + ' bytes'
    'SHA256: ' + (Get-FileHash $f.FullName -Algorithm SHA256).Hash
}

Write-Output "SUMS  : $sums"
