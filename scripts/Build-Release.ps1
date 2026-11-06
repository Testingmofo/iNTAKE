[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$releaseDirectory = Join-Path $projectRoot 'For-Release'
$stagingDirectory = Join-Path $projectRoot '.release-build'
$packageInput = Join-Path $stagingDirectory 'input'
$imageDirectory = Join-Path $stagingDirectory 'image'
$applicationImage = Join-Path $imageDirectory 'iNTAKE'
$version = '0.2.1'
$releaseLabel = '0.2.1-beta'
$pomPath = Join-Path $projectRoot 'pom.xml'
[xml]$pomDocument = Get-Content -LiteralPath $pomPath -Raw
$mavenArtifactVersion = [string]$pomDocument.project.version
$applicationJarName = "intake-$mavenArtifactVersion.jar"
$extensionManifestPath = Join-Path $projectRoot 'browser-extension\manifest.json'
$extensionManifest = Get-Content -LiteralPath $extensionManifestPath -Raw | ConvertFrom-Json
$extensionVersion = [string]$extensionManifest.version
if ($extensionVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid browser extension version in ${extensionManifestPath}: $extensionVersion"
}
$upgradeUuid = '06f5b964-26a8-4eeb-91c4-df18a7e6dde2'

function Assert-ProjectChild {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith(
            $projectRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the project: $fullPath"
    }
    return $fullPath
}

function Reset-Directory {
    param([Parameter(Mandatory)][string]$Path)
    $safePath = Assert-ProjectChild $Path
    if (Test-Path -LiteralPath $safePath) {
        Remove-Item -LiteralPath $safePath -Recurse -Force
    }
    [void](New-Item -ItemType Directory -Path $safePath -Force)
}

function Refresh-Environment {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = @($machinePath, $userPath) -join ';'
    $wixBin = 'C:\Program Files (x86)\WiX Toolset v3.14\bin'
    if (Test-Path -LiteralPath $wixBin) {
        $env:Path = $wixBin + ';' + $env:Path
    }
}

function Require-Command {
    param([Parameter(Mandatory)][string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "Required release tool is unavailable: $Name"
    }
    return $command.Source
}

function Find-ChocolateyTool {
    param(
        [Parameter(Mandatory)][string]$Package,
        [Parameter(Mandatory)][string]$Executable,
        [string]$Architecture = ''
    )
    $chocolatey = Require-Command 'choco.exe'
    $chocolateyRoot = Split-Path -Parent (Split-Path -Parent $chocolatey)
    $packageRoot = Join-Path $chocolateyRoot ("lib\" + $Package)
    $candidates = Get-ChildItem -LiteralPath $packageRoot -Filter $Executable -File -Recurse
    if ($Architecture) {
        $architectural = @($candidates | Where-Object FullName -Match (
            '[\\/]' + [regex]::Escape($Architecture) + '[\\/]'))
        if ($architectural.Count -gt 0) {
            $candidates = $architectural
        }
    }
    $selected = @($candidates | Sort-Object Length -Descending | Select-Object -First 1)
    if ($selected.Count -eq 0) {
        throw "Could not locate $Executable in Chocolatey package $Package."
    }
    return $selected[0].FullName
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(Mandatory)][string[]]$Arguments
    )
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE."
    }
}

Set-Location -LiteralPath $projectRoot
Refresh-Environment

$maven = Require-Command 'mvn.cmd'
$jpackage = Require-Command 'jpackage.exe'
[void](Require-Command 'candle.exe')
[void](Require-Command 'light.exe')
$deno = Require-Command 'deno.exe'
$ytDlp = Find-ChocolateyTool -Package 'yt-dlp' -Executable 'yt-dlp.exe' -Architecture 'x64'
$ffmpeg = Find-ChocolateyTool -Package 'ffmpeg' -Executable 'ffmpeg.exe'
$ffprobe = Find-ChocolateyTool -Package 'ffmpeg' -Executable 'ffprobe.exe'

Reset-Directory $releaseDirectory
Reset-Directory $stagingDirectory
[void](New-Item -ItemType Directory -Path $packageInput -Force)
[void](New-Item -ItemType Directory -Path $imageDirectory -Force)

Write-Host '[iNTAKE] Compiling Java and resolving runtime dependencies...' -ForegroundColor Cyan
Invoke-Checked $maven @(
    'clean', 'package', 'dependency:copy-dependencies',
    '-DincludeScope=runtime',
    '-DoutputDirectory=target/release-dependencies'
)

Copy-Item -LiteralPath (Join-Path $projectRoot "target\$applicationJarName") -Destination $packageInput
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'target\release-dependencies') -Filter '*.jar' -File |
    Copy-Item -Destination $packageInput
Copy-Item -LiteralPath (Join-Path $projectRoot 'release\THIRD-PARTY-NOTICES.txt') -Destination $packageInput

