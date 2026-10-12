param([Parameter(Mandatory=$true)][string]$RunId)
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[a-zA-Z0-9-]+$'){throw 'Simple run ID required'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$manifest=Get-Content -LiteralPath (Join-Path $projectRoot ('local\net-lab\'+$RunId+'\processes.json')) -Raw | ConvertFrom-Json
foreach($record in $manifest.processes){
    $process=Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if($process -and $process.ProcessName -eq 'java' -and [Math]::Abs(($process.StartTime.ToUniversalTime()-([datetime]$record.startedUtc).ToUniversalTime()).TotalSeconds) -lt 1){
        Stop-Process -Id $process.Id
        Write-Output ('Stopped owned '+$record.role+' PID '+$record.pid)
    }
}
