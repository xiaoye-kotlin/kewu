# Kewu: deploy the unified backend (8080 business + former 8081 recording/AI) on ONE port 8080.
# Safe order: smoke-test the new jar on spare port 8090 first -> only if healthy, retire 8081
# and switch 8080 to the new jar -> verify -> auto-rollback (bring 8081 back) on failure.
# Output: logs\deploy-unified-output.txt. Run as Administrator. Pure ASCII on purpose.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory $logDir | Out-Null }
$out = Join-Path $logDir 'deploy-unified-output.txt'
$newJar = Join-Path $root 'kewu-8080-unified.jar'
$liveJar = Join-Path $root 'kewu-8080.jar'
$java = Join-Path $root 'jdk21\bin\java.exe'
Set-Content -Path $out -Value '' -Encoding utf8

function Log([string]$m) {
    $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $m
    Write-Host $line
    Add-Content -Path $out -Value $line -Encoding utf8
}

function Probe([string]$url) {
    try {
        $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 10
        $b = [string]$r.Content
        return @{ ok = $true; code = [int]$r.StatusCode; body = $b.Substring(0, [Math]::Min(160, $b.Length)) }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ ok = $false; code = $code; body = $_.Exception.Message }
    }
}

function WaitHealth([int]$port, [int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i += 3) {
        $p = Probe "http://127.0.0.1:$port/api/health"
        if ($p.ok) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function KillPort([int]$port) {
    Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
            Log "  killing pid $_ on port $port"
            Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        }
}

# Same environment start-8080-service.bat builds (kewu.env = OBS keys, maas.env = AI overrides)
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

function Rollback([string]$why) {
    Log "ROLLBACK: $why"
    schtasks /change /tn KewuBackend8081 /enable | Out-Null
    schtasks /run /tn KewuBackend8081 | Out-Null
    Log '  8081 task re-enabled and started again (old two-port setup restored for recording/AI)'
    Log '  8080 business backend state unchanged from before (it was already down).'
}

# ---------------------------------------------------------------- 0. checks
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Log '[FAIL] not Administrator. Right-click -> Run as administrator.'; Read-Host 'Enter to close'; exit 1
}
if (-not (Test-Path $newJar)) { Log "[FAIL] $newJar not found (upload it first)"; Read-Host 'Enter to close'; exit 1 }
if (-not (Test-Path $java)) { Log "[FAIL] $java not found"; Read-Host 'Enter to close'; exit 1 }
Log ('new jar: ' + (Get-Item $newJar).Length + ' bytes, sha256 ' + (Get-FileHash $newJar).Hash.Substring(0, 16))
$os = Get-CimInstance Win32_OperatingSystem
Log ('RAM free before: ' + [int]($os.FreePhysicalMemory / 1KB) + 'MB')

# ---------------------------------------------------------------- 1. smoke test on 8090
Log '[1/4] smoke test: new jar on port 8090 (live services untouched)'
LoadEnv
[Environment]::SetEnvironmentVariable('PORT', '8090', 'Process')
KillPort 8090
$smokeLog = Join-Path $logDir 'unified-smoke.log'
$smokeErr = Join-Path $logDir 'unified-smoke.err.log'
$proc = Start-Process -FilePath $java -ArgumentList @('-Xmx384m', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8',
    '-Djava.net.useSystemProxies=false', '-jar', "`"$newJar`"") -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $smokeLog -RedirectStandardError $smokeErr
$smokeOk = WaitHealth 8090 120
$checks = @('/api/health', '/health', '/api/courses', '/api/notes/deploy-probe', '/api/teacher/recording/devices?sessionId=deploy-probe')
foreach ($c in $checks) {
    $p = Probe ("http://127.0.0.1:8090" + $c)
    Log ("  8090$c -> " + $p.code + '  ' + ($p.body -replace "`r?`n", ' '))
}
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
if (-not $smokeOk) {
    Log '[FAIL] new jar did not become healthy on 8090. Nothing was changed. Last log lines:'
    if (Test-Path $smokeLog) { Get-Content $smokeLog -Tail 40 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 } }
    if (Test-Path $smokeErr) { Get-Content $smokeErr -Tail 20 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 } }
    Read-Host 'Enter to close'; exit 1
}
Log '  smoke test passed'

# ---------------------------------------------------------------- 2. retire 8081
Log '[2/4] retiring the separate 8081 service (its routes now live in 8080)'
schtasks /change /tn KewuBackend8081 /disable | Out-Null
KillPort 8081

# ---------------------------------------------------------------- 3. switch 8080 jar
Log '[3/4] switching 8080 to the unified jar'
KillPort 8080
$bak = Join-Path $root ('kewu-8080.jar.bak-' + (Get-Date -Format 'yyyyMMdd-HHmm'))
if (Test-Path $liveJar) { Copy-Item $liveJar $bak -Force; Log "  old jar backed up to $bak" }
Copy-Item $newJar $liveJar -Force
schtasks /run /tn KewuBackend8080 | Out-Null
if (-not (WaitHealth 8080 150)) {
    Log '[FAIL] 8080 did not come up with the new jar. Tail of server8080.log:'
    Get-Content (Join-Path $logDir 'server8080.log') -Tail 40 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 }
    Rollback '8080 not healthy'
    Read-Host 'Enter to close'; exit 1
}

# ---------------------------------------------------------------- 4. verify
Log '[4/4] verify on 8080 (local) - every former 8081 route must answer here now'
foreach ($c in $checks) {
    $p = Probe ("http://127.0.0.1:8080" + $c)
    Log ("  8080$c -> " + $p.code + '  ' + ($p.body -replace "`r?`n", ' '))
}
try {
    $body = '{"question":"reply with the single word OK","context":"","maxTokens":512}'
    $r = Invoke-WebRequest 'http://127.0.0.1:8080/api/ai/answer' -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 120
    Log ('  8080/api/ai/answer -> ' + $r.StatusCode + '  ' + ([string]$r.Content -replace "`r?`n", ' ').Substring(0, [Math]::Min(160, ([string]$r.Content).Length)))
} catch { Log ('  8080/api/ai/answer -> FAIL ' + $_.Exception.Message) }
$l8081 = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
Log ('  port 8081 still listening: ' + [bool]$l8081)
$os = Get-CimInstance Win32_OperatingSystem
Log ('RAM free after: ' + [int]($os.FreePhysicalMemory / 1KB) + 'MB')
Log 'DONE: single backend on 8080. 8081 task disabled (re-enable: schtasks /change /tn KewuBackend8081 /enable).'
Read-Host 'Enter to close'
