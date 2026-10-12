# Isolated regression fixtures for import aliases. Never opens an actual profile.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$projectRoot=Split-Path $PSScriptRoot -Parent
$fixture=Join-Path $projectRoot ('local\profile-installer-tests\'+[guid]::NewGuid().ToString('N'))
$mods=Join-Path $fixture 'profile\saves\mods'
$backups=Join-Path $fixture 'backups'
$installer=Join-Path $PSScriptRoot 'install-profile-mod.ps1'
New-Item -ItemType Directory -Path $mods -Force | Out-Null
$script:checks=0
function Check([bool]$condition,[string]$message) {
    $script:checks++
    if(-not $condition){throw "Profile installer: $message"}
}
function New-Mod([string]$path,[string]$metadata,[string]$metaName='mod.hjson',[string]$payload='old') {
    $archive=[System.IO.Compression.ZipFile]::Open($path,[System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $payloadPath=if($metaName.Contains('/')){$metaName.Substring(0,$metaName.LastIndexOf('/')+1)+'fpv/test-payload.txt'}else{'fpv/test-payload.txt'}
        foreach($pair in @(@($metaName,$metadata),@($payloadPath,$payload))) {
            $entry=$archive.CreateEntry($pair[0]);$writer=New-Object System.IO.StreamWriter($entry.Open())
            try {$writer.Write($pair[1])} finally {$writer.Dispose()}
        }
    } finally {$archive.Dispose()}
}
function Hash([string]$path){return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
$old="name: mindustry-fpv`nmain: fpv.FpvMod`nversion: 0.12.0`n"
$new="name: mindustry-fpv`nmain: fpv.FpvMod`nversion: 0.12.1`n"
$source=Join-Path $fixture 'release.jar'; New-Mod $source $new 'mod.hjson' 'new'
$canonical=Join-Path $mods 'mindustry-fpv.jar'; New-Mod $canonical $old
$duplicate=Join-Path $mods 'mindustry-fpv-0.12.0.zip'; New-Mod $duplicate $old
$renamed=Join-Path $mods 'user-renamed.zip'; New-Mod $renamed '{"name":"mindustry-fpv","main":"fpv.FpvMod","version":"0.12.0"}' 'mod.json'
$wrapped=Join-Path $mods 'wrapped[1].zip'; New-Mod $wrapped "name: `"mindustry-fpv`"`nmain: `"fpv.FpvMod`"`n" 'one-directory/mod.hjson'
$unrelated=Join-Path $mods 'mindustry-fpv-unrelated.zip'; New-Mod $unrelated "name: other-mod`nmain: other.Mod`n"
$wrongMain=Join-Path $mods 'fpv-different-main.zip'; New-Mod $wrongMain "name: mindustry-fpv`nmain: other.Mod`n"
$invalid=Join-Path $mods 'invalid.zip'; [IO.File]::WriteAllText($invalid,'not a zip')
$ambiguous=Join-Path $mods 'multiline-example.zip'; New-Mod $ambiguous "`"name`": other-mod`n`"main`": other.Mod`ndescription: '''`nname: mindustry-fpv`nmain: fpv.FpvMod`n'''`n"
$save=Join-Path (Split-Path $mods -Parent) 'settings.bin'; [IO.File]::WriteAllText($save,'preserve settings bytes')
$guard=@{};foreach($path in @($unrelated,$wrongMain,$invalid,$ambiguous,$save)){$guard[$path]=Hash $path}
$oldHashes=@{};foreach($path in @($canonical,$duplicate,$renamed,$wrapped)){$oldHashes[(Split-Path $path -Leaf)]=Hash $path}
$result=& $installer -SourceJar $source -ModsDirectory $mods -BackupRoot $backups
Check ($result.archived -eq 4) 'all exact-identity stale copies archived'
Check ((Hash $canonical) -eq (Hash $source)) 'new canonical release verified'
Check (-not (Test-Path -LiteralPath $duplicate)) 'import alias no longer competes'
Check (-not (Test-Path -LiteralPath $renamed)) 'renamed JSON archive recognized by identity'
Check (-not (Test-Path -LiteralPath $wrapped)) 'single-directory quoted HJSON alias and literal bracket filename recognized'
foreach($name in $oldHashes.Keys){Check ((Hash (Join-Path $result.backup $name)) -eq $oldHashes[$name]) "archive bytes preserved: $name"}
foreach($path in $guard.Keys){Check ((Hash $path) -eq $guard[$path]) "unrelated bytes untouched: $path"}
$receipt=Get-Content -LiteralPath (Join-Path $result.backup 'manifest.json') -Raw | ConvertFrom-Json
Check (@($receipt.files | Where-Object {-not $_.archived}).Count -eq 0) 'receipt records all verified moves'
$second=& $installer -SourceJar $source -ModsDirectory $mods -BackupRoot $backups
Check (-not $second.changed -and $second.archived -eq 0) 'second installation is idempotent'
Check (@(Get-ChildItem -LiteralPath $mods -Filter '*.pending').Count -eq 0) 'no staging leftovers'

# Known-good canonical must be preserved when only aliases need recovering.
$alias=Join-Path $mods 'another-import.zip'; Copy-Item -LiteralPath $source -Destination $alias
$same=& $installer -SourceJar $source -ModsDirectory $mods -BackupRoot $backups
Check ($same.archived -eq 1 -and (Hash $canonical) -eq (Hash $source)) 'same-version alias cleanup keeps canonical bytes'

# Refuse a wrong release before changing any existing mod.
$before=Hash $canonical;$rejected=$false
try {& $installer -SourceJar $unrelated -ModsDirectory $mods -BackupRoot $backups | Out-Null} catch {$rejected=$true}
Check $rejected 'wrong release identity rejected'
Check ((Hash $canonical) -eq $before) 'wrong release leaves profile untouched'
$rejected=$false
try {& $installer -SourceJar $source -ModsDirectory $mods -BackupRoot (Join-Path $mods 'backups') | Out-Null} catch {$rejected=$true}
Check $rejected 'active mod directory cannot contain archived candidates'

# Never overwrite an unrelated file merely because it uses the canonical filename.
$collisionMods=Join-Path $fixture 'collision\mods';New-Item -ItemType Directory -Path $collisionMods -Force | Out-Null
$collision=Join-Path $collisionMods 'mindustry-fpv.jar';Copy-Item -LiteralPath $unrelated -Destination $collision
$before=Hash $collision;$rejected=$false
try {& $installer -SourceJar $source -ModsDirectory $collisionMods -BackupRoot $backups | Out-Null} catch {$rejected=$true}
Check $rejected 'foreign canonical filename rejected'
Check ((Hash $collision) -eq $before) 'foreign canonical file preserved'
Write-Output "Profile installer: $script:checks checks passed. Fixtures: $fixture"
