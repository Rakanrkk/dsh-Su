#Requires -Version 7
<#
.SYNOPSIS
Run a Bash script inside WSL from PowerShell 7 without shell re-quoting.

.DESCRIPTION
Script text is sent as UTF-8 without a BOM; child stdout and stderr pass
through unchanged, and the Bash exit code becomes the script exit code.

.EXAMPLE
./tooling/Invoke-WslScript.ps1 -Distro Ubuntu-24.04 -WorkingDirectory '~/projects/dsh-Go' -ScriptFile ./setup.sh

.EXAMPLE
./tooling/Invoke-WslScript.ps1 -Distro Ubuntu-24.04 -WorkingDirectory /tmp -Script 'printf ''%s\n'' "中文 $value"; exit 2'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$Distro,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$WorkingDirectory,

    [ValidateNotNullOrEmpty()]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ScriptFile,

    [ValidateNotNull()]
    [string]$Script
)

if ($PSBoundParameters.ContainsKey('ScriptFile') -eq $PSBoundParameters.ContainsKey('Script')) {
    throw 'Specify either ScriptFile or Script.'
}

if ($PSBoundParameters.ContainsKey('ScriptFile')) {
    $scriptPath = (Resolve-Path -LiteralPath $ScriptFile -ErrorAction Stop).ProviderPath
    $Script = [System.IO.File]::ReadAllText($scriptPath)
}

$scriptText = $Script.Replace("`r`n", "`n").Replace("`r", "`n")
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$wslCommand = @(Get-Command -Name 'wsl.exe' -CommandType Application -ErrorAction Stop)[0]
$wslPath = $wslCommand.Source

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $wslPath
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.StandardInputEncoding = $utf8NoBom
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.ArgumentList.Add('--distribution')
$startInfo.ArgumentList.Add($Distro)
$startInfo.ArgumentList.Add('--cd')
$startInfo.ArgumentList.Add($WorkingDirectory)
$startInfo.ArgumentList.Add('--exec')
$startInfo.ArgumentList.Add('bash')
$startInfo.ArgumentList.Add('-s')

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
if (-not $process.Start()) {
    throw "Failed to start $wslPath."
}

try {
    $standardInput = $process.StandardInput
    $standardInput.Write($scriptText)
    $standardInput.Flush()
    $standardInput.Dispose()

    $standardOutputTask = $process.StandardOutput.ReadToEndAsync()
    $standardErrorTask = $process.StandardError.ReadToEndAsync()
    $standardOutput = [string]$standardOutputTask.Result
    $standardError = [string]$standardErrorTask.Result
    $process.WaitForExit()
    $exitCode = $process.ExitCode
}
finally {
    if ($standardOutput) { [Console]::Out.Write($standardOutput) }
    if ($standardError) { [Console]::Error.Write($standardError) }
    $process.Dispose()
}

$global:LASTEXITCODE = $exitCode
exit $exitCode
