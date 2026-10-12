param([ValidateSet('repair','windowed','restore')][string]$Mode='repair',[string]$GameDir='D:\SteamLibrary\steamapps\common\Mindustry')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$willWrite=$Mode -ne 'repair' -or -not(Test-Path -LiteralPath (Join-Path $root 'play\presentation-mode.json'))
if($willWrite){
    $live=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -like '*mindustry.desktop.DesktopLauncher*' -and $_.CommandLine -like ('*'+(Join-Path $root 'play\saves')+'*')})
    if($live.Count){throw 'Close the ordinary Play-FPV game before changing its presentation preference; no settings were written.'}
}
$source=Join-Path $PSScriptRoot 'ProfileVideoSettings.java'
$classes=Join-Path $root 'local\profile-video-tool'
$helperTmp=Join-Path $classes 'tmp'
New-Item -ItemType Directory -Path $helperTmp -Force | Out-Null
$stamp=Join-Path $classes 'source.sha256'
$hash=(Get-FileHash -LiteralPath $source).Hash
$jdk='D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin'
$hostJar=Join-Path $GameDir 'jre\desktop.jar'
if(-not(Test-Path -LiteralPath $stamp) -or (Get-Content -LiteralPath $stamp -Raw).Trim() -ne $hash -or -not(Test-Path -LiteralPath (Join-Path $classes 'ProfileVideoSettings.class'))){
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & (Join-Path $jdk 'javac.exe') ('-J-Djava.io.tmpdir='+$helperTmp) --release 17 -encoding UTF-8 -cp $hostJar -d $classes $source
    if($LASTEXITCODE -ne 0){throw 'Could not build the presentation preference helper'}
    Set-Content -LiteralPath $stamp -Value $hash -Encoding ascii
}
& (Join-Path $jdk 'java.exe') ('-Djava.io.tmpdir='+$helperTmp) -cp ($classes+';'+$hostJar) ProfileVideoSettings $root (Join-Path $root 'play\saves') $Mode
if($LASTEXITCODE -ne 0){throw 'Presentation preference update failed; inspect the saved backup'}