$toolsDirectory = Join-Path $packageInput 'tools'
[void](New-Item -ItemType Directory -Path $toolsDirectory -Force)
Copy-Item -LiteralPath $ytDlp -Destination (Join-Path $toolsDirectory 'yt-dlp.exe')
Copy-Item -LiteralPath $ffmpeg -Destination (Join-Path $toolsDirectory 'ffmpeg.exe')
Copy-Item -LiteralPath $ffprobe -Destination (Join-Path $toolsDirectory 'ffprobe.exe')
Copy-Item -LiteralPath $deno -Destination (Join-Path $toolsDirectory 'deno.exe')

Write-Host '[iNTAKE] Compiling native browser wake helper...' -ForegroundColor Cyan
Add-Type -Path (Join-Path $projectRoot 'native-host\IntakeNativeHost.cs') `
    -OutputAssembly (Join-Path $toolsDirectory 'iNTAKE.NativeHost.exe') `
    -OutputType ConsoleApplication `
    -ReferencedAssemblies 'System.Web.Extensions.dll'

Write-Host '[iNTAKE] Creating self-contained portable image...' -ForegroundColor Cyan
$uninstallLauncherProperties = Join-Path $stagingDirectory 'uninstall-launcher.properties'
[IO.File]::WriteAllLines(
    $uninstallLauncherProperties,
    @(
        'main-class=UninstallLauncher',
        'win-console=false',
        'win-menu=true',
        'win-shortcut=false'
    ),
    [Text.UTF8Encoding]::new($false))
Invoke-Checked $jpackage @(
    '--type', 'app-image',
    '--dest', $imageDirectory,
    '--input', $packageInput,
    '--name', 'iNTAKE',
    '--main-jar', $applicationJarName,
    '--main-class', 'IntakeLauncher',
    '--app-version', $version,
    '--vendor', 'iNSRT',
    '--description', 'iNTAKE Media NetCrawler',
    '--add-modules', 'java.desktop,java.net.http,jdk.httpserver,jdk.unsupported,java.logging,java.xml,jdk.crypto.ec',
    '--java-options', '-Dfile.encoding=UTF-8'
    '--add-launcher', "Uninstall iNTAKE=$uninstallLauncherProperties"
)

$portableArchive = Join-Path $releaseDirectory ("iNTAKE-Portable-$releaseLabel.zip")
Compress-Archive -LiteralPath $applicationImage -DestinationPath $portableArchive -CompressionLevel Optimal

Write-Host '[iNTAKE] Creating Windows Setup executable...' -ForegroundColor Cyan
Invoke-Checked $jpackage @(
    '--type', 'exe',
    '--dest', $releaseDirectory,
    '--app-image', $applicationImage,
    '--name', 'iNTAKE',
    '--app-version', $version,
    '--vendor', 'iNSRT',
    '--description', 'iNTAKE Media NetCrawler',
    '--win-per-user-install',
    '--win-dir-chooser',
    '--win-menu',
    '--win-menu-group', 'iNTAKE',
    '--win-shortcut',
    '--win-upgrade-uuid', $upgradeUuid
)

$generatedInstaller = Get-ChildItem -LiteralPath $releaseDirectory -Filter '*.exe' -File |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $generatedInstaller) {
    throw 'jpackage did not produce a Windows installer.'
}
$setupPath = Join-Path $releaseDirectory ("iNTAKE-Setup-$releaseLabel.exe")
Move-Item -LiteralPath $generatedInstaller.FullName -Destination $setupPath -Force

$extensionArchive = Join-Path $releaseDirectory ("iNTAKE-Extension-$extensionVersion.zip")
Compress-Archive -LiteralPath (Join-Path $projectRoot 'browser-extension') `
    -DestinationPath $extensionArchive -CompressionLevel Optimal
Copy-Item -LiteralPath (Join-Path $projectRoot 'release\RELEASE-README.txt') `
    -Destination (Join-Path $releaseDirectory 'README.txt')
Copy-Item -LiteralPath (Join-Path $projectRoot 'release\THIRD-PARTY-NOTICES.txt') `
    -Destination (Join-Path $releaseDirectory 'THIRD-PARTY-NOTICES.txt')

$hashFiles = Get-ChildItem -LiteralPath $releaseDirectory -File |
    Where-Object Extension -In @('.exe', '.zip') |
    Sort-Object Name
$hashLines = foreach ($file in $hashFiles) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash *$($file.Name)"
}
[IO.File]::WriteAllLines(
    (Join-Path $releaseDirectory 'SHA256SUMS.txt'),
    $hashLines,
    [Text.UTF8Encoding]::new($false))

$safeStagingDirectory = Assert-ProjectChild $stagingDirectory
Remove-Item -LiteralPath $safeStagingDirectory -Recurse -Force

Write-Host "[iNTAKE] Release completed: $releaseDirectory" -ForegroundColor Green
Get-ChildItem -LiteralPath $releaseDirectory -File | Select-Object Name, Length
