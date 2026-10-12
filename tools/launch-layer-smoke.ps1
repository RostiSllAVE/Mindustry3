param([Parameter(Mandatory=$true)][string]$Run,[switch]$MouseInteraction,[switch]$AutomaticConstruction,[switch]$ElevatedTurrets,[switch]$XyzPower,[switch]$WallAi)
$ErrorActionPreference='Stop'
if($Run -notmatch '^[a-z0-9-]+$'){throw 'Simple test run name required'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLab=Join-Path $taskRoot ('local\layer-lab\'+$Run)
$taskOut=Join-Path $taskRoot ('local\layer-smoke\'+$Run)
if(Test-Path -LiteralPath $taskOut){throw 'Test output already exists; use a new run name'}
$taskVersion=[regex]::Match((Get-Content -LiteralPath (Join-Path $taskRoot 'src\main\resources\mod.hjson') -Raw),'(?m)^version:\s*(\S+)').Groups[1].Value
New-Item -ItemType Directory -Force -Path "$taskLab\saves\mods","$taskLab\tmp",$taskOut | Out-Null
& (Join-Path $taskRoot 'tools\install-profile-mod.ps1') -SourceJar (Join-Path $taskRoot "dist\mindustry-fpv-$taskVersion.jar") -ModsDirectory (Join-Path $taskLab 'saves\mods') -BackupRoot (Join-Path $taskRoot 'local\backups')
$taskHost='D:\SteamLibrary\steamapps\common\Mindustry\jre\desktop.jar'
Set-Content -LiteralPath "$taskLab\steam_appid.txt" -Value '1127400'
$taskArgs=@('-Dnodiscord=true',"-Djava.io.tmpdir=$taskLab\tmp",'-Djava.library.path=D:\SteamLibrary\steamapps\common\Mindustry',"-Dmindustry.data.dir=$taskLab\saves","-Dfpv.lab.path=$taskLab",'-Dfpv.smoke=true','-Dfpv.layers.smoke=true','-Dfpv.layers.profile=true','-Dfpv.visualProfile=true',"-Dfpv.smoke.output=$taskOut",'-cp',$taskHost,'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
if($MouseInteraction){$taskArgs=@('-Dfpv.mouse.smoke=true')+$taskArgs}
if($WallAi){$taskArgs=@('-Dfpv.wallAi.smoke=true')+$taskArgs}
if($AutomaticConstruction){$taskArgs=@('-Dfpv.placement.smoke=true')+$taskArgs}
if($XyzPower){$taskArgs=@('-Dfpv.xyzPower.smoke=true')+$taskArgs}
if($ElevatedTurrets){$taskArgs=@('-Dfpv.elevatedTurrets.smoke=true')+$taskArgs}
$taskProc=Start-Process -FilePath 'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe' -ArgumentList $taskArgs -WorkingDirectory $taskLab -WindowStyle Hidden -Environment @{APPDATA=(Join-Path $taskLab 'appdata');LOCALAPPDATA=(Join-Path $taskLab 'appdata')} -PassThru -RedirectStandardOutput "$taskOut\stdout.log" -RedirectStandardError "$taskOut\stderr.log"
@{pid=$taskProc.Id;start=$taskProc.StartTime.ToUniversalTime().ToString('o');jarSha256=(Get-FileHash "$taskLab\saves\mods\mindustry-fpv.jar").Hash.ToLowerInvariant();hostSha256=(Get-FileHash $taskHost).Hash.ToLowerInvariant();host=$taskHost;version=$taskVersion} | ConvertTo-Json | Set-Content "$taskOut\process.json"
Get-Content "$taskOut\process.json"
