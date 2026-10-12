param(
    [Parameter(Mandatory=$true)][string]$Run,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$GameDir='D:\SteamLibrary\steamapps\common\Mindustry',
    [switch]$ForceArc
)
$ErrorActionPreference='Stop'
if($Run -notmatch '^[a-z0-9-]+$'){throw 'Simple unique test run ID required'}
$root=Split-Path -Parent $PSScriptRoot
$lab=Join-Path $root ('local\native-input-lab\'+$Run)
$output=Join-Path $root ('local\native-input-results\'+$Run)
if((Test-Path -LiteralPath $lab) -or (Test-Path -LiteralPath $output)){throw 'Existing test run; choose a new ID'}
$version=[regex]::Match((Get-Content (Join-Path $root 'src\main\resources\mod.hjson') -Raw),'(?m)^version:\s*(\S+)').Groups[1].Value
$jar=Join-Path $root ('dist\mindustry-fpv-'+$version+'.jar')
$hostJar=Join-Path $GameDir 'jre\desktop.jar'
$java=Join-Path $JavaHome 'bin\java.exe'
foreach($path in @($jar,$hostJar,$java)){if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing dependency: $path"}}
New-Item -ItemType Directory -Path "$lab\saves\mods","$lab\tmp",$output -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination "$lab\saves\mods\mindustry-fpv.jar"
Set-Content -LiteralPath "$lab\steam_appid.txt" -Value '1127400' -Encoding ascii
$arguments=@('-Dnodiscord=true',('-Djava.io.tmpdir="'+$lab+'\tmp"'),('-Djava.library.path="'+$GameDir+'"'),('-Dmindustry.data.dir="'+$lab+'\saves"'),('-Dfpv.lab.path="'+$lab+'"'),'-Dfpv.smoke=true','-Dfpv.layers.smoke=true','-Dfpv.mouse.smoke=true','-Dfpv.placement.smoke=true',('-Dfpv.smoke.output="'+$output+'"'),'-cp',('"'+$hostJar+'"'),'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
if($ForceArc){$arguments=@('-Dfpv.input.forceArc=true')+$arguments}
$process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $lab -WindowStyle Hidden -PassThru -RedirectStandardOutput "$output\stdout.log" -RedirectStandardError "$output\stderr.log"
@{pid=$process.Id;java=$java;version=$version;forceArc=[bool]$ForceArc;jarSha256=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant();profile=$lab} | ConvertTo-Json | Set-Content "$output\process.json"
Write-Output "Isolated native-game regression started: $Run, PID $($process.Id)"
