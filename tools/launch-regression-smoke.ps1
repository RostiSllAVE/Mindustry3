param([Parameter(Mandatory=$true)][string]$Run,[ValidateSet('160.5','160.7')][string]$HostVersion='160.7',[switch]$ImportedProfile,[string]$LatestSaveCopy,[switch]$ProductionOnly,[switch]$AircraftOnly,[switch]$WalkerWeaponsOnly)
$ErrorActionPreference='Stop'
if($Run -notmatch '^[a-z0-9-]+$'){throw 'Simple run ID required'}
$taskRoot=(Split-Path -Parent $PSScriptRoot)
$taskLab=Join-Path $taskRoot ('local\compat-lab\'+$Run)
$taskOut=Join-Path $taskRoot ('local\compat-smoke\'+$Run)
if(Test-Path -LiteralPath $taskOut){throw 'Existing test run'}
if($ImportedProfile -and -not(Test-Path -LiteralPath (Join-Path $taskLab 'profile-copy.json'))){throw 'Imported profile must be staged from the verified backup first'}
New-Item -ItemType Directory -Force -Path "$taskLab\saves\mods","$taskLab\tmp",$taskOut | Out-Null
& (Join-Path $taskRoot 'tools\install-profile-mod.ps1') -SourceJar (Join-Path $taskRoot 'dist\mindustry-fpv-0.18.27.jar') -ModsDirectory (Join-Path $taskLab 'saves\mods') -BackupRoot (Join-Path $taskRoot 'local\backups')
$taskHost=if($HostVersion -eq '160.7'){'D:\SteamLibrary\steamapps\common\Mindustry\jre\desktop.jar'}else{Join-Path $taskRoot 'local\server-runtime\Mindustry-v160.5.jar'}
Set-Content -LiteralPath "$taskLab\steam_appid.txt" -Value '1127400'
if(-not $LatestSaveCopy){$LatestSaveCopy=Join-Path $taskRoot 'local\fixtures\before-layers-erekir-10.msav'}
$LatestSaveCopy=(Resolve-Path -LiteralPath $LatestSaveCopy).Path
if(-not $LatestSaveCopy.StartsWith((Join-Path $taskRoot 'local\fixtures\'),[StringComparison]::OrdinalIgnoreCase)){throw 'Use a disposable save copy in local\fixtures; never a live profile save'}
$taskArgs=@('-Dnodiscord=true',"-Djava.io.tmpdir=$taskLab\tmp",'-Djava.library.path=D:\SteamLibrary\steamapps\common\Mindustry',"-Dmindustry.data.dir=$taskLab\saves","-Dfpv.lab.path=$taskLab",'-Dfpv.smoke=true',"-Dfpv.smoke.output=$taskOut","-Dfpv.smoke.noCoreSave=$taskRoot\local\fixtures\before-layers-serpulo-228.msav","-Dfpv.smoke.userSave=$taskRoot\local\fixtures\before-layers-serpulo-228-previous.msav","-Dfpv.smoke.latestSave=$LatestSaveCopy",'-cp',$taskHost,'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
if($ProductionOnly){$taskArgs=@('-Dfpv.smoke.productionOnly=true')+$taskArgs}
if($AircraftOnly){$taskArgs=@('-Dfpv.smoke.aircraftOnly=true')+$taskArgs}
if($WalkerWeaponsOnly){$taskArgs=@('-Dfpv.smoke.walkerWeaponsOnly=true')+$taskArgs}
$taskProc=Start-Process -FilePath 'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe' -ArgumentList $taskArgs -WorkingDirectory $taskLab -WindowStyle Hidden -Environment @{APPDATA=(Join-Path $taskLab 'appdata');LOCALAPPDATA=(Join-Path $taskLab 'appdata')} -PassThru -RedirectStandardOutput "$taskOut\stdout.log" -RedirectStandardError "$taskOut\stderr.log"
@{pid=$taskProc.Id;start=$taskProc.StartTime.ToUniversalTime().ToString('o');sha=(Get-FileHash "$taskLab\saves\mods\mindustry-fpv.jar").Hash.ToLowerInvariant();hostSha=(Get-FileHash $taskHost).Hash.ToLowerInvariant();host=$taskHost;gameVersion=$HostVersion;importedProfile=[bool]$ImportedProfile} | ConvertTo-Json | Set-Content "$taskOut\process.json"
Get-Content "$taskOut\process.json"
