<#
.SYNOPSIS
    Builds the Windows portable zip: a jpackage app image (bundled Java runtime) around the
    client + local-server desktop jar, plus example projects.
.EXAMPLE
    .\.github\scripts\package-windows-portable.ps1 -Version 0.2.0 -DesktopJar expanse-desktop\target\expanse-desktop-0.2.0.jar -SmokeTest
#>
param(
    [Parameter(Mandatory)] [string] $Version,
    [Parameter(Mandatory)] [string] $DesktopJar,
    [string] $OutputDirectory = 'target\release',
    [switch] $SmokeTest
)
$ErrorActionPreference = 'Stop'

$appName = 'ExpanseDesign'
$appVersion = [regex]::Match($Version, '^\d+(\.\d+){0,2}').Value
if (-not $appVersion) { throw "Version must start with a number for jpackage: $Version" }
if (-not (Test-Path $DesktopJar -PathType Leaf)) { throw "Desktop jar not found: $DesktopJar" }

$work = Join-Path $OutputDirectory 'windows-portable'
$staging = Join-Path $work 'input'
$images = Join-Path $work 'image'
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $staging, $images | Out-Null
Copy-Item $DesktopJar (Join-Path $staging 'expanse-desktop.jar')

# Keep java.exe in the runtime (no --strip-native-commands): the desktop starts its local server with it.
jpackage --type app-image --input $staging --main-jar expanse-desktop.jar --name $appName `
    --app-version $appVersion --vendor 'Talaat Harb' --description 'Expanse Design EDA workbench' `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --jlink-options '--strip-debug --no-man-pages --no-header-files' --dest $images
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

$app = Join-Path $images $appName
foreach ($required in "$appName.exe", 'runtime\bin\java.exe') {
    if (-not (Test-Path (Join-Path $app $required))) { throw "Packaged image is missing $required" }
}
$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
New-Item -ItemType Directory -Force -Path (Join-Path $app 'examples') | Out-Null
Copy-Item (Join-Path $repo 'fixtures\projects\*.json') (Join-Path $app 'examples')
@"
Expanse Design $Version - Windows portable

Run $appName.exe. The desktop starts its own loopback server on port 8765 and stores
projects, the server log and a generated local API token in %USERPROFILE%\.expanse-design
(override with --expanse.data-dir=<dir>). Example projects are in the examples folder:
  $appName.exe --expanse.open=examples\logic-demo.json
No Java installation is required.
"@ | Set-Content -Encoding utf8 (Join-Path $app 'README.txt')

if ($SmokeTest) {
    $smoke = Join-Path (Resolve-Path $work) 'smoke'
    $exe = Join-Path $app "$appName.exe"
    $arguments = @('--expanse.open=' + (Join-Path (Resolve-Path $app) 'examples\logic-demo.json'),
        "--expanse.desktop.capture-dir=$smoke\capture", "--expanse.data-dir=$smoke\data",
        '--expanse.server-url=http://127.0.0.1:8797')
    $process = Start-Process $exe -ArgumentList $arguments -PassThru
    if (-not $process.WaitForExit(240000)) {
        Stop-Process -Id $process.Id -Force
        throw 'Smoke test timed out'
    }
    $captures = @(Get-ChildItem "$smoke\capture\*.png" -ErrorAction SilentlyContinue)
    if ($captures.Count -ne 4) {
        Get-Content "$smoke\data\server.log" -ErrorAction SilentlyContinue | Select-Object -Last 40
        throw "Smoke test produced $($captures.Count) of 4 tab captures"
    }
    Write-Host "Smoke test captured: $($captures.Name -join ', ')"
}

$zip = Join-Path $OutputDirectory "expanse-design-$Version-windows-portable.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive -Path $app -DestinationPath $zip
Write-Host "Created $zip"
if ($env:GITHUB_OUTPUT) { "zip=$zip" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8 }
