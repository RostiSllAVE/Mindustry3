param([Parameter(Mandatory=$true)][int]$GamePid,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
$previousCpu=0.0
$previousTime=Get-Date
$logicalProcessors=[Environment]::ProcessorCount
for($sample=0;$sample -lt 35;$sample++){
    Start-Sleep -Seconds 2
    $game=Get-Process -Id $GamePid -ErrorAction SilentlyContinue
    if(-not $game){break}
    $now=Get-Date
    $cpu=$game.TotalProcessorTime.TotalSeconds
    $seconds=($now-$previousTime).TotalSeconds
    $memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory -ErrorAction SilentlyContinue
    $row=@{utc=$now.ToUniversalTime().ToString('o');pid=$GamePid;workingSetBytes=$game.WorkingSet64;privateBytes=$game.PrivateMemorySize64;cpuPercent=if($sample -eq 0){$null}else{100*($cpu-$previousCpu)/$seconds/$logicalProcessors};availableMB=$memory.AvailableMBytes;pageReadsPerSec=$memory.PageReadsPersec;pagesPerSec=$memory.PagesPersec;committedBytes=$memory.CommittedBytes;commitLimit=$memory.CommitLimit}
    $row | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $Output 'system-samples.jsonl') -Encoding utf8
    if($sample % 5 -eq 4){
        # Periodic snapshots survive an early native/SDL exit; no graphics readback or per-frame logging.
        & 'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\jcmd.exe' $GamePid JFR.dump name=fpv-camera-profile ('filename='+ (Join-Path $Output 'frames-snapshot.jfr')) 2>&1 | Out-File -LiteralPath (Join-Path $Output 'recording-status.log') -Append -Encoding utf8
    }
    $previousTime=$now
    $previousCpu=$cpu
}
