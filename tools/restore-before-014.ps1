param([string]$Destination,[switch]$CheckOnly)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskReceipt=Get-Content -LiteralPath (Join-Path $taskRoot 'local\before-0.14.json') -Raw | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'restore-before-layers.ps1') -BackupDirectory $taskReceipt.backup -Destination $Destination -CheckOnly:$CheckOnly
