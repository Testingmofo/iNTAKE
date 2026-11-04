[CmdletBinding()]
param(
    [switch]$DependencyUpdate,
    [switch]$Silent
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$launcherPath = Join-Path $projectRoot 'Launch iNTAKE.cmd'
$dependencyStamp = Join-Path $projectRoot '.intake-dependencies.checked'
$requiredPackages = @('temurin17', 'maven', 'yt-dlp', 'ffmpeg', 'deno')
$successfulChocolateyExitCodes = @(0, 2, 1605, 1614, 1641, 3010)
$nativeHostName = 'com.insrt.intake'
$extensionId = 'omgodbpfilkfdihjffinlknijmdpogbn'

function Write-Stage {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "`n[iNTAKE] $Message" -ForegroundColor Cyan
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Refresh-ProcessEnvironment {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = @($machinePath, $userPath) -join ';'
    $machineChocolatey = [Environment]::GetEnvironmentVariable('ChocolateyInstall', 'Machine')
    if (-not [string]::IsNullOrWhiteSpace($machineChocolatey)) {
        $env:ChocolateyInstall = $machineChocolatey
    }
}

function Install-ChocolateyIfMissing {
    if (Get-Command choco.exe -ErrorAction SilentlyContinue) {
        return
    }

    Write-Stage 'Chocolatey was not found. Installing the official Chocolatey CLI...'
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $webClient = [Net.WebClient]::new()
    try {
        $installScript = $webClient.DownloadString('https://community.chocolatey.org/install.ps1')
    }
    finally {
        $webClient.Dispose()
    }
    Invoke-Expression $installScript
    Refresh-ProcessEnvironment

    if (-not (Get-Command choco.exe -ErrorAction SilentlyContinue)) {
        throw 'Chocolatey installation completed, but choco.exe is not available.'
    }
}

function Invoke-Chocolatey {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $chocolatey = Get-Command choco.exe -ErrorAction Stop
    & $chocolatey.Source @Arguments
    $exitCode = $LASTEXITCODE
    if ($successfulChocolateyExitCodes -notcontains $exitCode) {
        throw "Chocolatey failed with exit code $exitCode while running: choco $($Arguments -join ' ')"
    }
}

function Invoke-DependencyMaintenance {
    if (-not (Test-Administrator)) {
        throw 'Dependency maintenance requires an Administrator session.'
    }

    Install-ChocolateyIfMissing
    Write-Stage 'Updating Chocolatey...'
    Invoke-Chocolatey -Arguments @('upgrade', 'chocolatey', '-y', '--no-progress')

    Write-Stage 'Installing or updating Java, Maven, yt-dlp, FFmpeg, and Deno...'
    Invoke-Chocolatey -Arguments @(
        'upgrade'
        'temurin17'
        'maven'
        'yt-dlp'
        'ffmpeg'
        'deno'
        '-y'
        '--no-progress'
        '--stop-on-first-failure'
    )
}

function Invoke-ElevatedDependencyMaintenance {
    Write-Stage 'Windows will request Administrator permission for dependency maintenance.'
    $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -DependencyUpdate"
    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Dependency setup failed or was cancelled (exit code $($process.ExitCode))."
    }
    Refresh-ProcessEnvironment
    Set-Content -LiteralPath $dependencyStamp -Value ([DateTimeOffset]::Now.ToString('O')) -Encoding ASCII
}

function Get-JavaMajorVersion {
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $java) {
        return 0
    }

    # java -version intentionally writes normal version information to stderr.
    # Capturing it through PowerShell's error stream can become a terminating
    # NativeCommandError/RemoteException when ErrorActionPreference is Stop.
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $java.Source
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            return 0
        }
        $versionOutput = $process.StandardError.ReadToEnd() + "`n" +
            $process.StandardOutput.ReadToEnd()
        $process.WaitForExit()
    }
    finally {
        $process.Dispose()
    }

    $versionLine = ($versionOutput -split "`r?`n" |
        Where-Object { $_ -match 'version\s+"' } |
        Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($versionLine)) {
        return 0
    }
    if ($versionLine -notmatch 'version\s+"(?<major>\d+)(?:\.(?<minor>\d+))?') {
        return 0
    }
    $major = [int]$Matches.major
    if ($major -eq 1 -and $Matches.minor) {
        return [int]$Matches.minor
    }
    return $major
}

function Test-CommandsAvailable {
    if ((Get-JavaMajorVersion) -lt 17) {
        return $false
    }
    foreach ($command in @('mvn.cmd', 'yt-dlp.exe', 'ffmpeg.exe', 'deno.exe')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
            return $false
        }
    }
    return $true
}

function Test-ChocolateyUpdatesAvailable {
    $chocolatey = Get-Command choco.exe -ErrorAction SilentlyContinue
    if (-not $chocolatey) {
        return $true
    }

    Write-Stage 'Checking Chocolatey packages for updates...'
    $output = @(& $chocolatey.Source outdated --limit-output 2>$null)
    $exitCode = $LASTEXITCODE
    if ($successfulChocolateyExitCodes -notcontains $exitCode) {
        Write-Warning "Chocolatey update check returned exit code $exitCode; maintenance will run to be safe."
        return $true
    }

    $trackedPackages = @('chocolatey') + $requiredPackages
    foreach ($line in $output) {
        $packageName = ($line -split '\|', 2)[0]
        if ($trackedPackages -contains $packageName) {
            Write-Host "[iNTAKE] Update available: $line" -ForegroundColor Yellow
            return $true
        }
    }
    return $false
}

