param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$Adb = 'adb',
    [ValidatePattern('^[a-zA-Z0-9_.]+\.repair$')][string]$Package = 'top.rootu.lampa.repair',
    [ValidateRange(5,7200)][int]$Seconds = 2100
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/subtitle-memory-guard.ps1"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$target = (Resolve-Path -LiteralPath $OutputDirectory).Path
$sampleFile = Join-Path $target 'memory.csv'
if (Test-Path -LiteralPath $sampleFile) { throw 'Use a new output directory to preserve earlier measurements' }
$deadline = (Get-Date).AddSeconds($Seconds)
$iteration = 0
$pattern = '^\s*(\d+)\s+\d+\s+(\d+)\s+(' + [regex]::Escape($Package) + '(?::.*)?)$'
while ((Get-Date) -lt $deadline) {
    $time = Get-Date -Format o
    $memory = & $Adb -s $Serial shell cat /proc/meminfo
    $match = $memory | Select-String '^MemAvailable:\s+(\d+)'
    if (!$match) { throw 'Missing memory sample; device may be disconnected' }
    $available = [long]$match.Matches.Groups[1].Value
    $total = 0L
    $pids = @()
    foreach ($line in (& $Adb -s $Serial shell ps -A -o PID,PPID,RSS,NAME)) {
        if ($line -match $pattern) { $pids += [int]$Matches[1]; $total += [long]$Matches[2] }
    }
    $reason = Get-SubtitleMemoryStopReason $available $total
    [pscustomobject]@{time=$time;rss_kib=$total;available_kib=$available;processes=$pids.Count;stop_reason=$reason} |
        Export-Csv -NoTypeInformation -Append -LiteralPath $sampleFile
    if ($iteration % 6 -eq 0 -or $reason) {
        foreach ($appPid in $pids) { & $Adb -s $Serial shell dumpsys meminfo $appPid > (Join-Path $target "meminfo-$iteration-$appPid.txt") }
        & $Adb -s $Serial shell dumpsys cpuinfo > (Join-Path $target "cpu-$iteration.txt")
        & $Adb -s $Serial shell dumpsys thermalservice > (Join-Path $target "thermal-$iteration.txt")
        & $Adb -s $Serial shell dumpsys battery > (Join-Path $target "battery-$iteration.txt")
    }
    Write-Output "$time appRSS=$([math]::Round($total/1024))MiB available=$([math]::Round($available/1024))MiB processes=$($pids.Count)"
    if ($reason) {
        & $Adb -s $Serial shell am force-stop $Package
        "$time stopped diagnostic package: $reason" | Set-Content -LiteralPath (Join-Path $target 'intervention.txt')
        break
    }
    if ($pids.Count -eq 0) { 'Diagnostic app exited' | Set-Content -LiteralPath (Join-Path $target 'app-exited.txt'); break }
    $iteration++
    Start-Sleep -Seconds 5
}
