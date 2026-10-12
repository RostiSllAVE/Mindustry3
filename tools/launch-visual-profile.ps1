param([Parameter(Mandatory=$true)][string]$Label,[Parameter(Mandatory=$true)][string]$ModJar)
$ErrorActionPreference='Stop'
if($Label -notmatch '^[a-zA-Z0-9-]+$'){throw 'Simple unique label required'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$runRoot=Join-Path $projectRoot ('local\visual-profile\'+$Label)
if(Test-Path -LiteralPath $runRoot){throw 'Existing benchmark evidence must not be replaced'}
$sourceJar=if([IO.Path]::IsPathRooted($ModJar)){[IO.Path]::GetFullPath($ModJar)}else{Join-Path $projectRoot $ModJar}
$javaExe='D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe'
$gameDir='D:\SteamLibrary\steamapps\common\Mindustry'
$hostJar=Join-Path $gameDir 'jre\desktop.jar'
foreach($file in @($sourceJar,$javaExe,$hostJar)){if(-not(Test-Path -LiteralPath $file)){throw "Missing $file"}}
New-Item -ItemType Directory -Path (Join-Path $runRoot 'saves\mods'),(Join-Path $runRoot 'tmp') -Force | Out-Null
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $runRoot 'saves\mods\mindustry-fpv.jar')
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $runRoot 'profiled-mod.jar')
Set-Content -LiteralPath (Join-Path $runRoot 'steam_appid.txt') -Value '1127400' -Encoding ascii
$arguments=@('-Dnodiscord=true','-Dfpv.smoke=true','-Dfpv.visualProfile=true',('-Dfpv.visualProfile.output="'+$runRoot+'"'),('-Dfpv.lab.path="'+$runRoot+'"'),('-Djava.io.tmpdir="'+(Join-Path $runRoot 'tmp')+'"'),('-Dmindustry.data.dir="'+(Join-Path $runRoot 'saves')+'"'),('-Djava.library.path="'+$gameDir+'"'),'-cp',('"'+$hostJar+'"'),'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
$process=Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot 'stdout.log') -RedirectStandardError (Join-Path $runRoot 'stderr.log')
@{pid=$process.Id;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');sha256=(Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash.ToLowerInvariant()} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'process.json')
Get-Content -LiteralPath (Join-Path $runRoot 'process.json')
