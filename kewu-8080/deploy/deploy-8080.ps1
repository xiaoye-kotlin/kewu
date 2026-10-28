# Kewu: deploy a new build of the single backend on port 8080.
# Upload the new jar as kewu-8080-new.jar next to this script first (FTP).
# Order: smoke test on spare port 8090 -> swap jar on 8080 -> verify -> on failure restore the previous jar.
# Output: logs\deploy-8080-output.txt. Run as Administrator. Pure ASCII on purpose.
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

function Log([string]$m) {
    $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $m
    Write-Host $line
    Add-Content -Path $out -Value $line -Encoding utf8
}

function Probe([string]$url) {
    try {
        $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 10
        $b = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return @{ ok = $true; code = [int]$r.StatusCode; body = $b.Substring(0, [Math]::Min(160, $b.Length)) }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ ok = $false; code = $code; body = $_.Exception.Message }
    }
}

function WaitHealth([int]$port, [int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i += 3) {
        if ((Probe "http://127.0.0.1:$port/api/health").ok) { return $true }
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

# Upload a tiny text file through /api/uploads and check the returned url opens publicly
function UploadTest([int]$port) {
    $tmp = Join-Path $env:TEMP 'kewu-upload-probe.txt'
    Set-Content -Path $tmp -Value 'kewu upload probe' -Encoding ascii
    $resp = & curl.exe -s --max-time 60 -F "kind=FILE" -F "uploader=deploy-probe" -F "file=@$tmp;type=text/plain" "http://127.0.0.1:$port/api/uploads"
    Log ("  $port/api/uploads -> " + ([string]$resp -replace "`r?`n", ' ').Substring(0, [Math]::Min(300, ([string]$resp).Length)))
    if ($resp -match '"url"\s*:\s*"([^"]+)"') {
        $u = $Matches[1]
        $p = Probe $u
        Log ("  uploaded url public GET -> " + $p.code + '  ' + $p.body)
    }
}

# ---------------------------------------------------------------- 0. checks
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Log '[FAIL] not Administrator. Right-click -> Run as administrator.'; Read-Host 'Enter to close'; exit 1
}
if (-not (Test-Path $newJar)) { Log "[FAIL] $newJar not found (upload it first)"; Read-Host 'Enter to close'; exit 1 }
Log ('new jar: ' + (Get-Item $newJar).Length + ' bytes, sha256 ' + (Get-FileHash $newJar).Hash.Substring(0, 16))
$checks = @('/api/health', '/health', '/api/courses', '/api/courses/deploy-probe/materials', '/api/teacher/recording/devices?sessionId=deploy-probe')

# ---------------------------------------------------------------- 1. smoke test on 8090
Log '[1/3] smoke test: new jar on port 8090 (live 8080 untouched)'
LoadEnv
[Environment]::SetEnvironmentVariable('PORT', '8090', 'Process')
KillPort 8090
$smokeLog = Join-Path $logDir 'smoke-8090.log'
$smokeErr = Join-Path $logDir 'smoke-8090.err.log'
$proc = Start-Process -FilePath $java -ArgumentList @('-Xmx384m', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8',
    '-Djava.net.useSystemProxies=false', '-jar', "`"$newJar`"") -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $smokeLog -RedirectStandardError $smokeErr
$smokeOk = WaitHealth 8090 120
foreach ($c in $checks) {
    $p = Probe ("http://127.0.0.1:8090" + $c)
    Log ("  8090$c -> " + $p.code + '  ' + ($p.body -replace "`r?`n", ' '))
}
if ($smokeOk) { UploadTest 8090 }
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
if (-not $smokeOk) {
    Log '[FAIL] new jar did not become healthy on 8090. Nothing was changed. Last log lines:'
    if (Test-Path $smokeLog) { Get-Content $smokeLog -Tail 40 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 } }
    if (Test-Path $smokeErr) { Get-Content $smokeErr -Tail 20 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 } }
    Read-Host 'Enter to close'; exit 1
}
Log '  smoke test passed'

# ---------------------------------------------------------------- 2. swap
Log '[2/3] switching 8080 to the new jar'
$bak = Join-Path $root ('kewu-8080.jar.bak-' + (Get-Date -Format 'yyyyMMdd-HHmm'))
KillPort 8080
Start-Sleep -Seconds 2
Copy-Item $liveJar $bak -Force
Log "  previous jar backed up to $bak"
Copy-Item $newJar $liveJar -Force
schtasks /run /tn KewuBackend8080 | Out-Null
if (-not (WaitHealth 8080 150)) {
    Log '[FAIL] 8080 not healthy with the new jar -> restoring the previous jar'
    Get-Content (Join-Path $logDir 'server8080.log') -Tail 40 | ForEach-Object { Add-Content -Path $out -Value $_ -Encoding utf8 }
    KillPort 8080
    Start-Sleep -Seconds 2
    Copy-Item $bak $liveJar -Force
    schtasks /run /tn KewuBackend8080 | Out-Null
    $back = WaitHealth 8080 150
    Log ('  previous jar restored, healthy=' + $back)
    Read-Host 'Enter to close'; exit 1
}

# ---------------------------------------------------------------- 3. verify
Log '[3/3] verify on 8080'
foreach ($c in $checks) {
    $p = Probe ("http://127.0.0.1:8080" + $c)
    Log ("  8080$c -> " + $p.code + '  ' + ($p.body -replace "`r?`n", ' '))
}
UploadTest 8080
$os = Get-CimInstance Win32_OperatingSystem
Log ('RAM free: ' + [int]($os.FreePhysicalMemory / 1KB) + 'MB')
Log 'DONE'
Read-Host 'Enter to close'
