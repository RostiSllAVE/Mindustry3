param(
    [Parameter(Mandatory=$true)][string]$SourceJar,
    [Parameter(Mandatory=$true)][string]$ModsDirectory,
    [Parameter(Mandatory=$true)][string]$BackupRoot
)
# Windows PowerShell 5.1 compatible. Imported game data can contain several files
# with the same native mod ID; Mindustry otherwise selects one by directory order.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Get-FpvModIdentity([string]$Path) {
    $archive=$null
    try {
        $archive=[System.IO.Compression.ZipFile]::OpenRead($Path)
        $prefix=''
        $meta=$null
        foreach($name in @('mod.json','mod.hjson','plugin.json','plugin.hjson')) {
            $meta=$archive.GetEntry($name)
            if($null -ne $meta){break}
        }
        # Match Mindustry's one enclosing directory convention without extracting.
        if($null -eq $meta) {
            $roots=@($archive.Entries | ForEach-Object {($_.FullName -split '/')[0]} | Select-Object -Unique)
            if($roots.Count -eq 1) {
                $prefix=$roots[0]+'/'
                foreach($name in @('mod.json','mod.hjson','plugin.json','plugin.hjson')) {
                    $meta=$archive.GetEntry($prefix+$name)
                    if($null -ne $meta){break}
                }
            }
        }
        if($null -eq $meta -or $meta.Length -gt 1048576){return $false}
        $reader=New-Object System.IO.StreamReader($meta.Open())
        try {$text=$reader.ReadToEnd()} finally {$reader.Dispose()}
        $name=$null; $main=$null
        try {
            $json=$text | ConvertFrom-Json -ErrorAction Stop
            $name=$json.name; $main=$json.main
        } catch {
            # Deliberately conservative HJSON recognition: only unambiguous flat
            # fields, as in our release manifest. Unsupported metadata is untouched.
            if($text.Contains("'''") -or $text.Contains('/*') -or $text.Contains('*/') -or $text.TrimStart().StartsWith('{')){return $false}
            $values=@{}
            foreach($key in @('name','main')) {
                $pattern='(?m)^[ \t]*'+$key+'[ \t]*:[ \t]*(?:"(?<quoted>[^"\r\n]*)"|(?<bare>[^\r\n,#]+?))[ \t]*(?:,?[ \t]*(?:#[^\r\n]*)?)?$'
                $matches=[regex]::Matches($text.Replace("`r",''),$pattern)
                if($matches.Count -ne 1){return $false}
                $match=$matches[0]
                $values[$key]=if($match.Groups['quoted'].Success){$match.Groups['quoted'].Value}else{$match.Groups['bare'].Value.Trim()}
            }
            $name=$values['name']; $main=$values['main']
        }
        return ($name -is [string] -and $name.ToLowerInvariant().Replace(' ','-') -ceq 'mindustry-fpv' -and $main -ceq 'fpv.FpvMod')
    } catch {return $false}
    finally {if($null -ne $archive){$archive.Dispose()}}
}

function Assert-PlainDirectory([string]$Path) {
    $cursor=[System.IO.Path]::GetFullPath($Path)
    while($cursor) {
        if(Test-Path -LiteralPath $cursor) {
            $item=Get-Item -LiteralPath $cursor -Force
            if($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint){throw "Refusing linked profile/archive directory: $cursor"}
        }
        $parent=[System.IO.Directory]::GetParent($cursor)
        $cursor=if($null -eq $parent){$null}else{$parent.FullName}
    }
}

