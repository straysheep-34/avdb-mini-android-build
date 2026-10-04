$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tools = Join-Path $Root '.android-build'
$GradleVersion = '9.5.0'
$CmdTools = 'commandlinetools-win-15859902_latest.zip'
$GradleZip = "gradle-$GradleVersion-bin.zip"
$Sdk = Join-Path $Tools 'sdk'
$GradleHome = Join-Path $Tools "gradle-$GradleVersion"
$PortableJdkRoot = Join-Path $Tools 'jdk21'
$PortableJdkZip = Join-Path $Tools 'temurin-jdk21.zip'
$PortableJdkPartial = Join-Path $Tools 'temurin-jdk21.download'

function Download-FileAtomic($url, $path) {
    if (Test-Path $path) { return }
    $partial = "$path.partial"
    Remove-Item -Force -ErrorAction SilentlyContinue $partial
    Write-Host "Downloading: $url" -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 10 -Uri $url -OutFile $partial
    if (!(Test-Path $partial) -or (Get-Item $partial).Length -lt 1048576) {
        Remove-Item -Force -ErrorAction SilentlyContinue $partial
        throw "Download failed or returned an invalid file: $url"
    }
    Move-Item -Force $partial $path
}

function Download-IfMissing($url, $path) {
    Download-FileAtomic $url $path
}

function Probe-Java([string]$javaExe) {
    if ([string]::IsNullOrWhiteSpace($javaExe) -or !(Test-Path $javaExe)) { return $null }
    try {
        # IMPORTANT: `java -version` writes its version text to STDERR.
        # Windows PowerShell 5.1 + $ErrorActionPreference='Stop' can treat
        # native STDERR as an exception when invoked through `& ... 2>&1`.
        # Use ProcessStartInfo instead so a perfectly valid JDK is never
        # misdetected as missing.
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $javaExe
        $psi.Arguments = '-version'
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true

        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        [void]$proc.Start()
        $stdout = $proc.StandardOutput.ReadToEnd()
        $stderr = $proc.StandardError.ReadToEnd()
        $proc.WaitForExit()

        $text = (($stderr + "`n" + $stdout).Trim())
        if ([string]::IsNullOrWhiteSpace($text)) { return $null }
        $line = ($text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1).Trim()

        if ($text -match 'version\s+"?([0-9]+)(?:\.([0-9]+))?') {
            $major = [int]$Matches[1]
            if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
            return [PSCustomObject]@{
                Exe = $javaExe
                Major = $major
                VersionLine = $line
                ExitCode = $proc.ExitCode
            }
        }
    } catch {
        Write-Host "Java probe failed for: $javaExe" -ForegroundColor DarkYellow
        Write-Host $_.Exception.Message -ForegroundColor DarkYellow
    }
    return $null
}

function Get-JavaMajor([string]$javaExe) {
    $probe = Probe-Java $javaExe
    if ($probe) { return $probe.Major }
    return 0
}

function Try-Java([string]$javaExe) {
    $probe = Probe-Java $javaExe
    if ($probe -and $probe.Major -ge 17) { return $probe }
    return $null
}

function Find-Java17Plus {
    # 1) JAVA_HOME
    if ($env:JAVA_HOME) {
        $r = Try-Java (Join-Path $env:JAVA_HOME 'bin\java.exe')
        if ($r) { return $r }
    }

    # 2) PATH
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        $r = Try-Java $cmd.Source
        if ($r) { return $r }
    }

    # 3) Common Windows JDK/JBR install locations
    $fixed = @(
        'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
        'C:\Program Files\Android\Android Studio\jre\bin\java.exe'
    )
    foreach ($p in $fixed) {
        $r = Try-Java $p
        if ($r) { return $r }
    }

    $roots = @(
        'C:\Program Files\Microsoft',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Java',
        'C:\Program Files\Amazon Corretto',
        'C:\Program Files\Zulu'
    )
    foreach ($root in $roots) {
        if (!(Test-Path $root)) { continue }
        $candidates = Get-ChildItem -Path $root -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\bin\\java\.exe$' }
        foreach ($c in $candidates) {
            $r = Try-Java $c.FullName
            if ($r) { return $r }
        }
    }
    return $null
}

