param([Parameter(Mandatory=$true)][string]$Label,[string]$Save='local\fixtures\latest-user.msav',[string]$ModJar='')
$ErrorActionPreference='Stop'
if($Label -notmatch '^[a-zA-Z0-9-]+$'){throw 'Simple unique benchmark label required'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$runRoot=Join-Path $projectRoot ('local\wall-profile\'+$Label)
if(Test-Path -LiteralPath $runRoot){throw 'Benchmark already exists'}
$gameDir='D:\SteamLibrary\steamapps\common\Mindustry'
$javaExe='D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe'
$hostJar=Join-Path $gameDir 'jre\desktop.jar'
$sourceModJar=if([string]::IsNullOrWhiteSpace($ModJar)){Join-Path $projectRoot 'dist\mindustry-fpv-0.12.1.jar'}elseif([IO.Path]::IsPathRooted($ModJar)){[IO.Path]::GetFullPath($ModJar)}else{Join-Path $projectRoot $ModJar}
$copySave=Join-Path $projectRoot $Save
$data=Join-Path $runRoot 'saves'
$tmp=Join-Path $runRoot 'tmp'
foreach($file in @($javaExe,$hostJar,$sourceModJar,$copySave)){if(-not(Test-Path -LiteralPath $file)){throw "Missing $file"}}
New-Item -ItemType Directory -Force -Path (Join-Path $data 'mods'),$tmp | Out-Null
Copy-Item -LiteralPath $sourceModJar -Destination (Join-Path $data 'mods\mindustry-fpv.jar')
Copy-Item -LiteralPath $sourceModJar -Destination (Join-Path $runRoot 'profiled-mod.jar')
Set-Content -LiteralPath (Join-Path $runRoot 'steam_appid.txt') -Value '1127400' -Encoding ascii
$arguments=@('-Dnodiscord=true','-Dfpv.smoke=true','-Dfpv.wallProfile=true',('-Dfpv.wallProfile.label='+$Label),('-Dfpv.wallProfile.save="'+$copySave+'"'),('-Dfpv.wallProfile.output="'+$runRoot+'"'),('-Dfpv.lab.path="'+$runRoot+'"'),('-Djava.io.tmpdir="'+$tmp+'"'),('-Dmindustry.data.dir="'+$data+'"'),('-Djava.library.path="'+$gameDir+'"'),'-cp',('"'+$hostJar+'"'),'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
$process=Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot 'stdout.log') -RedirectStandardError (Join-Path $runRoot 'stderr.log')
@{pid=$process.Id;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');sha256=(Get-FileHash -LiteralPath $sourceModJar -Algorithm SHA256).Hash.ToLowerInvariant();saveSha256=(Get-FileHash -LiteralPath $copySave -Algorithm SHA256).Hash.ToLowerInvariant()} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'process.json')
Get-Content -LiteralPath (Join-Path $runRoot 'process.json')
