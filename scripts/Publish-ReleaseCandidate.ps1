[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')]
    [string]$ConfirmTag
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedCertificate = 'c33027eef9607dcf592ac7f8fefe47961e728c349541c3fa23c99355e2edbcc1'
$expectedApks = @(
    'app-universal-release.apk',
    'app-armeabi-v7a-release.apk',
    'app-arm64-v8a-release.apk',
    'app-x86-release.apk',
    'app-x86_64-release.apk'
)

function Invoke-GhJson {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $json = & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub CLI failed: gh $($Arguments -join ' ')"
    }
    return $json | ConvertFrom-Json
}

function Wait-ForDispatchedRun {
    param(
        [Parameter(Mandatory = $true)][string]$ExpectedTitle,
        [Parameter(Mandatory = $true)][datetime]$NotBefore
    )

    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ([DateTime]::UtcNow -lt $deadline) {
        $runs = @(Invoke-GhJson -Arguments @(
            'run', 'list',
            '--workflow', 'promote-release.yml',
            '--event', 'workflow_dispatch',
            '--limit', '20',
            '--json', 'databaseId,displayTitle,createdAt,headBranch,status,url'
        ))
        $match = $runs |
            Where-Object {
                $_.displayTitle -eq $ExpectedTitle -and
                $_.headBranch -eq 'main' -and
                ([DateTime]$_.createdAt).ToUniversalTime() -ge $NotBefore
            } |
            Sort-Object { [DateTime]$_.createdAt } -Descending |
            Select-Object -First 1
        if ($null -ne $match) {
            return $match
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for workflow run: $ExpectedTitle"
}

foreach ($command in @('git', 'gh')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releaseDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'app\build\outputs\apk\release'))
if (-not $releaseDir.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw "Unsafe release output directory: $releaseDir"
}
$manifestPath = Join-Path $releaseDir 'release-candidate-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Candidate manifest is missing. Run Build-ReleaseCandidate.ps1 first: $manifestPath"
}

Push-Location $repoRoot
try {
    & gh auth status
    if ($LASTEXITCODE -ne 0) {
        throw 'GitHub CLI is not authenticated.'
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $tag = [string]$manifest.release.tag
    $runId = [long]$manifest.workflow.runId
    if ($ConfirmTag -ne $tag) {
        throw "Confirmation tag $ConfirmTag does not match candidate tag $tag."
    }
    if ([string]$manifest.workflow.path -ne '.github/workflows/release-candidate.yml') {
        throw 'Candidate workflow path is not trusted.'
    }
    if ([string]$manifest.release.applicationId -ne 'com.surfsave.browser') {
        throw 'Candidate application ID is invalid.'
    }
    if ([string]$manifest.release.certificateSha256 -ne $expectedCertificate) {
        throw 'Candidate signing certificate is invalid.'
    }
    $manifestApks = @($manifest.apks)
    if ($manifestApks.Count -ne $expectedApks.Count) {
        throw "Expected $($expectedApks.Count) candidate APKs."
    }
    $actualNames = @($manifestApks | ForEach-Object { [string]$_.name } | Sort-Object)
    if (($actualNames -join "`n") -ne (($expectedApks | Sort-Object) -join "`n")) {
        throw 'Candidate APK filename set is invalid.'
    }
    $diskNames = @(
        Get-ChildItem -LiteralPath $releaseDir -File -Filter '*.apk' |
            ForEach-Object { $_.Name } |
            Sort-Object
    )
    if (($diskNames -join "`n") -ne (($expectedApks | Sort-Object) -join "`n")) {
        throw 'Release output contains an unexpected or missing APK. Promotion is blocked.'
    }
    foreach ($entry in $manifestApks) {
        $name = [string]$entry.name
        if ([IO.Path]::GetFileName($name) -ne $name) {
            throw "Unsafe APK filename in manifest: $name"
        }
        $path = Join-Path $releaseDir $name
        $file = Get-Item -LiteralPath $path -ErrorAction Stop
        if ($file.Length -ne [long]$entry.size) {
            throw "Candidate APK size mismatch: $name"
        }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne [string]$entry.sha256) {
            throw "Candidate APK SHA-256 mismatch: $name"
        }
    }

    $repository = [string](Invoke-GhJson -Arguments @('repo', 'view', '--json', 'nameWithOwner')).nameWithOwner
    if ([string]$manifest.repository -ne $repository) {
        throw 'Candidate repository does not match the current repository.'
    }
    $runDetails = Invoke-GhJson -Arguments @('api', "repos/$repository/actions/runs/$runId")
    if (
        [string]$runDetails.path -ne '.github/workflows/release-candidate.yml' -or
        [string]$runDetails.event -ne 'workflow_dispatch' -or
        [string]$runDetails.status -ne 'completed' -or
        [string]$runDetails.conclusion -ne 'success' -or
        [string]$runDetails.head_branch -ne 'main' -or
        [string]$runDetails.head_sha -ne [string]$manifest.source.sha -or
        [string]$runDetails.head_repository.full_name -ne $repository
    ) {
        throw 'Candidate workflow run provenance is not trusted.'
    }

    $notBefore = [DateTime]::UtcNow.AddSeconds(-5)
    & gh workflow run promote-release.yml `
        --ref main `
        -f "candidate_run_id=$runId" `
        -f "release_tag=$tag"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to dispatch the release promotion workflow.'
    }

    $expectedTitle = "Promote $tag from candidate $runId"
    $promotion = Wait-ForDispatchedRun -ExpectedTitle $expectedTitle -NotBefore $notBefore
    Write-Host "Promotion workflow: $($promotion.url)"
    & gh run watch ([long]$promotion.databaseId) --exit-status
    if ($LASTEXITCODE -ne 0) {
        throw "Release promotion failed: $($promotion.url)"
    }

    $release = Invoke-GhJson -Arguments @('release', 'view', $tag, '--json', 'url,isDraft,isPrerelease,tagName,assets')
    if ($release.isDraft -or $release.isPrerelease -or [string]$release.tagName -ne $tag) {
        throw 'Published Release state is invalid.'
    }
    if (@($release.assets).Count -ne $expectedApks.Count) {
        throw 'Published Release does not contain exactly five APK assets.'
    }

    Write-Host ''
    Write-Host 'The tested release candidate was promoted without rebuilding.'
    Write-Host "Release: $($release.url)"
    Write-Host "Candidate run ID: $runId"
    Write-Host "Source: $($manifest.source.sha)"
}
finally {
    Pop-Location
}
