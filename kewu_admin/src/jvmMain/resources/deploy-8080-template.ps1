# Kewu: deploy a new build of the single backend on port 8080 (v2).
# Pure ASCII on purpose. Run as Administrator (normally invoked by kewu_admin).
# v2: wall-clock health wait, disable autostart task during swap, start backend directly,
#     auto-recover MySQL, freeze stale backend/watchdog processes before overwriting the jar.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory $logDir | Out-Null }
$out = Join-Path $logDir 'deploy-8080-output.txt'
$newJar = Join-Path $root 'kewu-8080-new.jar'
$liveJar = Join-Path $root 'kewu-8080.jar'
$java = Join-Path $root 'jdk21\bin\java.exe'
Set-Content -Path $out -Value '' -Encoding utf8
# Recover from a previous interrupted run that may have left the task disabled.
schtasks /change /tn KewuBackend8080 /enable 2>$null | Out-Null

function Log([string]$m) {
    $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $m
    Write-Host $line
    Add-Content -Path $out -Value $line -Encoding utf8
}

function Probe([string]$url) {
    try {
        $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 8
        $b = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return @{ ok = $true; code = [int]$r.StatusCode; body = $b.Substring(0, [Math]::Min(160, $b.Length)) }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ ok = $false; code = $code; body = $_.Exception.Message }
    }
}

# Real wall-clock deadline (the old loop counted iterations; slow probes stretched it 3x).
function WaitHealth([int]$port, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Probe "http://127.0.0.1:$port/api/health").ok) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function PortBusy([int]$port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function KillPort([int]$port) {
    Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
            Log "  killing pid $_ on port $port"
            Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        }
}

# Stop the scheduled task and every process that could auto-restart the backend while
# the jar is being overwritten (overwrite-during-read causes NoClassDefFoundError).
function FreezeBackend {
    schtasks /change /tn KewuBackend8080 /disable 2>$null | Out-Null
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -and (
            ($_.Name -eq 'java.exe' -and $_.CommandLine -match 'kewu-8080') -or
            ($_.Name -in @('cmd.exe','wscript.exe','powershell.exe') -and $_.CommandLine -match 'start_bg_8080|start-8080-service')
        )
    }
    foreach ($p in $procs) {
        Log ("  freezing pid " + $p.ProcessId + " (" + $p.Name + ")")
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
    KillPort 8090
    KillPort 8080
    Start-Sleep -Seconds 2
}

