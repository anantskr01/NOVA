$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

# Pair 1 requires Java 17. Prefer the known Microsoft JDK location, then search common installs.
$javaCandidates = @(
    'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot',
    'C:\Program Files\Microsoft\jdk-17*'
)
$javaHome = $null
foreach ($candidate in $javaCandidates) {
    $match = Get-Item $candidate -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if ($match) { $javaHome = $match.FullName; break }
}
if (-not $javaHome) {
    throw 'Java 17 was not found. Install a JDK 17 or set JAVA_HOME to a valid JDK 17 before starting the PC companion.'
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

$workspace = Join-Path (Split-Path -Parent $repoRoot) 'AirControlFinal'
if (-not (Test-Path $workspace -PathType Container)) {
    $workspace = $repoRoot
}

$env:NOVA_PC_WORKSPACE = $workspace
$env:NOVA_PC_BIND = '0.0.0.0'
$env:NOVA_PC_PORT = '18765'
$env:NOVA_PC_TOKEN = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))

Write-Host ''
Write-Host 'NOVA Pair 1 PC Companion' -ForegroundColor Cyan
Write-Host "Java:      $javaHome"
Write-Host "Workspace: $env:NOVA_PC_WORKSPACE"
Write-Host "Port:      $env:NOVA_PC_PORT"
Write-Host ''
Write-Host 'PC token (enter this on the tablet; do not share it publicly):' -ForegroundColor Yellow
Write-Host $env:NOVA_PC_TOKEN -ForegroundColor Yellow
Write-Host ''
Write-Host 'Keep this window open while pairing.' -ForegroundColor Green
Write-Host ''

& .\gradlew :pc-companion:run
