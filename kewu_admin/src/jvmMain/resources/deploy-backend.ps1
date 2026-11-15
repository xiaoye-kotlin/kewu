param(
    [Parameter(Mandatory=$true)][string]$ServerRoot,
    [Parameter(Mandatory=$true)][string]$ZipUrl,
    [switch]$OnlineBuild,
    [string]$PrebuiltJarUrl = '',
    [string]$PrebuiltZipUrl = '',
    [string]$DeployTemplate = ''
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# 统一 UTF-8 输出：否则 .NET 本地化的异常消息按 GBK 写出，总控端按 UTF-8 读会乱码。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 文件日志：一键部署在 GUI 里跑时，SSH 无法看到控制台，所有输出同步落盘，
# 便于运维直接读 $root\logs\admin-deploy.log 定位问题。
$script:deployLog = $null
function Log([string]$message) {
    $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $message
    Write-Host $line
    if ($script:deployLog) {
        try { Add-Content -Path $script:deployLog -Value ((Get-Date -Format 'yyyy-MM-dd ') + $line) -Encoding UTF8 } catch { }
    }
}

# native exe 的 stderr 在 PS5.1 下会变成 ErrorRecord（$ErrorActionPreference='Stop' 时甚至是终止异常），
# 且 $LASTEXITCODE 在 2>&1 管道后不可靠。统一改走 Start-Process -Wait -PassThru 拿真实 ExitCode，
# stdout/stderr 各自重定向到临时文件，再把内容写进部署日志。
function Invoke-Native([string]$exe, [string[]]$argList, [string]$label) {
    $tmp = Join-Path $env:TEMP ('kewu-native-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $o = $tmp + '.out'; $e = $tmp + '.err'
    try {
        $p = Start-Process -FilePath $exe -ArgumentList $argList -Wait -PassThru `
            -WindowStyle Hidden -RedirectStandardOutput $o -RedirectStandardError $e
        $code = $p.ExitCode
        foreach ($f in @($o, $e)) {
            if (Test-Path $f) {
                $txt = (Get-Content $f -Raw -ErrorAction SilentlyContinue)
                if ($txt -and $txt.Trim()) { Log ('  [' + $label + '] ' + ($txt.Trim() -replace "`r?`n", ' | ')) }
            }
        }
        return $code
    } finally {
        Remove-Item $o, $e -Force -ErrorAction SilentlyContinue
    }
}

# 用 stdin 文件执行 .sql：SQL 里含空格、分号、单引号，走命令行参数会被拆坏。
function Invoke-MySql([string]$exe, [string]$sqlFile, [string[]]$argList, [string]$label) {
    $tmp = Join-Path $env:TEMP ('kewu-sql-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $o = $tmp + '.out'; $e = $tmp + '.err'
    try {
        $p = Start-Process -FilePath $exe -ArgumentList $argList -Wait -PassThru -WindowStyle Hidden `
            -RedirectStandardInput $sqlFile -RedirectStandardOutput $o -RedirectStandardError $e
        foreach ($f in @($o, $e)) {
            if (Test-Path $f) {
                $txt = Get-Content $f -Raw -ErrorAction SilentlyContinue
                if ($txt -and $txt.Trim()) { Log ('  [' + $label + '] ' + ($txt.Trim() -replace "`r?`n", ' | ')) }
            }
        }
        return $p.ExitCode
    } finally {
        Remove-Item $o, $e -Force -ErrorAction SilentlyContinue
    }
}

function Test-Tcp([int]$port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function New-RandomToken([int]$len) {
    $bytes = New-Object byte[] $len
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    -join ($bytes | ForEach-Object { $alphabet[$_ % 62] })
}

# 后端 DatabaseFactory.init() 用 HikariDataSource 构造即连库，MySQL 连不上进程直接崩，
# 是唯一硬依赖（Redis / OBS / MaaS / SIS 都是软依赖，连不上只降级）。
# 全新服务器首次部署时自动装一套本机免安装 MySQL 8.0 并建库，让「一键部署」真正一键可用。
function Ensure-MySQL([string]$root, [string]$work) {
    # 注意：不能因为 3306 已在监听就直接 return —— 那样会跳过「口令同步 / 建库 / 回写 kewu.env」，
    # 上一次部署中断后 env 里仍是 change-me，后端就会 Access denied。安装步骤可跳过，凭据步骤必须每次都做。
    $alreadyUp = Test-Tcp 3306
    if ($alreadyUp) { Log 'MySQL 3306 already listening (skip install, still sync credentials and database)' }

    $mysqlHome = Join-Path $root 'mysql'
    $mysqld = Join-Path $mysqlHome 'bin\mysqld.exe'
    $mysqlCli = Join-Path $mysqlHome 'bin\mysql.exe'
    $dataDir = Join-Path $mysqlHome 'data'
    $myIni = Join-Path $mysqlHome 'my.ini'
    $envPath = Join-Path $root 'kewu.env'
    $dbPass = ''

    if (-not $alreadyUp) {
        # MySQL 8.0 的 mysqld.exe 依赖 VC++ 2015-2022 运行库；精简版 Windows Server 常只带
        # *_clr0400.dll 而不带标准 vcruntime140.dll / msvcp140.dll，缺了会直接启动失败。
        $hasVc = (Test-Path (Join-Path $env:SystemRoot 'System32\vcruntime140.dll')) -and
                 (Test-Path (Join-Path $env:SystemRoot 'System32\msvcp140.dll'))
        if (-not $hasVc) {
            Log '[DB] VC++ runtime missing. Downloading VC++ 2015-2022 redistributable...'
            $vcExe = Join-Path $work 'vc_redist.x64.exe'
            Invoke-WebRequest -UseBasicParsing -Uri 'https://aka.ms/vs/17/release/vc_redist.x64.exe' -OutFile $vcExe -TimeoutSec 600
            $vcCode = Invoke-Native $vcExe @('/install', '/quiet', '/norestart') 'vc-redist'
            # 3010 = 安装成功但需重启，视为成功。
            if ($vcCode -ne 0 -and $vcCode -ne 3010) { Log ('  [WARN] VC++ redist returned ' + $vcCode + ', continuing anyway') }
            else { Log '  VC++ runtime installed' }
        }

        if (-not (Test-Path $mysqld)) {
            Log '[DB] No local MySQL found. Downloading MySQL 8.0 zip from Aliyun mirror...'
            $zip = Join-Path $work 'mysql-8.0.28-winx64.zip'
            Invoke-WebRequest -UseBasicParsing -Uri 'https://mirrors.aliyun.com/mysql/MySQL-8.0/mysql-8.0.28-winx64.zip' -OutFile $zip -TimeoutSec 1800
            Log ('  downloaded ' + (Get-Item $zip).Length + ' bytes, extracting...')
            $ext = Join-Path $work 'mysql-extract'
            if (Test-Path $ext) { Remove-Item $ext -Recurse -Force }
            Expand-Archive -LiteralPath $zip -DestinationPath $ext -Force
            $inner = Get-ChildItem $ext -Directory | Select-Object -First 1
            if (-not $inner) { throw 'MySQL zip extracted but no folder found inside.' }
            if (Test-Path $mysqlHome) { Remove-Item $mysqlHome -Recurse -Force }
            Move-Item $inner.FullName $mysqlHome
            Remove-Item $zip -Force -ErrorAction SilentlyContinue
            Log ('  MySQL unpacked to ' + $mysqlHome)
        }

        # my.ini 放在 basedir 根目录：mysqld 在 Windows 上会自动读取它。
        # 绝不要传 --defaults-file=... —— PS5.1 对该参数的引号处理会把它拼成
        # "my.ini --"，导致 "Failed to open required defaults file"。
        $iniLines = @(
            '[mysqld]',
            ('basedir=' + $mysqlHome.Replace('\','/')),
            ('datadir=' + $dataDir.Replace('\','/')),
            'port=3306',
            'bind-address=127.0.0.1',
            'default_authentication_plugin=mysql_native_password',
            'character-set-server=utf8mb4',
            'collation-server=utf8mb4_unicode_ci',
            'max_connections=200',
            'innodb_buffer_pool_size=128M',
            '[client]',
            'port=3306',
            'default-character-set=utf8mb4'
        )
        $iniLines | Set-Content -Path $myIni -Encoding ASCII

        if (-not (Test-Path (Join-Path $dataDir 'ibdata1'))) {
            Log '[DB] initializing MySQL data directory (this takes ~1 min)...'
            # --initialize 要求 data 目录为空；清掉上次中断留下的 auto.cnf / *.err 等残留。
            if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force }
            New-Item -ItemType Directory -Force $dataDir | Out-Null
            $code = Invoke-Native $mysqld @('--initialize-insecure', '--console') 'mysql-init'
            if ($code -ne 0 -or -not (Test-Path (Join-Path $dataDir 'ibdata1'))) {
                throw "mysqld --initialize-insecure failed (exit $code); see logs/admin-deploy.log"
            }
            Log '  data directory initialized (root has empty password for now)'
        }

        # 注册/启动 Windows 服务，保证重启后仍在。
        $svc = Get-Service -Name 'KewuMySQL' -ErrorAction SilentlyContinue
        if (-not $svc) {
            Log '[DB] registering Windows service KewuMySQL...'
            $c = Invoke-Native $mysqld @('--install', 'KewuMySQL') 'mysql-install'
            if ($c -ne 0) { Log ('  [WARN] service registration returned ' + $c) }
        }
        $svc = Get-Service -Name 'KewuMySQL' -ErrorAction SilentlyContinue
        if ($svc) {
            if ($svc.Status -ne 'Running') {
                Log '[DB] starting KewuMySQL service...'
                $c = Invoke-Native 'net' @('start', 'KewuMySQL') 'net-start'
                if ($c -ne 0) { Log ('  [WARN] net start returned ' + $c) }
            }
            Set-Service -Name 'KewuMySQL' -StartupType Automatic -ErrorAction SilentlyContinue
        }
        if (-not $svc) {
            # 服务不可用（精简系统）时退化为后台进程。
            Log '[DB] service unavailable, starting mysqld as background process...'
            Start-Process -FilePath $mysqld -ArgumentList @('--console') -WorkingDirectory $mysqlHome -WindowStyle Hidden | Out-Null
        }

        $ready = $false
        for ($i = 0; $i -lt 40; $i++) {
            if (Test-Tcp 3306) { $ready = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) {
            # 把 MySQL 自身的错误日志尾部贴进部署日志，避免盲猜。
            $errLogs = Get-ChildItem $dataDir -Filter '*.err' -ErrorAction SilentlyContinue
            foreach ($el in $errLogs) {
                Log ('  [mysql-error-log] ' + ((Get-Content $el.FullName -Tail 12) -replace "`r?`n", ' | '))
            }
            throw 'MySQL did not start listening on 3306 within 80s. Check logs/admin-deploy.log.'
        }
    }

    # 口令以独立文件为唯一真相源：上一次中断可能已把 MySQL 口令改掉但没回写 env，
    # 若每次重新随机生成，就会和 MySQL 里的实际口令永久对不上。
    $passFile = Join-Path $mysqlHome 'db-password.txt'
    if (Test-Path $passFile) {
        $saved = (Get-Content $passFile -Raw).Trim()
        if ($saved -and $saved -ne 'change-me') { $dbPass = $saved }
    }
    if (-not $dbPass -or $dbPass -eq 'change-me') { $dbPass = New-RandomToken 20 }

    # 连接凭据自愈：root 可能是「空口令」（刚 initialize）、「本次口令」（重跑）、
    # 或「上次口令」（中断残留）。逐个试探，能连上就把口令统一成 $dbPass 并建库。
    $credModes = @(
        @{ tag = 'saved-pass'; args = @('-u', 'root', ('--password=' + $dbPass)) },
        @{ tag = 'empty-pass'; args = @('-u', 'root', '--skip-password') },
        @{ tag = 'env-pass';   args = @('-u', 'root', ('--password=change-me')) }
    )
    # 不做单独的 SELECT 1 探测：-e 后的 SQL 含空格会被 Start-Process 拆散，
    # 反而报 "Unknown database" 误判为认证失败。直接执行建库 SQL，成功即视为连上。
    $sqlFile = Join-Path $work 'init-kewu-db.sql'
    @"
CREATE DATABASE IF NOT EXISTS kewu CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '$dbPass';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '$dbPass';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
"@ | Set-Content -Path $sqlFile -Encoding ASCII

    $connected = $false
    foreach ($mode in $credModes) {
        $c2 = Invoke-MySql $mysqlCli $sqlFile $mode.args ('mysql-init-' + $mode.tag)
        if ($c2 -eq 0) {
            Log ('  [DB] database ready via ' + $mode.tag)
            $connected = $true
            break
        }
    }
    if (-not $connected) {
        throw 'Cannot authenticate to MySQL as root with either saved or empty password. See logs/admin-deploy.log.'
    }

    # 口令落盘 + 回写 kewu.env（保持其它行不变），后端 Hikari 才能连上。
    Set-Content -Path $passFile -Value $dbPass -Encoding ASCII
    $lines = @()
    if (Test-Path $envPath) { $lines = @(Get-Content $envPath | Where-Object { $_ -notmatch '^\s*KEWU_DB_(USER|URL|PASSWORD)\s*=' }) }
    $lines += 'KEWU_DB_URL=jdbc:mysql://127.0.0.1:3306/kewu?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
    $lines += 'KEWU_DB_USER=root'
    $lines += 'KEWU_DB_PASSWORD=' + $dbPass
    $lines | Set-Content -Path $envPath -Encoding ASCII
    Log '[DB] MySQL ready on 3306, database `kewu` created, kewu.env synced'
}

try {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'One-click deployment requires Administrator privileges.'
    }

    # 目录不存在则创建：支持在一台全新服务器上自选空目录首次搭建。
    if (-not (Test-Path $ServerRoot)) {
        Log "backend directory does not exist, creating: $ServerRoot"
        New-Item -ItemType Directory -Force $ServerRoot | Out-Null
    }
    $root = (Resolve-Path $ServerRoot).Path
    Log "Server root: $root"
    $work = Join-Path $root 'admin-deploy'
    $extractRoot = Join-Path $work 'source'
    $zipPath = Join-Path $work 'kewu-backend-source.zip'
    $logDir = Join-Path $root 'logs'
    New-Item -ItemType Directory -Force $work, $extractRoot, $logDir | Out-Null
    $script:deployLog = Join-Path $logDir 'admin-deploy.log'
    Set-Content -Path $script:deployLog -Value ('===== deploy started ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' =====') -Encoding UTF8

    # ------------------------------------------------ 0. preflight info
    Log '---------- preflight ----------'
    $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { $_.IPAddress }
    Log ('host: ' + $env:COMPUTERNAME + '   IPv4: ' + ($ips -join ', '))
    if ($OnlineBuild) { Log 'mode: ONLINE BUILD (download source and build with Gradle on server)' } else { Log 'mode: PREBUILT JAR (download ready-made jar from GitHub Release, no Gradle on server)' }
    if (Test-Path (Join-Path $root 'deploy-8080.ps1')) { Log 'found existing deploy-8080.ps1 (will reuse)' } else { Log 'deploy-8080.ps1 missing (will bootstrap from template)' }
    if (Test-Path (Join-Path $root 'kewu.env')) { Log 'found existing kewu.env (will reuse)' } else { Log 'kewu.env missing (will generate with random secrets)' }
    if (Test-Path (Join-Path $root 'kewu-8080.jar')) { Log 'found existing kewu-8080.jar (upgrade deploy)' } else { Log 'kewu-8080.jar missing (first-time deploy)' }
    if (-not (Test-Tcp 3306)) {
        Log '[WARN] MySQL 3306 not listening -> will auto-install local MySQL 8.0 and create database `kewu`.'
    }
    if (-not (Test-Tcp 6379)) {
        Log '[WARN] Redis 6379 not listening (soft dependency: radar/AI status degrade gracefully, startup unaffected).'
    }

    # ------------------------------------------------ 1. ensure JDK 21
    $javaHome = Join-Path $root 'jdk21'
    if (Test-Path (Join-Path $javaHome 'bin\java.exe')) {
        Log "using bundled JDK: $javaHome"
    } else {
        $javaHome = $env:JAVA_HOME
        if ($javaHome -and (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
            Log "using JAVA_HOME: $javaHome"
        } else {
            Log '[1/4] No JDK found. Downloading OpenJDK 21 from Tsinghua mirror...'
            $mirrorDir = 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/'
            $idx = Invoke-WebRequest -UseBasicParsing -Uri $mirrorDir -TimeoutSec 60
            $jdkZip = ($idx.Links | Where-Object { $_.href -match '^OpenJDK21U-jdk_x64_windows_hotspot_.*\.zip$' } | Select-Object -First 1).href
            if (-not $jdkZip) { throw 'Could not find JDK 21 zip on Tsinghua Adoptium mirror.' }
            $jdkZipPath = Join-Path $work 'jdk21.zip'
            Invoke-WebRequest -UseBasicParsing -Uri ($mirrorDir + $jdkZip) -OutFile $jdkZipPath -TimeoutSec 1800
            Log ('  downloaded ' + (Get-Item $jdkZipPath).Length + ' bytes, extracting...')
            $jdkExtract = Join-Path $work 'jdk-extract'
            if (Test-Path $jdkExtract) { Remove-Item $jdkExtract -Recurse -Force }
            Expand-Archive -LiteralPath $jdkZipPath -DestinationPath $jdkExtract -Force
            $inner = Get-ChildItem $jdkExtract -Directory | Select-Object -First 1
            if (-not $inner) { throw 'JDK zip extracted but no directory found inside.' }
            if (Test-Path (Join-Path $root 'jdk21')) { Remove-Item (Join-Path $root 'jdk21') -Recurse -Force }
            Move-Item $inner.FullName (Join-Path $root 'jdk21')
            Remove-Item $jdkZipPath -Force -ErrorAction SilentlyContinue
            $javaHome = Join-Path $root 'jdk21'
            Log "  JDK ready: $javaHome"
        }
    }

    # ------------------------------------------------ 2. obtain the new jar
    $newJar = Join-Path $root 'kewu-8080-new.jar'
    $sourceDir = $null
    if (-not $OnlineBuild) {
        $gotJar = $false
        # 通道 1（首选）：codeload 分发仓库 ZIP——该域名在本项目服务器上已多次验证可通。
        if ($PrebuiltZipUrl) {
            try {
                Log '[2/4] Downloading prebuilt jar via dist repo ZIP (codeload)...'
                $distZip = Join-Path $work 'kewu-backend-dist.zip'
                Invoke-WebRequest -UseBasicParsing -Uri $PrebuiltZipUrl -OutFile $distZip -TimeoutSec 900
                Log ('  downloaded ' + (Get-Item $distZip).Length + ' bytes')
                $distExtract = Join-Path $work 'dist-extract'
                if (Test-Path $distExtract) { Remove-Item $distExtract -Recurse -Force }
                Expand-Archive -LiteralPath $distZip -DestinationPath $distExtract -Force
                $distJar = Get-ChildItem $distExtract -Recurse -Filter 'kewu-8080.jar' | Select-Object -First 1
                if (-not $distJar) { throw 'kewu-8080.jar not found inside dist zip' }
                Copy-Item $distJar.FullName $newJar -Force
                Remove-Item $distZip -Force -ErrorAction SilentlyContinue
                $gotJar = $true
            } catch {
                Log ('  dist zip channel failed: ' + $_.Exception.Message)
            }
        }
        # 通道 2（兜底）：GitHub Release 直链。
        if (-not $gotJar -and $PrebuiltJarUrl) {
            try {
                Log '[2/4] Downloading prebuilt backend jar from GitHub Release (fallback)...'
                Invoke-WebRequest -UseBasicParsing -Uri $PrebuiltJarUrl -OutFile $newJar -TimeoutSec 900
                $gotJar = $true
            } catch {
                Log ('  release channel failed: ' + $_.Exception.Message)
            }
        }
        # 通道 3（兜底）：目录里已有的 jar（FTP/面板手动上传）。
        if (-not $gotJar -and (Test-Path $newJar)) {
            Log '[2/4] using existing jar in server root'
            $gotJar = $true
        }
        if (-not $gotJar) { throw 'All prebuilt jar download channels failed. Check network or upload kewu-8080-new.jar manually.' }
        Log ('  jar ready: ' + (Get-Item $newJar).Length + ' bytes')
    } else {
        Log '[2/4] Downloading backend source from GitHub...'
        Invoke-WebRequest -UseBasicParsing -Uri $ZipUrl -OutFile $zipPath -TimeoutSec 300
        Log ('  downloaded ' + (Get-Item $zipPath).Length + ' bytes')
        if (Test-Path $extractRoot) { Remove-Item $extractRoot -Recurse -Force }
        Expand-Archive -LiteralPath $zipPath -DestinationPath $extractRoot -Force
        Remove-Item $zipPath -Force -ErrorAction SilentlyContinue

        $sourceDir = Get-ChildItem -Path $extractRoot -Directory | ForEach-Object { Join-Path $_.FullName 'kewu-8080' } | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $sourceDir) { throw 'kewu-8080 source directory not found in GitHub archive' }
        Log "  source: $sourceDir"

        # Gradle 发行版走腾讯镜像，避免 services.gradle.org 国际线路龟速。
        $wrapperProps = Join-Path $sourceDir 'gradle\wrapper\gradle-wrapper.properties'
        if (Test-Path $wrapperProps) {
            $wp = Get-Content $wrapperProps -Raw
            $wp = $wp -replace 'https\\?:\\/\\/services\.gradle\.org\\/distributions\\/', 'https\://mirrors.cloud.tencent.com/gradle/'
            Set-Content -Path $wrapperProps -Value $wp -Encoding ASCII
        }
    }

    # ------------------------------------------------ 3. bootstrap server files on first run
    $deployPs1 = Join-Path $root 'deploy-8080.ps1'
    if (-not (Test-Path $deployPs1)) {
        $template = ''
        if ($sourceDir) {
            $t = Join-Path $sourceDir 'deploy\deploy-8080.ps1'
            if (Test-Path $t) { $template = $t }
        }
        if (-not $template -and $DeployTemplate -and (Test-Path $DeployTemplate)) { $template = $DeployTemplate }
        if ($template) {
            Copy-Item $template $deployPs1 -Force
            Log "  bootstrapped deploy-8080.ps1 from template"
        } else {
            throw 'deploy-8080.ps1 missing and no template available.'
        }
    }
    if (-not (Test-Path (Join-Path $root 'kewu.env'))) {
        $jwt = New-RandomToken 24
        $adminPass = New-RandomToken 10
        @"
JWT_SECRET=$jwt
KEWU_DB_URL=jdbc:mysql://127.0.0.1:3306/kewu?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
KEWU_DB_USER=root
KEWU_DB_PASSWORD=change-me
ADMIN_ACCOUNT=admin
ADMIN_PASSWORD=$adminPass
KEWU_REDIS_HOST=127.0.0.1
KEWU_REDIS_PORT=6379
"@ | Set-Content -Path (Join-Path $root 'kewu.env') -Encoding ASCII
        Log "  generated kewu.env with random JWT_SECRET / admin password"
        Log "  >>> initial admin login: admin / $adminPass  (keep it safe, also in kewu.env)"
    }

    # 后端的唯一硬依赖：MySQL。必须在 8090 冒烟测试之前就绪。
    # 放在 kewu.env 生成之后调用，Ensure-MySQL 会把真实口令回写进去。
    Ensure-MySQL -root $root -work $work

    # 注意：脚本顶端是 $ErrorActionPreference = 'Stop'。PowerShell 5.1 会把 schtasks.exe
    # 写往 stderr 的“系统找不到指定的文件”（任务不存在时的正常输出）提升为终止异常
    # (RemoteException)，首次部署会在这里直接跳到 catch 报 [FAIL]，后面的 [3/4] 安全部署
    # 根本不会执行。改用纯 cmdlet 查询；创建命令局部降级为 Continue 并视为非致命。
    $autoTask = Get-ScheduledTask -TaskName 'KewuBackend8080' -ErrorAction SilentlyContinue
    $starter = Join-Path $root 'start-8080-service.ps1'
    # 上次部署中断可能留下“任务存在但启动脚本丢失”的半成品，必须一并重建。
    $taskHealthy = $autoTask -and (Test-Path $starter)
    if (-not $taskHealthy) {
        if ($autoTask) { Log '  autostart task exists but starter script is missing -> recreating' }
        @'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
foreach ($f in 'kewu.env', 'maas.env') {
    $path = Join-Path $root $f
    if (Test-Path $path) {
        Get-Content $path | ForEach-Object {
            $kv = $_ -split '=', 2
            if ($kv.Count -eq 2 -and $kv[0].Trim() -ne '') { [Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process') }
        }
    }
}
New-Item -ItemType Directory -Force (Join-Path $root 'logs') | Out-Null
$live = Join-Path $root 'kewu-8080.jar'
if (-not (Test-Path $live)) { exit 0 }
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match 'kewu-8080\.jar' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1
& (Join-Path $root 'jdk21\bin\java.exe') -Xmx512m -Dfile.encoding=UTF-8 -jar $live *>> (Join-Path $root 'logs\server8080.log')
'@ | Set-Content -Path $starter -Encoding UTF8
        # 开机自启只是附加能力，创建失败不能让整个部署失败（例如精简版系统没有任务计划服务）。
        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            schtasks /create /tn KewuBackend8080 /tr "powershell -NoProfile -ExecutionPolicy Bypass -File `"$starter`"" /sc onstart /ru SYSTEM /rl HIGHEST /f 2>&1 | Out-Null
            $taskCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $prevPref
        }
        if ($taskCode -ne 0) {
            Log ("  [WARN] could not create autostart task (exit $taskCode); backend still deployed, start it manually after reboot")
        } else {
            Log '  created scheduled task KewuBackend8080 (auto start on boot)'
        }
    } else {
        Log '  autostart task KewuBackend8080 already exists (will reuse)'
    }

    # ------------------------------------------------ 4. (online mode only) build
    if ($OnlineBuild) {
        # 清掉上次卡死残留的 Gradle 守护进程（按命令行匹配 gradle，不会碰线上 8080 后端）。
        $leftovers = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and ($_.CommandLine -match 'gradle') }
        foreach ($p in $leftovers) {
            Log ("  stopping leftover gradle java pid=" + $p.ProcessId)
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }

        # 国内服务器直连 Maven Central / Gradle Plugin Portal 极慢，注入阿里云镜像 init 脚本。
        # 必须 ASCII 写出：PS5.1 的 -Encoding UTF8 会带 BOM，而 Gradle 的 init 脚本读取器不剥 BOM。
        $initScript = Join-Path $work 'aliyun-mirror.init.gradle'
        @'
allprojects {
    buildscript {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
            maven { url 'https://maven.aliyun.com/repository/public' }
        }
    }
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/central' }
    }
}
settingsEvaluated { settings ->
    settings.pluginManagement {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
            maven { url 'https://maven.aliyun.com/repository/public' }
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
'@ | Set-Content -Path $initScript -Encoding ASCII

        $gradlew = Join-Path $sourceDir 'gradlew.bat'
        if (-not (Test-Path $gradlew)) { throw "Gradle wrapper not found: $gradlew" }

        Log '[3/4] Building kewu-8080 shadowJar on server (first run downloads deps via Aliyun mirror)...'
        $env:JAVA_HOME = $javaHome
        $env:Path = "$javaHome\bin;$env:Path"
        Push-Location $sourceDir
        # -D 锁定服务器 JDK；堆收敛到 1G/768M 避免与线上 8080 抢内存；
        # kotlin 编译改 in-process，避免 Kotlin 编译守护进程在低内存服务器上挂起。
        & cmd.exe /c "`"$gradlew`" --no-daemon -Dorg.gradle.java.home=`"$javaHome`" `"-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8`" `"-Dkotlin.daemon.jvmargs=-Xmx768m`" `"-Dkotlin.compiler.execution.strategy=in-process`" -I `"$initScript`" clean shadowJar"
        $buildCode = $LASTEXITCODE
        Pop-Location
        if ($buildCode -ne 0) { throw "Gradle shadowJar failed with exit code $buildCode" }

        $builtJar = Join-Path $sourceDir 'build\libs\kewu-8080.jar'
        if (-not (Test-Path $builtJar)) { throw "Built jar not found: $builtJar" }
        Log ("  built " + (Get-Item $builtJar).Length + ' bytes')
        Copy-Item $builtJar $newJar -Force
        Log "  copied to $newJar"
    }

    # ------------------------------------------------ 5. safe deploy (8090 smoke -> swap -> verify -> rollback)
    Log '[3/4] Running safe deploy script (8090 smoke test, backup, swap, verify, rollback)...'
    $runTag = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $sanitizedDeploy = Join-Path $work ('deploy-8080-safe-' + $runTag + '.ps1')
    $deploySource = Get-Content $deployPs1 -Raw
    # 原脚本按自身位置推导 $root；复制到临时目录执行会错根目录，显式锁定为服务器后端目录。
    $escapedRoot = $root.Replace("'", "''")
    $rootPattern = [regex]::Escape('$root = Split-Path -Parent $MyInvocation.MyCommand.Path')
    $deploySource = $deploySource -replace $rootPattern, "`$root = '$escapedRoot'"
    $deploySource = $deploySource -replace "Read-Host 'Enter to close'; exit 1", 'exit 1'
    $deploySource = $deploySource -replace "Read-Host 'Enter to close'", ''
    Set-Content -Path $sanitizedDeploy -Value $deploySource -Encoding UTF8
    # 临时文件带本次运行唯一后缀：上次部署若因 -Wait 卡住没退出，会继续占用
    # empty-input.txt / 重定向文件，导致重跑时 Set-Content 抛「文件正在使用」而误报部署失败。
    $emptyInput = Join-Path $work ('empty-input-' + $runTag + '.txt')
    Set-Content -Path $emptyInput -Value '' -Encoding ASCII
    $safeLog = Join-Path $logDir 'deploy-8080-output.txt'
    if (Test-Path $safeLog) { Remove-Item $safeLog -Force -ErrorAction SilentlyContinue }
    $safeStdout = Join-Path $work ('safe-deploy-stdout-' + $runTag + '.txt')
    $safeStderr = Join-Path $work ('safe-deploy-stderr-' + $runTag + '.txt')

    # 不能用 -Wait：安全部署脚本会 Start-Process 拉起常驻后端 Java（独立进程树），
    # -Wait 会一直等它退出，导致主脚本永远走不到 BACKEND DEPLOY DONE、GUI 一直转圈。
    # 改为轮询安全部署脚本自己的日志文件里的终态标记 DONE / [FAIL]。
    $deployProcess = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$sanitizedDeploy`"") `
        -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardInput $emptyInput -RedirectStandardOutput $safeStdout -RedirectStandardError $safeStderr

    $deadline = (Get-Date).AddMinutes(12)
    $terminal = ''
    $lastLen = 0
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $safeLog) {
            $txt = Get-Content $safeLog -Raw -ErrorAction SilentlyContinue
            if ($txt) {
                # 把安全部署的新增输出同步进主日志，SSH 上只读一个文件就能看全。
                if ($txt.Length -gt $lastLen) {
                    $newPart = $txt.Substring($lastLen)
                    foreach ($ln in ($newPart -split "`r?`n")) {
                        if ($ln.Trim()) { Log ('  | ' + $ln.Trim()) }
                    }
                    $lastLen = $txt.Length
                }
                if ($txt -match '(?m)^\s*\d\d:\d\d:\d\d\s+DONE\s*$') { $terminal = 'DONE'; break }
                if ($txt -match '\[FAIL\]') { $terminal = 'FAIL'; break }
            }
        }
        Start-Sleep -Seconds 2
    }
    if ($terminal -ne 'DONE') {
        if (Test-Path $safeStderr) {
            $se = (Get-Content $safeStderr -Raw -ErrorAction SilentlyContinue)
            if ($se -and $se.Trim()) { Log ('  [safe-deploy-stderr] ' + ($se.Trim() -replace "`r?`n", ' | ')) }
        }
        throw ("safe deploy did not reach DONE (terminal=" + ($terminal | Out-String).Trim() + "); see " + $safeLog)
    }
    try { $deployProcess.WaitForExit(3000) } catch { }

    Log '---------- deploy summary ----------'
    Log ('server IPv4: ' + ($ips -join ', '))
    Log "local health check: http://127.0.0.1:8080/api/health"
    Log 'BACKEND DEPLOY DONE'
} catch {
    $ex = $_.Exception
    Log ('[FAIL] ' + $ex.GetType().Name + ': ' + $ex.Message)
    try { Log ('  position: ' + ($_.InvocationInfo.PositionMessage -replace "`r?`n", ' | ')) } catch { }
    try { Log ('  stack: ' + ($_.ScriptStackTrace -replace "`r?`n", ' | ')) } catch { }
    if ($script:deployLog) { Log ('  full log: ' + $script:deployLog) }
    exit 1
}
