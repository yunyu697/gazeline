# Single source of truth for the version string is version.txt.
# versionCode mapping (fixed, must never change after the first public release):
#   1.0.0-alphaN  -> major*1000000 + minor*10000 + patch*100 + N
#   1.0.0 (stable) -> major*1000000 + minor*10000 + patch*100 + 99
# alpha N is limited to 1..98 so a stable build always sorts above any alpha.
# Example: major 1, minor 0, patch 0, alpha 1 -> 1000001

function Get-VersionInfo {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $file = Join-Path $ProjectRoot 'version.txt'
    if (-not (Test-Path $file)) { throw "version.txt not found at $file" }

    $lines = @((Get-Content $file) | Where-Object { $_.Trim().Length -gt 0 })
    if ($lines.Count -ne 1) { throw "version.txt must contain exactly one non-empty line" }
    $name = $lines[0].Trim()

    if ($name -notmatch '^(\d+)\.(\d+)\.(\d+)(-alpha(\d+))?$') {
        throw "version.txt '$name' does not match MAJOR.MINOR.PATCH[-alphaN]"
    }
    $major = [int64]$Matches[1]
    $minor = [int64]$Matches[2]
    $patch = [int64]$Matches[3]
    $isAlpha = $name -match '-alpha\d+$'

    if ($name -match '-alpha(\d+)$') {
        $stage = [int64]$Matches[1]
        if ($stage -lt 1 -or $stage -gt 98) { throw "alpha number must be in 1..98 (stable uses 99)" }
    } else {
        $stage = [int64]99
    }

    $code = ($major * 1000000) + ($minor * 10000) + ($patch * 100) + $stage
    if ($code -lt 1 -or $code -gt 2100000000) { throw "computed versionCode $code is out of range" }

    [pscustomobject]@{
        Name    = $name
        Code    = [int]$code
        Major   = $major
        Minor   = $minor
        Patch   = $patch
        Stage   = $stage
        IsAlpha = $isAlpha
    }
}
