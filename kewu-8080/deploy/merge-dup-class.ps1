# Kewu: merge duplicate class k691400 (has the timetable, 0 students) into k206700 (has the students).
# Steps: backup (mysqldump --result-file) -> show what will change -> type MERGE -> apply -> show result.
# Output: logs\merge-dup-class-output.txt. Pure ASCII on purpose.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory $logDir | Out-Null }
$out = Join-Path $logDir 'merge-dup-class-output.txt'
Set-Content -Path $out -Value '' -Encoding utf8
$bin = Join-Path $root 'mysql\mysql-8.0.29-winx64\bin'
$mysql = Join-Path $bin 'mysql.exe'
$dump = Join-Path $bin 'mysqldump.exe'
$user = 'kewu'; $pass = 'Kewu-DB-2026'; $db = 'kewu'
$keep = 'k206700'; $drop = 'k691400'

function Log([string]$m) { Write-Host $m; Add-Content -Path $out -Value $m -Encoding utf8 }
function Q([string]$sql) {
    & $mysql --default-character-set=utf8mb4 --connect-timeout=8 -h 127.0.0.1 -P 3306 -u $user -p"$pass" $db -N -e $sql 2>&1 |
        Where-Object { $_ -notmatch 'Using a password on the command line' }
}

$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$bakDir = Join-Path $root 'backup'
if (-not (Test-Path $bakDir)) { New-Item -ItemType Directory $bakDir | Out-Null }
$bak = Join-Path $bakDir "kewu-before-merge-$ts.sql"
Log "[1/4] full backup -> $bak"
& $dump --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u $user -p"$pass" --single-transaction --routines --result-file="$bak" $db 2>$null
$size = (Get-Item $bak -ErrorAction SilentlyContinue).Length
if (-not $size -or $size -lt 1024) { Log "[FAIL] backup empty ($size bytes). Nothing changed."; Read-Host 'Enter to close'; exit 1 }
Log "  backup ok: $size bytes"

Log '[2/4] current state:'
foreach ($c in @($keep, $drop)) {
    Log ("  class {0}: exists={1} students={2} schedules={3}" -f $c,
        (Q "SELECT COUNT(*) FROM classes WHERE id='$c'"),
        (Q "SELECT COUNT(*) FROM class_students WHERE class_id='$c'"),
        (Q "SELECT COUNT(*) FROM course_schedules WHERE class_id='$c'"))
}
if ((Q "SELECT COUNT(*) FROM classes WHERE id='$keep'") -ne '1' -or (Q "SELECT COUNT(*) FROM classes WHERE id='$drop'") -ne '1') {
    Log '[STOP] one of the two classes is missing; nothing changed.'; Read-Host 'Enter to close'; exit 1
}
Log "  plan: move schedules + students + course link of $drop into $keep, then delete class $drop"
$ans = Read-Host 'Type MERGE to continue'
if ($ans -ne 'MERGE') { Log 'Cancelled, nothing changed.'; Read-Host 'Enter to close'; exit 0 }

Log '[3/4] applying...'
Q @"
START TRANSACTION;
UPDATE course_schedules SET class_id='$keep', class_name=(SELECT name FROM (SELECT name FROM classes WHERE id='$keep') t) WHERE class_id='$drop';
UPDATE classes k JOIN classes d ON d.id='$drop' SET k.course_id=d.course_id, k.course_name=d.course_name WHERE k.id='$keep' AND k.course_id='';
INSERT IGNORE INTO class_students (class_id, student_id) SELECT '$keep', student_id FROM class_students WHERE class_id='$drop';
DELETE FROM class_students WHERE class_id='$drop';
DELETE FROM classes WHERE id='$drop';
COMMIT;
"@ | ForEach-Object { Log "  $_" }

Log '[4/4] after:'
Log ("  class {0}: students={1} schedules={2} course_id={3}" -f $keep,
    (Q "SELECT COUNT(*) FROM class_students WHERE class_id='$keep'"),
    (Q "SELECT COUNT(*) FROM course_schedules WHERE class_id='$keep'"),
    (Q "SELECT course_id FROM classes WHERE id='$keep'"))
Log ("  class {0} exists={1}" -f $drop, (Q "SELECT COUNT(*) FROM classes WHERE id='$drop'"))
Log "DONE. Restore if needed: mysql -u kewu -p kewu < $bak"
Read-Host 'Enter to close'
