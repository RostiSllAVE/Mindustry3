param([int]$Seconds=90,[int]$GamePid=0)
$ErrorActionPreference='Stop'
if($Seconds -lt 1 -or $Seconds -gt 180){throw 'Capture duration must be 1-180 seconds'}
$root=Split-Path -Parent $PSScriptRoot
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$principal=New-Object Security.Principal.WindowsPrincipal($identity)
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Windows denied ETW access. Run Record-Presentation.cmd as administrator. No permissions/groups are changed.'}
$exe='D:\Linux\tools\PresentMon\PresentMon-2.6.0-x64.exe'
if(-not(Test-Path -LiteralPath $exe)){throw 'Standalone PresentMon executable missing'}
$games=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -like '*mindustry.desktop.DesktopLauncher*' -and $_.CommandLine -like ('*'+$root+'*') -and ($GamePid -eq 0 -or $_.ProcessId -eq $GamePid)})
if($games.Count -ne 1){throw ('Expected one running MindustryFPV game; found '+$games.Count+'. Start Play-FPV.cmd normally first and close duplicate game windows.')}
$game=$games[0]
$label=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6)
$output=Join-Path $root ('local\present-profile\'+$label)
New-Item -ItemType Directory -Path $output | Out-Null
@{pid=$game.ProcessId;commandLine=$game.CommandLine;startedUtc=(Get-Date).ToUniversalTime().ToString('o');durationSeconds=$Seconds;profiler='PresentMon 2.6.0 standalone; Windows ETW only, no injection/GPU readbacks';profilerSha256=(Get-FileHash -LiteralPath $exe).Hash} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'capture.json')
Write-Output ('Recording actual presentations from game PID '+$game.ProcessId+' for '+$Seconds+' seconds.')
Write-Output ('Output: '+$output)
# Unique session; never stop another trace, install a service or alter Windows groups.
& $exe --process_id $game.ProcessId --session_name ('FPVCamera-'+$label) --timed $Seconds --terminate_after_timed --no_console_stats --date_time --write_display_metadata --track_hybrid_present --output_file (Join-Path $output 'presents.csv')
if($LASTEXITCODE -ne 0){throw ('Presentation recorder failed: '+$LASTEXITCODE)}
