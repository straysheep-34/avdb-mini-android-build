$ErrorActionPreference = 'Continue'
Write-Host 'AVDB Mini - Java diagnostic' -ForegroundColor Cyan
Write-Host '=============================' -ForegroundColor Cyan
Write-Host ''
Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "PATH java =" -NoNewline
$cmd = Get-Command java.exe -ErrorAction SilentlyContinue
if ($cmd) { Write-Host " $($cmd.Source)" -ForegroundColor Green } else { Write-Host ' NOT FOUND' -ForegroundColor Red }
Write-Host ''

$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
if ($cmd) { $candidates += $cmd.Source }
$candidates = $candidates | Select-Object -Unique

foreach ($javaExe in $candidates) {
    Write-Host "Testing: $javaExe" -ForegroundColor Yellow
    if (!(Test-Path $javaExe)) {
        Write-Host '  File does not exist.' -ForegroundColor Red
        continue
    }
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $javaExe
        $psi.Arguments = '-version'
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $p = New-Object System.Diagnostics.Process
        $p.StartInfo = $psi
        [void]$p.Start()
        $out = $p.StandardOutput.ReadToEnd()
        $err = $p.StandardError.ReadToEnd()
        $p.WaitForExit()
        $text = ($err + "`n" + $out).Trim()
        Write-Host "  Exit code: $($p.ExitCode)"
        Write-Host "  Output: $text"
        if ($text -match 'version\s+"?([0-9]+)(?:\.([0-9]+))?') {
            $major=[int]$Matches[1]
            if ($major -eq 1 -and $Matches[2]) { $major=[int]$Matches[2] }
            Write-Host "  Detected Java major: $major" -ForegroundColor Green
            if ($major -ge 17) { Write-Host '  RESULT: OK (Java 17+)' -ForegroundColor Green }
            else { Write-Host '  RESULT: TOO OLD' -ForegroundColor Red }
        } else {
            Write-Host '  RESULT: Could not parse version.' -ForegroundColor Red
        }
    } catch {
        Write-Host "  RESULT: ERROR - $($_.Exception.Message)" -ForegroundColor Red
    }
    Write-Host ''
}
Read-Host 'Press Enter to close'
