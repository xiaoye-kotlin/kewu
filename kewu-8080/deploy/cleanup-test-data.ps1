# Kewu: wipe ALL course-related test data, keep user accounts.
# Steps: full mysqldump backup -> show what will be deleted -> ask to type DELETE -> delete -> show counts.
# Output: logs\cleanup-test-data-output.txt. Pure ASCII on purpose.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory $logDir | Out-Null }
$out = Join-Path $logDir 'cleanup-test-data-output.txt'
Set-Content -Path $out -Value '' -Encoding utf8
$bin = Join-Path $root 'mysql\mysql-8.0.29-winx64\bin'
$mysql = Join-Path $bin 'mysql.exe'
$dump = Join-Path $bin 'mysqldump.exe'
$user = 'kewu'; $pass = 'Kewu-DB-2026'; $db = 'kewu'

function Log([string]$m) { Write-Host $m; Add-Content -Path $out -Value $m -Encoding utf8 }
function Q([string]$sql) {
    & $mysql --default-character-set=utf8mb4 --connect-timeout=8 -h 127.0.0.1 -P 3306 -u $user -p"$pass" $db -N -e $sql 2>&1 |
        Where-Object { $_ -notmatch 'Using a password on the command line' }
}

# Tables holding course / class / homework / attendance data. users and crash_reports are kept.
$tables = @('submissions', 'assignments', 'class_notes', 'attendance_records', 'attendance_sessions', 'attendance_roster',
    'class_students', 'classes', 'class_states', 'user_courses', 'course_schedules', 'course_materials',
    'class_recordings', 'questions', 'uploads', 'courses')

# ---------------------------------------------------------------- 1. backup
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$bakDir = Join-Path $root 'backup'
if (-not (Test-Path $bakDir)) { New-Item -ItemType Directory $bakDir | Out-Null }
$bak = Join-Path $bakDir "kewu-before-cleanup-$ts.sql"
Log "[1/4] full backup -> $bak"
# 2026-10-27: write the dump with --result-file. Piping through Out-File re-decoded the UTF-8 bytes as GBK and
# corrupted every Chinese string (and swallowed closing quotes), making the backup unrestorable.
& $dump --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u $user -p"$pass" --single-transaction --routines --result-file="$bak" $db 2>$null
$size = (Get-Item $bak -ErrorAction SilentlyContinue).Length
if (-not $size -or $size -lt 1024) {
    Log "[FAIL] backup is empty or too small ($size bytes). Nothing deleted."
    Read-Host 'Enter to close'; exit 1
}
Log "  backup ok: $size bytes"

# ---------------------------------------------------------------- 2. show
Log '[2/4] what will be deleted (row counts):'
foreach ($t in $tables) { Log ("  {0,-22} {1}" -f $t, (Q "SELECT COUNT(*) FROM $t")) }
Log '  courses:'
Q "SELECT CONCAT('    ', id, ' | ', name, ' | ', teacher) FROM courses" | ForEach-Object { Log $_ }
Log '  assignments:'
Q "SELECT CONCAT('    ', id, ' | ', course_id, ' | ', title) FROM assignments" | ForEach-Object { Log $_ }
Log '  KEPT - user accounts:'
Q "SELECT CONCAT('    ', username, ' | ', IFNULL(nickname,''), ' | ', role) FROM users" | ForEach-Object { Log $_ }

# ---------------------------------------------------------------- 3. confirm
Write-Host ''
$ans = Read-Host 'Type DELETE (all caps) to wipe the data above, anything else to cancel'
if ($ans -cne 'DELETE') { Log 'Cancelled. Nothing deleted.'; Read-Host 'Enter to close'; exit 0 }

# ---------------------------------------------------------------- 4. delete
Log '[3/4] deleting...'
$sql = 'SET FOREIGN_KEY_CHECKS=0; START TRANSACTION; ' + (($tables | ForEach-Object { "DELETE FROM $_;" }) -join ' ') + ' COMMIT; SET FOREIGN_KEY_CHECKS=1;'
Q $sql | ForEach-Object { Log "  $_" }
Log '[4/4] row counts after:'
foreach ($t in $tables) { Log ("  {0,-22} {1}" -f $t, (Q "SELECT COUNT(*) FROM $t")) }
Log "DONE. Restore if needed: mysql -u kewu -p kewu < $bak"
Read-Host 'Enter to close'