function Set-JavaEnvironment {
    $adoptiumRoot = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (-not (Test-Path -LiteralPath $adoptiumRoot)) {
        return
    }
    $jdk = Get-ChildItem -LiteralPath $adoptiumRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending |
        Select-Object -First 1
    if ($jdk) {
        $env:JAVA_HOME = $jdk.FullName
        $env:Path = (Join-Path $jdk.FullName 'bin') + ';' + $env:Path
    }
}

function New-DesktopShortcut {
    if (-not (Test-Path -LiteralPath $launcherPath)) {
        return
    }
    $desktop = [Environment]::GetFolderPath([Environment+SpecialFolder]::DesktopDirectory)
    if ([string]::IsNullOrWhiteSpace($desktop)) {
        return
    }

    $shortcutPath = Join-Path $desktop 'iNTAKE.lnk'
    $shortcut = $null
    $shell = New-Object -ComObject WScript.Shell
    try {
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $launcherPath
        $shortcut.WorkingDirectory = $projectRoot
        $shortcut.Description = 'Launch iNTAKE Media NetCrawler'
        $shortcut.WindowStyle = 1
        $shortcut.Save()
    }
    finally {
        if ($shortcut) {
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut)
        }
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
    }
}

function Install-NativeMessagingHost {
    $hostDirectory = Join-Path $projectRoot 'native-host'
    $sourcePath = Join-Path $hostDirectory 'IntakeNativeHost.cs'
    $executablePath = Join-Path $hostDirectory 'iNTAKE.NativeHost.exe'
    $manifestPath = Join-Path $hostDirectory ($nativeHostName + '.json')
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Native messaging source is missing: $sourcePath"
    }

    $requiresBuild = -not (Test-Path -LiteralPath $executablePath -PathType Leaf)
    if (-not $requiresBuild) {
        $requiresBuild = (Get-Item -LiteralPath $sourcePath).LastWriteTimeUtc -gt
            (Get-Item -LiteralPath $executablePath).LastWriteTimeUtc
    }
    if ($requiresBuild) {
        Write-Stage 'Building the browser wake-up bridge...'
        if (Test-Path -LiteralPath $executablePath) {
            Remove-Item -LiteralPath $executablePath -Force
        }
        Add-Type -Path $sourcePath `
            -OutputAssembly $executablePath `
            -OutputType ConsoleApplication `
            -ReferencedAssemblies 'System.Web.Extensions.dll'
    }

    $manifest = [ordered]@{
        name = $nativeHostName
        description = 'Starts iNTAKE and forwards browser download requests.'
        path = $executablePath
        type = 'stdio'
        allowed_origins = @("chrome-extension://$extensionId/")
    } | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText(
        $manifestPath,
        $manifest,
        [Text.UTF8Encoding]::new($false))

    foreach ($registryPath in @(
        "HKCU:\Software\Google\Chrome\NativeMessagingHosts\$nativeHostName",
        "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\$nativeHostName"
    )) {
        [void](New-Item -Path $registryPath -Force)
        Set-Item -Path $registryPath -Value $manifestPath
    }
}

function Test-iNTAKEAlreadyRunning {
    try {
        return [bool](Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 17845 -State Listen `
            -ErrorAction SilentlyContinue)
    }
    catch {
        return $false
    }
}

if ($DependencyUpdate) {
    try {
        Invoke-DependencyMaintenance
        exit 0
    }
    catch {
        Write-Host "`n[iNTAKE] DEPENDENCY SETUP FAILED" -ForegroundColor Red
        Write-Host $_.Exception.ToString() -ForegroundColor Red
        Read-Host 'Press Enter to close this window'
        exit 1
    }
}

try {
    Set-Location -LiteralPath $projectRoot
    Refresh-ProcessEnvironment

    if ($Silent) {
        if (-not (Test-CommandsAvailable)) {
            throw 'Silent startup requires initial setup. Run Launch iNTAKE.cmd manually once.'
        }
    }
    else {
        $needsMaintenance = -not (Test-Path -LiteralPath $dependencyStamp)
        if (-not (Test-CommandsAvailable)) {
            $needsMaintenance = $true
        }
        if (Test-ChocolateyUpdatesAvailable) {
            $needsMaintenance = $true
        }
        if ($needsMaintenance) {
            Invoke-ElevatedDependencyMaintenance
        }
    }

    Refresh-ProcessEnvironment
    Set-JavaEnvironment
    if (-not (Test-CommandsAvailable)) {
        throw 'One or more required commands are still unavailable after setup.'
    }

    if (-not $Silent) {
        Install-NativeMessagingHost
        New-DesktopShortcut
    }
    if (Test-iNTAKEAlreadyRunning) {
        Write-Host '[iNTAKE] iNTAKE is already running in the system tray.' -ForegroundColor Yellow
        exit 0
    }

    Write-Stage 'Building the application and resolving JavaFX dependencies...'
    $maven = Get-Command mvn.cmd -ErrorAction Stop
    & $maven.Source -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }

    Write-Stage 'Launching iNTAKE...'
    $standardOutput = Join-Path $projectRoot 'launcher-output.log'
    $standardError = Join-Path $projectRoot 'launcher-error.log'
    $mavenRunCommand = if ($Silent) {
        'mvn.cmd -q -DskipTests -Djavafx.args=-silent javafx:run'
    }
    else {
        'mvn.cmd -q -DskipTests javafx:run'
    }
    Start-Process -FilePath $env:ComSpec `
        -ArgumentList @('/d', '/c', $mavenRunCommand) `
        -WorkingDirectory $projectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $standardOutput `
        -RedirectStandardError $standardError
    exit 0
}
catch {
    Write-Host "`n[iNTAKE] LAUNCH FAILED" -ForegroundColor Red
    Write-Host $_.Exception.ToString() -ForegroundColor Red
    exit 1
}
