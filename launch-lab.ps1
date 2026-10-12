param([switch]$Smoke,[switch]$Windowed,[string]$GameDir='D:\SteamLibrary\steamapps\common\Mindustry')
$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
$profileName=if($Smoke){'lab'}else{'play'}
$labRoot=Join-Path $projectRoot $profileName
$hostJar=Join-Path $GameDir 'jre\desktop.jar'
# The host's stripped JRE omits java.logging, required by the current native mouse bridge.
# Keep the complete runtime on D: and leave the installed game's runtime untouched.
$javaName=if($Smoke){'java.exe'}else{'javaw.exe'}
$javaExe=Join-Path 'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin' $javaName
if($env:FPV_JAVA){$javaExe=$env:FPV_JAVA}
$modJar=Join-Path $projectRoot 'dist\mindustry-fpv-0.18.27.jar'
foreach($path in @($hostJar,$javaExe,$modJar)){if(-not(Test-Path -LiteralPath $path)){throw "Required file missing: $path"}}
New-Item -ItemType Directory -Force -Path $labRoot,(Join-Path $labRoot 'saves\mods'),(Join-Path $labRoot 'tmp'),(Join-Path $projectRoot 'local\smoke') | Out-Null
& (Join-Path $projectRoot 'tools\install-profile-mod.ps1') -SourceJar $modJar -ModsDirectory (Join-Path $labRoot 'saves\mods') -BackupRoot (Join-Path $projectRoot 'local\backups') | Out-Null
if(-not $Smoke){
    # This machine's windowed OpenGL/GDI-copy path drops most displayed frames.
    # Initialize native fullscreen once; subsequent native UI choices stay intact.
    $presentationMode=if($Windowed){'windowed'}else{'repair'}
    & (Join-Path $projectRoot 'tools\ensure-presentation-mode.ps1') -Mode $presentationMode -GameDir $GameDir
}
# Steam's documented development app-ID file identifies the installed required host; it is not an ownership bypass.
Set-Content -LiteralPath (Join-Path $labRoot 'steam_appid.txt') -Value '1127400' -Encoding ascii
$arguments=@('-Dnodiscord=true',('-Djava.io.tmpdir="'+(Join-Path $labRoot 'tmp')+'"'),('-Djava.library.path="'+$GameDir+'"'),('-Dmindustry.data.dir="'+(Join-Path $labRoot 'saves')+'"'),('-Dfpv.lab.path="'+$labRoot+'"'))
if($Smoke){$arguments+=@('-Dfpv.smoke=true',('-Dfpv.smoke.output="'+(Join-Path $projectRoot 'local\smoke')+'"'),('-Dfpv.smoke.userSave="'+(Join-Path $projectRoot 'local\fixtures\imported-user.msav')+'"'))}
if($Smoke){$arguments+=('-Dfpv.smoke.latestSave="'+(Join-Path $projectRoot 'local\fixtures\latest-user.msav')+'"')}
$arguments+=@('-cp',('"'+$hostJar+'"'),'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
# Hidden is appropriate only for the automated smoke process. Windows can apply
# STARTUPINFO's hidden state to SDL's first window, even though rendering/audio work.
# javaw avoids a console while Normal allows the interactive game window to appear.
$windowStyle=if($Smoke){'Hidden'}else{'Normal'}
$process=Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $labRoot -WindowStyle $windowStyle -PassThru -RedirectStandardOutput (Join-Path $labRoot 'stdout.log') -RedirectStandardError (Join-Path $labRoot 'stderr.log')
$process.Id | Set-Content -LiteralPath (Join-Path $labRoot 'process.pid')
Write-Output "Mindustry FPV $profileName PID: $($process.Id)"
Write-Output "Isolated profile: $(Join-Path $labRoot 'saves')"
Write-Output "Game log: $(Join-Path $labRoot 'stdout.log')"
