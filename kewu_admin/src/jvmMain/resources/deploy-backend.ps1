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

function Log([string]$message) {
    Write-Host ((Get-Date -Format 'HH:mm:ss') + '  ' + $message)
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
    New-Item -ItemType Directory -Force $work, $extractRoot | Out-Null

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
    if (-not (Test-Tcp 3306)) { Log '[WARN] MySQL 3306 not listening. Please install MySQL and create database `kewu` before deploying.' }
    if (-not (Test-Tcp 6379)) { Log '[WARN] Redis 6379 not listening. Radar/AI status features need Redis.' }

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
        Log "  >>> remember to set KEWU_DB_PASSWORD to a real MySQL credential"
    }
    schtasks /query /tn KewuBackend8080 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $starter = Join-Path $root 'start-8080-service.ps1'
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
        schtasks /create /tn KewuBackend8080 /tr "powershell -NoProfile -ExecutionPolicy Bypass -File `"$starter`"" /sc onstart /ru SYSTEM /rl HIGHEST /f | Out-Null
        Log '  created scheduled task KewuBackend8080 (auto start on boot)'
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

    Log '---------- deploy summary ----------'
    Log ('server IPv4: ' + ($ips -join ', '))
    Log "local health check: http://127.0.0.1:8080/api/health"
    Log 'BACKEND DEPLOY DONE'
} catch {
    Log ('[FAIL] ' + $_.Exception.Message)
    exit 1
}
