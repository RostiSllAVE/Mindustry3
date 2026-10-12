param([string]$BackupDirectory,[string]$Destination,[switch]$CheckOnly)
$ErrorActionPreference='Stop'
$taskProject=Split-Path -Parent $PSScriptRoot
if(-not $BackupDirectory){$BackupDirectory=(Get-Content -LiteralPath (Join-Path $taskProject 'local\before-layered-3d.json') -Raw | ConvertFrom-Json).backup}
$taskBackup=(Resolve-Path -LiteralPath $BackupDirectory).Path
$taskManifest=Get-Content -LiteralPath (Join-Path $taskBackup 'manifest.json') -Raw | ConvertFrom-Json
$taskJars=@(Get-ChildItem -LiteralPath $taskBackup -Filter 'mindustry-fpv-*.jar' -File)
if($taskJars.Count -ne 1){throw 'Expected one versioned mod JAR in this verified backup'}
$taskJar=$taskJars[0].FullName
$taskVersion=[regex]::Match($taskJars[0].Name,'^mindustry-fpv-([0-9]+\.[0-9]+\.[0-9]+)\.jar$').Groups[1].Value
if(-not $taskVersion){throw 'Unexpected backup release filename'}
if((Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash -ine $taskManifest.releaseSha256){throw 'Backup release checksum mismatch'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $Destination){$Destination=Join-Path (Split-Path -Parent $taskProject) ('MindustryFPV-restored-'+$taskVersion+'-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))}
$taskDestination=[IO.Path]::GetFullPath($Destination)
if(-not $CheckOnly -and (Test-Path -LiteralPath $taskDestination)){throw 'Choose a NEW destination. Existing directories are never overwritten.'}
if(-not $CheckOnly){New-Item -ItemType Directory -Path $taskDestination | Out-Null}
$taskCount=0
foreach($archiveName in @('mod-source.zip','play-profile.zip')){
    $taskArchive=[IO.Compression.ZipFile]::OpenRead((Join-Path $taskBackup $archiveName))
    try{
        foreach($record in $taskManifest.files | Where-Object {$_.archive -eq $archiveName}){
            $taskEntry=$taskArchive.GetEntry($record.path)
            if($null -eq $taskEntry -or $taskEntry.Length -ne $record.bytes){throw "Missing or changed backup member: $($record.path)"}
            if($CheckOnly){$taskStream=$taskEntry.Open();$taskHasher=[Security.Cryptography.SHA256]::Create();try{$taskHash=[BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-','');if($taskHash -ine $record.sha256){throw "Backup checksum mismatch: $($record.path)"}}finally{$taskHasher.Dispose();$taskStream.Dispose()}}
            $taskTarget=[IO.Path]::GetFullPath((Join-Path $taskDestination $record.path))
            if(-not $taskTarget.StartsWith($taskDestination.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Archive path escaped the new destination'}
            if(-not $CheckOnly){
                New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($taskTarget)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry,$taskTarget,$false)
                if((Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -ine $record.sha256){throw "Restored checksum mismatch: $($record.path)"}
            }
            $taskCount++
        }
    }finally{$taskArchive.Dispose()}
}
if($CheckOnly){Write-Output "Backup release and $taskCount archive members verified with SHA-256. No files restored.";return}
$taskDist=Join-Path $taskDestination 'dist'
New-Item -ItemType Directory -Force -Path $taskDist | Out-Null
Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskDist $taskJars[0].Name)
if((Get-FileHash -LiteralPath (Join-Path $taskDist $taskJars[0].Name)).Hash -ine $taskManifest.releaseSha256){throw 'Restored release checksum mismatch'}
Copy-Item -LiteralPath (Join-Path $taskBackup 'baseline.git.bundle') -Destination (Join-Path $taskDestination 'baseline.git.bundle')
Set-Content -LiteralPath (Join-Path $taskDestination 'RESTORED-FROM.txt') -Value "Verified backup: $taskBackup`r`nRestored files: $taskCount`r`nOpen Play-FPV.cmd in THIS restored folder. The current project/profile was not modified."
Write-Output "Restored and verified $taskCount files into: $taskDestination"
Write-Output 'Open Play-FPV.cmd there. Your current project and current saves have been preserved separately.'