$source=[System.IO.Path]::GetFullPath($SourceJar)
$mods=[System.IO.Path]::GetFullPath($ModsDirectory).TrimEnd('\','/')
$backup=[System.IO.Path]::GetFullPath($BackupRoot).TrimEnd('\','/')
Assert-PlainDirectory $mods
Assert-PlainDirectory $backup
if($backup.Equals($mods,[StringComparison]::OrdinalIgnoreCase) -or $backup.StartsWith($mods+'\',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Mod backups must be outside the active mods directory.'
}
if(-not (Get-FpvModIdentity $source)){throw "Release is not the expected Mindustry FPV Java mod: $source"}
$sourceHash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
New-Item -ItemType Directory -Path $mods -Force | Out-Null
$canonical=Join-Path $mods 'mindustry-fpv.jar'
$changes=New-Object 'System.Collections.Generic.List[object]'
foreach($file in Get-ChildItem -LiteralPath $mods -File -Force) {
    if($file.Extension -notin @('.jar','.zip')){continue}
    $owned=Get-FpvModIdentity $file.FullName
    if($file.FullName.Equals($canonical,[StringComparison]::OrdinalIgnoreCase) -and -not $owned) {
        throw "The canonical FPV filename contains an unrecognized file; preserved: $canonical"
    }
    if(-not $owned){continue}
    if($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint){throw "Refusing linked mod file: $($file.FullName)"}
    $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if($file.FullName.Equals($canonical,[StringComparison]::OrdinalIgnoreCase) -and $hash -eq $sourceHash){continue}
    $changes.Add([pscustomobject]@{source=$file.FullName;name=$file.Name;sha256=$hash;archived=$false})
}
$alreadyInstalled=(Test-Path -LiteralPath $canonical -PathType Leaf) -and ((Get-FileHash -LiteralPath $canonical -Algorithm SHA256).Hash -eq $sourceHash)
if($changes.Count -eq 0 -and $alreadyInstalled) {
    Write-Output ([pscustomobject]@{installed=$canonical;sha256=$sourceHash;archived=0;backup=$null;changed=$false})
    return
}
$stage=Join-Path $mods ('.fpv-install-'+[guid]::NewGuid().ToString('N')+'.pending')
$archiveRoot=$null
try {
    Copy-Item -LiteralPath $source -Destination $stage
    if((Get-FileHash -LiteralPath $stage -Algorithm SHA256).Hash -ne $sourceHash -or -not (Get-FpvModIdentity $stage)) {
        throw 'Staged FPV release failed verification; active mods were not changed.'
    }
    if($changes.Count -gt 0) {
        $archiveRoot=Join-Path $backup ('mod-imports-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
        if(-not [System.IO.Path]::GetFullPath($archiveRoot).StartsWith($backup+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Archive escaped backup directory.'}
        New-Item -ItemType Directory -Path $archiveRoot | Out-Null
        $receipt=[ordered]@{installed=$canonical;releaseSha256=$sourceHash;files=$changes}
        $receiptPath=Join-Path $archiveRoot 'manifest.json'
        $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding UTF8
        foreach($entry in $changes) {
            $original=[System.IO.Path]::GetFullPath($entry.source)
            $destination=[System.IO.Path]::GetFullPath((Join-Path $archiveRoot $entry.name))
            if([System.IO.Path]::GetDirectoryName($original) -ine $mods -or [System.IO.Path]::GetDirectoryName($destination) -ine $archiveRoot){throw 'Mod archive paths escaped their exact directories.'}
            if((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash -ne $entry.sha256){throw "Mod changed during installation; preserved: $original"}
            Move-Item -LiteralPath $original -Destination $destination
            if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $entry.sha256){throw "Archived mod failed hash verification: $destination"}
            $entry.archived=$true
            $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding UTF8
        }
    }
    if(-not $alreadyInstalled){Move-Item -LiteralPath $stage -Destination $canonical}
    if((Get-FileHash -LiteralPath $canonical -Algorithm SHA256).Hash -ne $sourceHash){throw 'Installed FPV release failed hash verification.'}
    Write-Output ([pscustomobject]@{installed=$canonical;sha256=$sourceHash;archived=$changes.Count;backup=$archiveRoot;changed=$true})
} finally {
    # This is only our individually named, non-loadable staging file.
    if(Test-Path -LiteralPath $stage -PathType Leaf){Remove-Item -LiteralPath $stage}
}
