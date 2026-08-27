[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')]
    [string]$Tag
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
$candidateFiles = $expectedApks + @('release-candidate-manifest.json')

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
        [Parameter(Mandatory = $true)][string]$Workflow,
        [Parameter(Mandatory = $true)][string]$ExpectedTitle,
        [Parameter(Mandatory = $true)][datetime]$NotBefore
    )

    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ([DateTime]::UtcNow -lt $deadline) {
        $runs = @(Invoke-GhJson -Arguments @(
            'run', 'list',
            '--workflow', $Workflow,
            '--event', 'workflow_dispatch',
            '--limit', '20',
            '--json', 'databaseId,displayTitle,createdAt,headBranch,headSha,status,url'
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

function Assert-CandidateFiles {
    param(
        [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
        [Parameter(Mandatory = $true)]$Manifest,
        [Parameter(Mandatory = $true)][long]$RunId,
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$ExpectedTag,
        [Parameter(Mandatory = $true)][string]$SourceSha
    )

    if ([int]$Manifest.schemaVersion -ne 1) {
        throw 'Unsupported candidate manifest schema.'
    }
    if ([string]$Manifest.repository -ne $Repository) {
        throw 'Candidate repository does not match the current repository.'
    }
    if ([string]$Manifest.workflow.path -ne '.github/workflows/release-candidate.yml') {
        throw 'Candidate workflow path is not trusted.'
    }
    if ([long]$Manifest.workflow.runId -ne $RunId) {
        throw 'Candidate run ID does not match the downloaded workflow run.'
    }
    if ([string]$Manifest.source.sha -ne $SourceSha) {
        throw 'Candidate source SHA does not match the workflow run.'
    }
    if ([string]$Manifest.source.ref -ne 'refs/heads/main') {
        throw 'Candidate source ref is not refs/heads/main.'
    }
    if ([string]$Manifest.release.tag -ne $ExpectedTag) {
        throw 'Candidate tag does not match the requested tag.'
    }
    if ([string]$Manifest.release.applicationId -ne 'com.surfsave.browser') {
        throw 'Candidate application ID is invalid.'
    }
    if ([string]$Manifest.release.certificateSha256 -ne $expectedCertificate) {
        throw 'Candidate signing certificate is invalid.'
    }

    $manifestApks = @($Manifest.apks)
    if ($manifestApks.Count -ne $expectedApks.Count) {
        throw "Expected $($expectedApks.Count) APK entries, got $($manifestApks.Count)."
    }
    $actualNames = @($manifestApks | ForEach-Object { [string]$_.name } | Sort-Object)
    $wantedNames = @($expectedApks | Sort-Object)
    if (($actualNames -join "`n") -ne ($wantedNames -join "`n")) {
        throw 'Candidate APK filename set is invalid.'
    }
    $diskNames = @(
        Get-ChildItem -LiteralPath $ReleaseDirectory -File -Filter '*.apk' |
            ForEach-Object { $_.Name } |
            Sort-Object
    )
    if (($diskNames -join "`n") -ne ($wantedNames -join "`n")) {
        throw 'Release output contains an unexpected or missing APK. Remove unrelated APKs before testing.'
    }

    foreach ($entry in $manifestApks) {
        $name = [string]$entry.name
        if ([IO.Path]::GetFileName($name) -ne $name) {
            throw "Unsafe APK filename in manifest: $name"
        }
        $path = Join-Path $ReleaseDirectory $name
        $file = Get-Item -LiteralPath $path -ErrorAction Stop
        if ($file.Length -ne [long]$entry.size) {
            throw "Candidate APK size mismatch: $name"
        }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne [string]$entry.sha256) {
            throw "Candidate APK SHA-256 mismatch: $name"
        }
    }
}

foreach ($command in @('git', 'gh')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releaseDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'app\build\outputs\apk\release'))
$expectedReleaseDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'app\build\outputs\apk\release'))
if ($releaseDir -ne $expectedReleaseDir -or -not $releaseDir.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw "Unsafe release output directory: $releaseDir"
}

