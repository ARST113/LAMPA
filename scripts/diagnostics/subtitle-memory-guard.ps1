function Get-SubtitleMemoryStopReason([long]$AvailableKiB, [long]$AppRssKiB) {
    if ($AvailableKiB -lt 1048576) { return 'low-system-memory' }
    if ($AppRssKiB -gt 3145728) { return 'high-app-rss' }
    return $null
}
