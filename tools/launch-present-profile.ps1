param([switch]$Synced,[switch]$FullscreenTest)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$principal=New-Object Security.Principal.WindowsPrincipal($identity)
if($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Launch Play-Record-Presentation.cmd normally, not as administrator. Only the recorder should be elevated.'}
$games=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -like '*mindustry.desktop.DesktopLauncher*' -and $_.CommandLine -like ('*'+$root+'*')})
if($games.Count -gt 1 -or ($Synced -or $FullscreenTest) -and $games.Count -ne 0){throw 'Close game windows first; no games were terminated.'}
if($games.Count -eq 0){
    $info=& (Join-Path $PSScriptRoot 'launch-live-camera-profile.ps1') -InstalledBaseline -PassThru -Synced:$Synced -FullscreenTest:$FullscreenTest | Where-Object {$_ -is [pscustomobject] -and $_.GamePid}
    if(-not $info){throw 'Game launcher did not return a process ID'}
    $capturePid=$info.GamePid
}else{$capturePid=$games[0].ProcessId}
Write-Output 'Accept the Windows prompt for the recorder only. Open your save and test 2D / horizon / vertical / horizon during the 90 second recording.'
$arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $PSScriptRoot 'record-presentation.ps1')+'"'),'-GamePid',[string]$capturePid)
# The player authorized the ETW recorder's elevation. The game remains an ordinary user process.
$recorder=Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru
Write-Output ('Recorder PID: '+$recorder.Id)
