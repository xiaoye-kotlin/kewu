param(
    [Parameter(Mandatory=$true)][string]$ServerRoot,
    [Parameter(Mandatory=$true)][string]$ZipUrl
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Log([string]$message) {
    Write-Host ((Get-Date -Format 'HH:mm:ss') + '  ' + $message)
}

try {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'One-click deployment requires Administrator privileges.'
    }

    $root = (Resolve-Path $ServerRoot).Path
    Log "Server root: $root"
    $deployPs1 = Join-Path $root 'deploy-8080.ps1'
    if (-not (Test-Path $deployPs1)) { throw "deploy-8080.ps1 not found in $root" }

    $work = Join-Path $root 'admin-deploy'
    $extractRoot = Join-Path $work 'source'
    $zipPath = Join-Path $work 'kewu-backend-source.zip'
    New-Item -ItemType Directory -Force $work, $extractRoot | Out-Null

    Log '[1/4] Downloading backend source from GitHub...'
    Invoke-WebRequest -UseBasicParsing -Uri $ZipUrl -OutFile $zipPath -TimeoutSec 300
    Log ("  downloaded " + (Get-Item $zipPath).Length + ' bytes')
    if (Test-Path $extractRoot) { Remove-Item $extractRoot -Recurse -Force }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractRoot -Force
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue

    $sourceDir = Get-ChildItem -Path $extractRoot -Directory | ForEach-Object { Join-Path $_.FullName 'kewu-8080' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $sourceDir) { throw 'kewu-8080 source directory not found in GitHub archive' }
    Log "  source: $sourceDir"

    $javaHome = Join-Path $root 'jdk21'
    if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
        $javaHome = $env:JAVA_HOME
    }
    if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
        throw 'Java not found. Expected $ServerRoot\jdk21 or JAVA_HOME.'
    }
    $gradlew = Join-Path $sourceDir 'gradlew.bat'
    if (-not (Test-Path $gradlew)) { throw "Gradle wrapper not found: $gradlew" }

    Log '[2/4] Building kewu-8080 shadowJar on server...'
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    Push-Location $sourceDir
    & cmd.exe /c "`"$gradlew`" --no-daemon clean shadowJar"
    $buildCode = $LASTEXITCODE
    Pop-Location
    if ($buildCode -ne 0) { throw "Gradle shadowJar failed with exit code $buildCode" }

    $builtJar = Join-Path $sourceDir 'build\libs\kewu-8080.jar'
    if (-not (Test-Path $builtJar)) { throw "Built jar not found: $builtJar" }
    Log ("  built " + (Get-Item $builtJar).Length + ' bytes')

    Log '[3/4] Preparing server deployment package...'
    $newJar = Join-Path $root 'kewu-8080-new.jar'
    Copy-Item $builtJar $newJar -Force
    Log "  copied to $newJar"

    Log '[4/4] Running safe deploy script (8090 smoke test, backup, swap, verify, rollback)...'
    $sanitizedDeploy = Join-Path $work 'deploy-8080-safe.ps1'
    $deploySource = Get-Content $deployPs1 -Raw
    # 原脚本按自身位置推导 $root；复制到临时目录执行会错根目录，显式锁定为服务器后端目录。
    $escapedRoot = $root.Replace("'", "''")
    $rootPattern = [regex]::Escape('$root = Split-Path -Parent $MyInvocation.MyCommand.Path')
    $deploySource = $deploySource -replace $rootPattern, "`$root = '$escapedRoot'"
    $deploySource = $deploySource -replace "Read-Host 'Enter to close'; exit 1", 'exit 1'
    $deploySource = $deploySource -replace "Read-Host 'Enter to close'", ''
    Set-Content -Path $sanitizedDeploy -Value $deploySource -Encoding UTF8
    $emptyInput = Join-Path $work 'empty-input.txt'
    Set-Content -Path $emptyInput -Value '' -Encoding ASCII
    $deployProcess = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$sanitizedDeploy`"") `
        -WorkingDirectory $root -Wait -PassThru -NoNewWindow -RedirectStandardInput $emptyInput
    if ($deployProcess.ExitCode -ne 0) {
        throw "deploy-8080.ps1 failed with exit code $($deployProcess.ExitCode)"
    }

    Log 'BACKEND DEPLOY DONE'
} catch {
    Log ('[FAIL] ' + $_.Exception.Message)
    exit 1
}
