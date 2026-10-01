param(
    [Parameter(Mandatory=$true)][int]$TestPid,
    [Parameter(Mandatory=$true)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$taskPreviousCpu = $null
$taskPreviousTime = $null
$taskLogicalCpus = [Environment]::ProcessorCount
while ($true) {
    $taskProcess = Get-Process -Id $TestPid -ErrorAction SilentlyContinue
    if ($null -eq $taskProcess) { break }
    $taskTime = [DateTime]::UtcNow
    $taskCpu = $taskProcess.TotalProcessorTime.TotalSeconds
    $taskLoad = if ($null -eq $taskPreviousCpu) { 0 } else {
        100 * ($taskCpu - $taskPreviousCpu) / ($taskTime - $taskPreviousTime).TotalSeconds / $taskLogicalCpus
    }
    [pscustomobject]@{
        utc = $taskTime.ToString('o')
        pid = $TestPid
        machineCpuPercent = [Math]::Round($taskLoad, 2)
        cpuSeconds = [Math]::Round($taskCpu, 2)
        workingSetMb = [Math]::Round($taskProcess.WorkingSet64 / 1MB, 2)
        privateMb = [Math]::Round($taskProcess.PrivateMemorySize64 / 1MB, 2)
        threads = $taskProcess.Threads.Count
        handles = $taskProcess.HandleCount
    } | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Append
    $taskPreviousCpu = $taskCpu
    $taskPreviousTime = $taskTime
    Start-Sleep -Seconds 5
}