function LoadEnv {
    foreach ($f in 'kewu.env', 'maas.env') {
        $path = Join-Path $root $f
        if (Test-Path $path) {
            Get-Content $path | ForEach-Object {
                $kv = $_ -split '=', 2
                if ($kv.Count -eq 2 -and $kv[0].Trim() -ne '') { [Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process') }
            }
        }
    }
    $defaults = @{
        'JWT_SECRET' = 'kewu-prod-secret-2026'
        'KEWU_DB_URL' = 'jdbc:mysql://127.0.0.1:3306/kewu?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true'
        'KEWU_DB_USER' = 'kewu'; 'KEWU_DB_PASSWORD' = 'Kewu-DB-2026'
        'OBS_ENDPOINT' = 'https://obs.cn-north-4.myhuaweicloud.com'; 'OBS_BUCKET' = 'kewu2'
        'OBS_REGION' = 'cn-north-4'; 'STORAGE_MODE' = 'obs'
        'KEWU_REDIS_HOST' = '127.0.0.1'; 'KEWU_REDIS_PORT' = '6379'
    }
    foreach ($k in $defaults.Keys) {
        if (-not [Environment]::GetEnvironmentVariable($k, 'Process')) { [Environment]::SetEnvironmentVariable($k, $defaults[$k], 'Process') }
    }
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $null, 'Process')
    [Environment]::SetEnvironmentVariable('_JAVA_OPTIONS', $null, 'Process')
}

# Start the backend ourselves (not via the scheduled task) so the deploy can watch it.
function StartBackend([int]$port) {
    [Environment]::SetEnvironmentVariable('PORT', "$port", 'Process')
    $stdout = Join-Path $logDir ("backend-$port.out.log")
    $stderr = Join-Path $logDir ("backend-$port.err.log")
    $p = Start-Process -FilePath $java -ArgumentList @(
        '-Xmx512m', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8',
        '-Djava.net.useSystemProxies=false', '-jar', "`"$liveJar`""
    ) -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Log ("  started backend pid " + $p.Id + " on port $port")
    return $p
}

function EnsureMySQL {
    if (PortBusy 3306) { Log '  MySQL 3306 is listening'; return $true }
    Log '  MySQL 3306 down, trying to start the MySQL service...'
    $svc = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'mysql|KewuMysql' -or $_.DisplayName -match 'mysql' } | Select-Object -First 1
    if ($svc) { Start-Service -Name $svc.Name -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 20; $i++) {
        if (PortBusy 3306) { Log '  MySQL recovered'; return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Log '[FAIL] not Administrator.'; exit 1
}
if (-not (Test-Path $newJar)) { Log "[FAIL] $newJar not found"; exit 1 }
if (-not (Test-Path $java)) { Log "[FAIL] $java not found"; exit 1 }
Log ('new jar: ' + (Get-Item $newJar).Length + ' bytes, sha256 ' + (Get-FileHash $newJar).Hash.Substring(0, 16))

$taskDisabled = $false
try {
    # ---------------- 0. database must be up BEFORE touching the live service
    Log '[0/3] preflight: MySQL / Redis'
    if (-not (EnsureMySQL)) {
        Log '[FAIL] MySQL is not reachable on 3306. Live backend was NOT touched. Start MySQL and retry.'
        exit 1
    }
    if (-not (PortBusy 6379)) { Log '  [WARN] Redis 6379 not listening (radar/AI status may be degraded)' }

    # ---------------- 1. smoke test on 8090 (live 8080 untouched)
    Log '[1/3] smoke test: new jar on port 8090'
    LoadEnv
    FreezeBackend
    $taskDisabled = $true
    $smokeOut = Join-Path $logDir 'smoke-8090.log'
    $smokeErr = Join-Path $logDir 'smoke-8090.err.log'
    [Environment]::SetEnvironmentVariable('PORT', '8090', 'Process')
    $smokeProc = Start-Process -FilePath $java -ArgumentList @(
        '-Xmx384m', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8',
        '-Djava.net.useSystemProxies=false', '-jar', "`"$newJar`""
    ) -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $smokeOut -RedirectStandardError $smokeErr
    $smokeOk = WaitHealth 8090 300
    foreach ($c in @('/api/health', '/health', '/api/courses')) {
        $p = Probe ("http://127.0.0.1:8090" + $c)
        Log ("  8090$c -> " + $p.code + '  ' + ($p.body -replace "`r?`n", ' '))
    }
    Stop-Process -Id $smokeProc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    if (-not $smokeOk) {
        Log '[FAIL] new jar not healthy on 8090. Nothing was changed.'
        if (Test-Path $smokeErr) { Get-Content $smokeErr -Tail 25 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 } }
        exit 1
    }
    Log '  smoke test passed'

    # ---------------- 2. swap + direct start + verify
    Log '[2/3] swapping 8080 to the new jar'
    FreezeBackend
    $bak = Join-Path $root ('kewu-8080.jar.bak-' + (Get-Date -Format 'yyyyMMdd-HHmm'))
    if (Test-Path $liveJar) { Copy-Item $liveJar $bak -Force }
    Copy-Item $newJar $liveJar -Force
    Log '  jar swapped'
    $p8080 = StartBackend 8080
    $ok8080 = WaitHealth 8080 900
    if (-not $ok8080) {
        Log '[FAIL] 8080 not healthy with the new jar -> restoring previous jar'
        Stop-Process -Id $p8080.Id -Force -ErrorAction SilentlyContinue
        FreezeBackend
        if (Test-Path $bak) { Copy-Item $bak $liveJar -Force }
        $pBack = StartBackend 8080
        $back = WaitHealth 8080 900
        Log ('  previous jar restored, healthy=' + $back)
        exit 1
    }
    Log '  new jar healthy on 8080'

    # ---------------- 3. verify
    Log '[3/3] verify on 8080'
    foreach ($c in @('/api/health', '/health', '/api/courses')) {
        $p = Probe ("http://127.0.0.1:8080" + $c)
        Log ("  8080$c -> " + $p.code + '  ' + ($p.body -replace "`r?`n", ' '))
    }
    Log 'DONE'
    exit 0
} finally {
    if ($taskDisabled) {
        schtasks /change /tn KewuBackend8080 /enable 2>$null | Out-Null
        Log '  autostart task KewuBackend8080 re-enabled'
    }
}
