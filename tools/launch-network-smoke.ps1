param([Parameter(Mandatory=$true)][string]$RunId,[int]$Port=16567,[string]$ModJar,[switch]$XyzPower,[switch]$NavalProductionOnly,
      [ValidateSet('160.5','160.7')][string]$HostVersion='160.7')
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[a-zA-Z0-9-]+$'){throw 'Use a simple unique run ID'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$runRoot=Join-Path $projectRoot ('local\net-lab\'+$RunId)
if(Test-Path -LiteralPath $runRoot){throw 'This run already exists; choose a fresh ID'}
$javaExe='D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe'
$hostJar=Join-Path $projectRoot ('local\server-runtime\Mindustry-v'+$HostVersion+'.jar')
$serverJar=Join-Path $projectRoot ('local\server-runtime\server-release-v'+$HostVersion+'.jar')
$modJar=if($ModJar){(Resolve-Path -LiteralPath $ModJar).Path}else{Join-Path $projectRoot 'dist\mindustry-fpv-0.18.27.jar'}
foreach($file in @($javaExe,$hostJar,$serverJar,$modJar)){if(-not(Test-Path -LiteralPath $file)){throw "Missing $file"}}
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($runtime in @($hostJar,$serverJar)){
    $archive=[System.IO.Compression.ZipFile]::OpenRead($runtime)
    try{
        $entry=$archive.GetEntry('version.properties')
        if($null -eq $entry){throw "Missing version.properties in $runtime"}
        $reader=[System.IO.StreamReader]::new($entry.Open())
        try{$properties=$reader.ReadToEnd()}finally{$reader.Dispose()}
        $build=[regex]::Match($properties,'(?m)^build=([^\r\n]+)').Groups[1].Value
        $type=[regex]::Match($properties,'(?m)^type=([^\r\n]+)').Groups[1].Value
        if($build -ne $HostVersion -or $type -ne 'official'){throw "Expected official Mindustry $HostVersion, found '$type $build' in $runtime"}
    }finally{$archive.Dispose()}
}
New-Item -ItemType Directory -Path $runRoot | Out-Null
$records=@()
foreach($role in @('server','client-a','client-b')){
    $work=Join-Path $runRoot $role
    $data=Join-Path $work 'config'
    $tmp=Join-Path $work 'tmp'
    New-Item -ItemType Directory -Force -Path (Join-Path $data 'mods'),$tmp | Out-Null
    Copy-Item -LiteralPath $modJar -Destination (Join-Path $data 'mods\mindustry-fpv.jar')
    $arguments=@('-Dnodiscord=true',('-Djava.io.tmpdir="'+$tmp+'"'),('-Dmindustry.data.dir="'+$data+'"'),('-Dfpv.netSmokeRoot="'+$runRoot+'"'),('-Dfpv.netSmokePort='+$Port),('-Dfpv.netSmokeRole='+$role))
    if($XyzPower){$arguments+=@('-Dfpv.xyzPower.netSmoke=true')}
    if($NavalProductionOnly){$arguments+=@('-Dfpv.navalProduction.netSmoke=true')}
    if($role -eq 'server'){$arguments+=@('-jar',('"'+$serverJar+'"'))}
    else{$arguments+=@('-Djava.library.path="D:\SteamLibrary\steamapps\common\Mindustry"','-cp',('"'+$hostJar+'"'),'mindustry.desktop.DesktopLauncher','-width','960','-height','640','-maximized','false')}
    $process=Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $work -WindowStyle Hidden -Environment @{APPDATA=(Join-Path $work 'appdata');LOCALAPPDATA=(Join-Path $work 'appdata')} -PassThru -RedirectStandardOutput (Join-Path $work 'stdout.log') -RedirectStandardError (Join-Path $work 'stderr.log')
    $records+=@{role=$role;pid=$process.Id;profile=$data;startedUtc=$process.StartTime.ToUniversalTime().ToString('o')}
}
@{runRoot=$runRoot;gameVersion=$HostVersion;modSha256=(Get-FileHash -LiteralPath $modJar -Algorithm SHA256).Hash.ToLowerInvariant();
  desktopJar=$hostJar;desktopSha256=(Get-FileHash -LiteralPath $hostJar -Algorithm SHA256).Hash.ToLowerInvariant();
  serverJar=$serverJar;serverSha256=(Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash.ToLowerInvariant();
  processes=$records} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'processes.json')
Get-Content -LiteralPath (Join-Path $runRoot 'processes.json')