function Ensure-Java17Plus {
    $found = Find-Java17Plus
    if (!$found) {
        Write-Host ''
        Write-Host 'Java 17+ was not found. A private portable JDK 21 will be prepared.' -ForegroundColor Yellow
        Write-Host 'Nothing will be installed system-wide and PATH will not be modified permanently.' -ForegroundColor DarkYellow

        New-Item -ItemType Directory -Force -Path $Tools | Out-Null
        $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x64' }
        $jdkUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$arch/jdk/hotspot/normal/eclipse"

        # If a previous run left an invalid JDK folder behind, remove it automatically.
        $existing = Try-Java (Join-Path $PortableJdkRoot 'bin\java.exe')
        if (!$existing -and (Test-Path $PortableJdkRoot)) {
            Write-Host 'Removing incomplete portable JDK from a previous run...' -ForegroundColor DarkYellow
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $PortableJdkRoot
        }

        if (!(Test-Path $PortableJdkRoot)) {
            # Reuse a valid ZIP when possible; if it cannot be opened, delete and redownload it.
            $needDownload = !(Test-Path $PortableJdkZip)
            if (!$needDownload) {
                try {
                    Add-Type -AssemblyName System.IO.Compression.FileSystem
                    $z = [System.IO.Compression.ZipFile]::OpenRead($PortableJdkZip)
                    $hasJava = $false
                    foreach ($e in $z.Entries) {
                        if ($e.FullName -match '/bin/java\.exe$' -or $e.FullName -match '\\bin\\java\.exe$') { $hasJava = $true; break }
                    }
                    $z.Dispose()
                    if (!$hasJava) { $needDownload = $true }
                } catch {
                    $needDownload = $true
                }
                if ($needDownload) {
                    Write-Host 'Cached JDK archive is invalid. Downloading it again...' -ForegroundColor DarkYellow
                    Remove-Item -Force -ErrorAction SilentlyContinue $PortableJdkZip
                }
            }
            if (!(Test-Path $PortableJdkZip)) {
                Download-FileAtomic $jdkUrl $PortableJdkZip
            }

            $tmpJdk = Join-Path $Tools 'jdk21-extract'
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmpJdk
            New-Item -ItemType Directory -Force -Path $tmpJdk | Out-Null
            Write-Host 'Extracting portable JDK 21...' -ForegroundColor Cyan
            try {
                Expand-Archive -Force $PortableJdkZip $tmpJdk
            } catch {
                Remove-Item -Force -ErrorAction SilentlyContinue $PortableJdkZip
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmpJdk
                throw 'The downloaded JDK archive could not be extracted. Run the builder again; it will download a fresh copy.'
            }

            # Do not assume a fixed archive folder name. Find the real JDK root recursively.
            $java = Get-ChildItem -Path $tmpJdk -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
                Select-Object -First 1
            if (!$java) {
                Remove-Item -Force -ErrorAction SilentlyContinue $PortableJdkZip
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmpJdk
                throw 'Portable JDK archive was extracted, but bin\java.exe was not found. The cached ZIP was removed; run again to redownload it.'
            }

            $jdkHome = $java.Directory.Parent.FullName
            $major = Get-JavaMajor $java.FullName
            if ($major -lt 17) {
                Remove-Item -Force -ErrorAction SilentlyContinue $PortableJdkZip
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmpJdk
                throw "Downloaded Java is version $major, but Java 17+ is required."
            }

            Write-Host "Portable JDK found: $jdkHome" -ForegroundColor Green
            # Copy rather than Move so a partially-existing destination cannot create an unexpected nested directory.
            New-Item -ItemType Directory -Force -Path $PortableJdkRoot | Out-Null
            Copy-Item -Recurse -Force (Join-Path $jdkHome '*') $PortableJdkRoot
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmpJdk
        }

        $found = Try-Java (Join-Path $PortableJdkRoot 'bin\java.exe')
        if (!$found) {
            Write-Host 'Portable JDK validation failed. Cleaning the broken local JDK automatically...' -ForegroundColor Red
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $PortableJdkRoot
            throw 'Could not validate portable Java 17+. Run build-apk.bat once more; the broken local JDK has already been removed.'
        }
    }

    $javaHome = (Split-Path -Parent (Split-Path -Parent $found.Exe))
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
    $versionLine = if ($found.VersionLine) { $found.VersionLine } else { "Java major $($found.Major)" }
    Write-Host "Java OK: $versionLine" -ForegroundColor Green
    Write-Host "Java executable: $($found.Exe)" -ForegroundColor Green
    Write-Host "JAVA_HOME: $javaHome"
}

New-Item -ItemType Directory -Force -Path $Tools | Out-Null
Ensure-Java17Plus

$GradleZipPath = Join-Path $Tools $GradleZip
Download-IfMissing "https://services.gradle.org/distributions/$GradleZip" $GradleZipPath
if (!(Test-Path $GradleHome)) {
    Write-Host 'Extracting Gradle...' -ForegroundColor Cyan
    Expand-Archive -Force $GradleZipPath $Tools
}

$CmdZipPath = Join-Path $Tools $CmdTools
Download-IfMissing "https://dl.google.com/android/repository/$CmdTools" $CmdZipPath
$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (!(Test-Path $SdkManager)) {
    $tmp = Join-Path $Tools 'cmdline-extract'
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmp
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Write-Host 'Extracting Android command-line tools...' -ForegroundColor Cyan
    Expand-Archive -Force $CmdZipPath $tmp
    New-Item -ItemType Directory -Force -Path (Join-Path $Sdk 'cmdline-tools\latest') | Out-Null
    Copy-Item -Recurse -Force (Join-Path $tmp 'cmdline-tools\*') (Join-Path $Sdk 'cmdline-tools\latest')
}

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:PATH = "$(Join-Path $Sdk 'platform-tools');$env:PATH"

Write-Host 'Accepting Android SDK licenses...' -ForegroundColor Cyan
1..40 | ForEach-Object { 'y' } | & $SdkManager --licenses | Out-Host

Write-Host 'Installing Android SDK 36...' -ForegroundColor Cyan
& $SdkManager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }

Write-Host 'Building AVDB Mini Gecko APK. First build downloads GeckoView (~230 MB)...' -ForegroundColor Cyan
$Gradle = Join-Path $GradleHome 'bin\gradle.bat'
Push-Location $Root
try {
    & $Gradle --no-daemon :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
} finally {
    Pop-Location
}

$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$Out = Join-Path $Root 'AVDB-Mini-Android-Gecko-v2.0.3.apk'
if (!(Test-Path $Apk)) { throw 'Build completed but APK was not found.' }
Copy-Item -Force $Apk $Out
Write-Host ''
Write-Host "SUCCESS: $Out" -ForegroundColor Green
