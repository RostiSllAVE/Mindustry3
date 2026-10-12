param([Parameter(Mandatory=$true)][string]$Run,[Parameter(Mandatory=$true)][string]$ModJar,[int]$MapSize=128,[int]$Samples=240,[switch]$CountDraws,[switch]$Jfr,[string]$Save,[string]$SettingsFile,[int]$Width=1280,[int]$Height=800,[int]$Altitude=16,[string]$Unit='flare',[switch]$CoreSpawned,[string]$CompanionMods,[switch]$NormalInput,[switch]$Javaw)
$ErrorActionPreference='Stop'
if($Run -notmatch '^[a-zA-Z0-9-]+$'){throw 'Simple unique label required'}
$root=Split-Path -Parent $PSScriptRoot
$lab=Join-Path $root ('local\camera-profile\'+$Run)
if(Test-Path -LiteralPath $lab){throw 'Existing benchmark evidence must not be replaced'}
$source=(Resolve-Path -LiteralPath $ModJar).Path
New-Item -ItemType Directory -Force -Path (Join-Path $lab 'saves\mods'),(Join-Path $lab 'tmp') | Out-Null
Copy-Item -LiteralPath $source -Destination (Join-Path $lab 'saves\mods\mindustry-fpv.jar')
if($CompanionMods){foreach($file in Get-ChildItem -LiteralPath (Resolve-Path -LiteralPath $CompanionMods).Path -File){if($file.Extension -in @('.zip','.jar') -and $file.Name -ne 'mindustry-fpv.jar'){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $lab ('saves\mods\'+$file.Name))}}}
if($SettingsFile){Copy-Item -LiteralPath (Resolve-Path -LiteralPath $SettingsFile).Path -Destination (Join-Path $lab 'saves\settings.bin')}
if($Save){Copy-Item -LiteralPath (Resolve-Path -LiteralPath $Save).Path -Destination (Join-Path $lab 'fixture.msav')}
Set-Content -LiteralPath (Join-Path $lab 'steam_appid.txt') -Value '1127400' -Encoding ascii
$game='D:\SteamLibrary\steamapps\common\Mindustry'
$args=@('-Dnodiscord=true','-Dfpv.smoke=true','-Dfpv.cameraProfile=true','-Dfpv.visualProfile=true',('-Dfpv.cameraProfile.mapSize='+$MapSize),('-Dfpv.cameraProfile.samples='+$Samples),('-Dfpv.cameraProfile.output="'+$lab+'"'),('-Dfpv.lab.path="'+$lab+'"'),('-Djava.io.tmpdir="'+(Join-Path $lab 'tmp')+'"'),('-Dmindustry.data.dir="'+(Join-Path $lab 'saves')+'"'),('-Djava.library.path="'+$game+'"'))
if($CountDraws){$args+='-Dfpv.cameraProfile.countDraws=true'}
if($SettingsFile){$args+='-Dfpv.cameraProfile.userSettings=true'}
if($Save){$args+=('-Dfpv.cameraProfile.save="'+(Join-Path $lab 'fixture.msav')+'"')}
$args+=('-Dfpv.cameraProfile.altitude='+$Altitude)
$args+=('-Dfpv.cameraProfile.unit='+$Unit)
if($CoreSpawned){$args+='-Dfpv.cameraProfile.coreSpawned=true'}
if($NormalInput){$args=$args | Where-Object {$_ -ne '-Dfpv.smoke=true'}}
if($Jfr){$args+=('-XX:StartFlightRecording=settings=profile,filename="'+(Join-Path $lab 'frames.jfr')+'",dumponexit=true')}
$args+=@('-cp',('"'+(Join-Path $game 'jre\desktop.jar')+'"'),'mindustry.desktop.DesktopLauncher','-width',[string]$Width,'-height',[string]$Height,'-maximized','false')
$javaExe=if($Javaw){'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\javaw.exe'}else{'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe'}
$proc=Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory $lab -WindowStyle Hidden -Environment @{APPDATA=(Join-Path $lab 'appdata');LOCALAPPDATA=(Join-Path $lab 'appdata')} -PassThru -RedirectStandardOutput (Join-Path $lab 'stdout.log') -RedirectStandardError (Join-Path $lab 'stderr.log')
@{pid=$proc.Id;runRoot=$lab;jarSha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant();startedUtc=$proc.StartTime.ToUniversalTime().ToString('o');mapSize=$MapSize;countOnly=[bool]$CountDraws} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $lab 'process.json')
Get-Content -LiteralPath (Join-Path $lab 'process.json')