Push-Location $repoRoot
try {
    & gh auth status
    if ($LASTEXITCODE -ne 0) {
        throw 'GitHub CLI is not authenticated.'
    }

    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') {
        throw 'Release candidates must be started from the local main branch.'
    }
    $trackedChanges = (& git status --porcelain --untracked-files=no)
    if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrWhiteSpace(($trackedChanges -join "`n"))) {
        throw 'Tracked files are not clean. Commit and push the release source before building a candidate.'
    }
    & git fetch origin main
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to fetch origin/main.'
    }
    $head = (& git rev-parse HEAD).Trim()
    $originMain = (& git rev-parse origin/main).Trim()
    if ($head -ne $originMain) {
        throw "Local HEAD $head does not match origin/main $originMain."
    }

    $versionLine = Select-String -LiteralPath 'app\build.gradle.kts' -Pattern '^\s*val baseVersionName = "([^"]+)"' |
        Select-Object -First 1
    if ($null -eq $versionLine) {
        throw 'Unable to read baseVersionName from app/build.gradle.kts.'
    }
    $versionName = $versionLine.Matches[0].Groups[1].Value
    if ($Tag -ne "v$versionName") {
        throw "Requested tag $Tag does not match source version $versionName."
    }

    $repository = [string](Invoke-GhJson -Arguments @('repo', 'view', '--json', 'nameWithOwner')).nameWithOwner
    $notBefore = [DateTime]::UtcNow.AddSeconds(-5)
    $requestId = [Guid]::NewGuid().ToString('N')
    & gh workflow run release-candidate.yml --ref main -f "release_tag=$Tag" -f "request_id=$requestId"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to dispatch the release candidate workflow.'
    }

    $expectedTitle = "Candidate $Tag request $requestId from main"
    $run = Wait-ForDispatchedRun -Workflow 'release-candidate.yml' -ExpectedTitle $expectedTitle -NotBefore $notBefore
    $runId = [long]$run.databaseId
    Write-Host "Candidate workflow: $($run.url)"
    & gh run watch $runId --exit-status
    if ($LASTEXITCODE -ne 0) {
        throw "Candidate workflow failed: $($run.url)"
    }

    $runDetails = Invoke-GhJson -Arguments @('api', "repos/$repository/actions/runs/$runId")
    if (
        [string]$runDetails.path -ne '.github/workflows/release-candidate.yml' -or
        [string]$runDetails.event -ne 'workflow_dispatch' -or
        [string]$runDetails.status -ne 'completed' -or
        [string]$runDetails.conclusion -ne 'success' -or
        [string]$runDetails.head_branch -ne 'main' -or
        [string]$runDetails.head_repository.full_name -ne $repository
    ) {
        throw 'Completed candidate run provenance is not trusted.'
    }

    New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
    foreach ($name in $candidateFiles) {
        $existing = Join-Path $releaseDir $name
        if (Test-Path -LiteralPath $existing -PathType Leaf) {
            Remove-Item -LiteralPath $existing -Force
        }
    }
    & gh run download $runId --name release-candidate --dir $releaseDir
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to download the immutable candidate artifact.'
    }

    $manifestPath = Join-Path $releaseDir 'release-candidate-manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-CandidateFiles `
        -ReleaseDirectory $releaseDir `
        -Manifest $manifest `
        -RunId $runId `
        -Repository $repository `
        -ExpectedTag $Tag `
        -SourceSha ([string]$runDetails.head_sha)

    Write-Host ''
    Write-Host 'Signed release candidate downloaded and verified.'
    Write-Host "Run ID: $runId"
    Write-Host "Source: $($runDetails.head_sha)"
    Write-Host "APK directory: $releaseDir"
    Write-Host "Install for most phones: $(Join-Path $releaseDir 'app-arm64-v8a-release.apk')"
    Write-Host "After testing, run: .\scripts\Publish-ReleaseCandidate.ps1 -ConfirmTag $Tag"
}
finally {
    Pop-Location
}
