$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/subtitle-memory-guard.ps1"
if (Get-SubtitleMemoryStopReason 1048576 3145728) { throw 'Boundary must be permitted' }
if ((Get-SubtitleMemoryStopReason 1048575 100) -ne 'low-system-memory') { throw 'Missing available-memory guard' }
if ((Get-SubtitleMemoryStopReason 2000000 3145729) -ne 'high-app-rss') { throw 'Missing app-RSS guard' }
if (Get-SubtitleMemoryStopReason 2000000 1200000) { throw 'Healthy sample stopped' }
'4 memory guard checks passed; no device commands executed'
