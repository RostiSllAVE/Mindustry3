param([string]$ModJar,[switch]$PrepareOnly,[switch]$NoGpu,[switch]$InstalledBaseline,[switch]$PassThru,[switch]$Synced,[switch]$FullscreenTest)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(-not $ModJar){$ModJar=Join-Path $root 'local\camera-profile-staging\live-capture-d\profiled-mod.jar'}
if($InstalledBaseline){$ModJar=Join-Path $root 'play\saves\mods\mindustry-fpv.jar'}
$sourceJar=(Resolve-Path -LiteralPath $ModJar).Path
$sourceProfile=Join-Path $root 'play\saves'
$run='live-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6)
$lab=Join-Path $root ('local\camera-profile\'+$run)
$profile=Join-Path $lab 'saves'
New-Item -ItemType Directory -Path $profile,(Join-Path $profile 'mods'),(Join-Path $lab 'tmp') -Force | Out-Null
# Copy only small gameplay/profile directories. Never copy the multi-GB screenshots folder.
foreach($name in @('maps','saves','schematics','cache','previews','modIconCache','assetCache')){
    $source=Join-Path $sourceProfile $name
    if(Test-Path -LiteralPath $source){Copy-Item -LiteralPath $source -Destination (Join-Path $profile $name) -Recurse}
}
foreach($name in @('settings.bin','settings.bin.bak')){
    $source=Join-Path $sourceProfile $name
    if(Test-Path -LiteralPath $source){Copy-Item -LiteralPath $source -Destination (Join-Path $profile $name)}
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $sourceProfile 'mods') -File){
    if($file.Extension -in @('.jar','.zip') -and $file.Name -ne 'mindustry-fpv.jar'){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $profile ('mods\'+$file.Name))}
}
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $profile 'mods\mindustry-fpv.jar')
Set-Content -LiteralPath (Join-Path $lab 'steam_appid.txt') -Value '1127400' -Encoding ascii
@{runRoot=$lab;originalProfile=$sourceProfile;jarSha256=(Get-FileHash -LiteralPath $sourceJar).Hash;originalJarSha256=(Get-FileHash -LiteralPath (Join-Path $sourceProfile 'mods\mindustry-fpv.jar')).Hash;preparedUtc=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $lab 'process.json')
Write-Output ('Diagnostic copy: '+$lab)
if($Synced -and $FullscreenTest){throw 'Test one native presentation setting at a time'}
if($Synced -or $FullscreenTest){
    $classes=Join-Path $root 'local\profile-video-tool'
    if(-not(Test-Path -LiteralPath (Join-Path $classes 'ProfileVideoSettings.class'))){throw 'Compile the presentation settings helper before this test'}
    $setting=if($FullscreenTest){'fullscreen'}else{'vsync'}
    & 'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe' ('-Djava.io.tmpdir='+(Join-Path $lab 'tmp')) '-cp' ($classes+';D:\SteamLibrary\steamapps\common\Mindustry\jre\desktop.jar') 'ProfileVideoSettings' $root $profile $setting
    if($LASTEXITCODE -ne 0){throw 'Could not prepare synchronized copy'}
}
if($PrepareOnly){return}
$game='D:\SteamLibrary\steamapps\common\Mindustry'
$arguments=@('-Dnodiscord=true','-Dfpv.cameraProfile=true','-Dfpv.cameraProfile.live=true','-Dfpv.visualProfile=true',('-Dfpv.cameraProfile.output="'+$lab+'"'),('-Dfpv.lab.path="'+$lab+'"'),('-Djava.io.tmpdir="'+(Join-Path $lab 'tmp')+'"'),('-Dmindustry.data.dir="'+$profile+'"'),('-Djava.library.path="'+$game+'"'),('-XX:StartFlightRecording=name=fpv-camera-profile,settings=profile,maxsize=128m,filename="'+(Join-Path $lab 'frames.jfr')+'",dumponexit=true'),'-cp',('"'+(Join-Path $game 'jre\desktop.jar')+'"'),'mindustry.desktop.DesktopLauncher','-width','1280','-height','800','-maximized','false')
if($NoGpu){$arguments=@('-Dfpv.cameraProfile.gpu=false')+$arguments}
if($InstalledBaseline){
    # Untouched accepted JAR, no Java-level hooks or GPU timer queries. Bounded JFR ends while the game remains open.
    $arguments=$arguments | Where-Object {$_ -notmatch '^-Dfpv\.(cameraProfile|visualProfile)' -and $_ -notmatch '^-XX:StartFlightRecording='}
    $arguments=@(('-XX:StartFlightRecording=name=fpv-camera-profile,settings=default,duration=60s,maxsize=32m,filename="'+(Join-Path $lab 'frames.jfr')+'"'))+$arguments
}
# This launcher is explicitly invoked by the player for interactive capture, not a background helper.
$process=Start-Process -FilePath 'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\javaw.exe' -ArgumentList $arguments -WorkingDirectory $lab -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $lab 'stdout.log') -RedirectStandardError (Join-Path $lab 'stderr.log')
$process.Id | Set-Content -LiteralPath (Join-Path $lab 'process.pid')
if($InstalledBaseline){
    $sensorArguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $PSScriptRoot 'record-system-profile.ps1')+'"'),'-GamePid',[string]$process.Id,'-Output',('"'+$lab+'"'))
    Start-Process -FilePath 'powershell.exe' -ArgumentList $sensorArguments -WindowStyle Hidden | Out-Null
}
Write-Output 'Open the same saved game and reproduce the slowdown. Exit normally to finish the recording.'
if($PassThru){[pscustomobject]@{GamePid=$process.Id;RunRoot=$lab}}
