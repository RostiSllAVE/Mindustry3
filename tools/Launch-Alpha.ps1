param(
    [Parameter(Mandatory=$true)][string]$GameDir,
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$ModJar,
    [string]$ProfileDirectory,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
# Explicit native module path also works when a parent PowerShell 7 process
# supplies incompatible modules in PSModulePath to Windows PowerShell 5.1.
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
if(-not $ModJar){$ModJar=Join-Path $PSScriptRoot 'mindustry-fpv-0.18.27.jar'}
if(-not $ProfileDirectory){$ProfileDirectory=Join-Path $PSScriptRoot 'alpha-profile'}
$expectedHash='D4F136AB7803966970AB50EF0D0150D895514AEBFF26DD343BD674C729582BC0'
if(-not $JavaHome){throw 'Supply -JavaHome with a complete 64-bit Java 17 installation, or set JAVA_HOME.'}
$hostJar=Join-Path $GameDir 'jre\desktop.jar'
$java=Join-Path $JavaHome 'bin\java.exe'
$javaw=Join-Path $JavaHome 'bin\javaw.exe'
foreach($path in @($hostJar,$java,$javaw,$ModJar)){
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Required file missing: $path"}
}
$hostJar=(Resolve-Path -LiteralPath $hostJar).Path
$GameDir=(Resolve-Path -LiteralPath $GameDir).Path
$ModJar=(Resolve-Path -LiteralPath $ModJar).Path
$ProfileDirectory=[IO.Path]::GetFullPath($ProfileDirectory)
if((Get-FileHash -LiteralPath $ModJar -Algorithm SHA256).Hash -ne $expectedHash){throw 'This launcher expects the exact tested Alpha JAR. Its checksum does not match.'}
# Windows PowerShell 5.1 turns Java's normal stderr version banner into an
# ErrorRecord. Capture it without treating a successful JVM as a failure.
$previousPreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    $runtime=& $java -XshowSettings:properties -version 2>&1 | ForEach-Object {"$_"}
    $runtimeExit=$LASTEXITCODE
} finally {$ErrorActionPreference=$previousPreference}
if($runtimeExit -ne 0 -or -not($runtime -match 'java.version\s*=\s*17\.') -or -not($runtime -match 'sun.arch.data.model\s*=\s*64')){
    throw 'The verified Alpha requires a complete 64-bit Java 17 runtime.'
}
$modules=& $java --list-modules
if($LASTEXITCODE -ne 0 -or -not($modules -match '^java.logging@')){throw 'Java runtime lacks java.logging, required by the native mouse dependency.'}
$mods=Join-Path $ProfileDirectory 'mods'
$target=Join-Path $mods 'mindustry-fpv-0.18.27.jar'
if(Test-Path -LiteralPath $mods){
    $archives=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$_.Extension -in @('.jar','.zip')})
    foreach($archive in $archives){
        if($archive.FullName -ne $target -or (Get-FileHash -LiteralPath $archive.FullName).Hash -ne $expectedHash){
            throw 'Choose a fresh Alpha profile directory. Existing mod archives will not be removed or replaced.'
        }
    }
}
if($ValidateOnly){Write-Output 'PASS: tested JAR checksum, host paths, complete 64-bit Java 17, and profile mod-conflict checks. No game launched or profile changed.';return}
$live=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -like '*mindustry.desktop.DesktopLauncher*' -and $_.CommandLine.Contains($ProfileDirectory)})
if($live.Count){throw 'This Alpha profile is already running. Close it before starting another instance.'}
$tmp=Join-Path $ProfileDirectory 'tmp'
New-Item -ItemType Directory -Force -Path $mods,$tmp | Out-Null
if(-not(Test-Path -LiteralPath $target)){Copy-Item -LiteralPath $ModJar -Destination $target}
# Identify the installed Steam host, without shipping its files or altering its runtime.
Set-Content -LiteralPath (Join-Path $ProfileDirectory 'steam_appid.txt') -Value '1127400' -Encoding ascii
$arguments=@('-Dnodiscord=true',('-Djava.io.tmpdir="'+$tmp+'"'),('-Djava.library.path="'+$GameDir+'"'),('-Dmindustry.data.dir="'+$ProfileDirectory+'"'),'-cp',('"'+$hostJar+'"'),'mindustry.desktop.DesktopLauncher')
# Explicit interactive launch: Normal prevents SDL's game window being hidden.
$process=Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $ProfileDirectory -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $ProfileDirectory 'stdout.log') -RedirectStandardError (Join-Path $ProfileDirectory 'stderr.log')
Write-Output "Alpha game started (PID $($process.Id)). Saves/settings remain in: $ProfileDirectory"
Write-Output 'Enable Fullscreen in the native graphics settings before testing 2D or vertical views.'
