param(
    [string]$Username,
    [int]$Port = 6000
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$mavenWrapper = Join-Path $projectDirectory 'mvnw.cmd'
$serverPort = $Port
$logDirectory = Join-Path $env:LOCALAPPDATA 'Reda_App\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$launchId = [Guid]::NewGuid().ToString('N')

function Test-LocalPort {
    param([int]$Port)

    $connection = New-Object System.Net.Sockets.TcpClient
    try {
        $result = $connection.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(350)) {
            return $false
        }
        $connection.EndConnect($result)
        return $true
    } catch {
        return $false
    } finally {
        $connection.Close()
    }
}

if (-not (Test-Path $mavenWrapper)) {
    throw "Le lanceur Maven est introuvable : $mavenWrapper"
}

$hasJavaHome = -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)
if ($hasJavaHome) {
    $hasJavaHome = Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')
}

if (-not $hasJavaHome) {
    $jdk21Candidates = @(
        Get-ChildItem -Path `
            (Join-Path $env:ProgramFiles 'Java\jdk-21*'), `
            (Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-21*') `
            -Directory -ErrorAction SilentlyContinue
    )
    $jdk21 = $jdk21Candidates | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk21) {
        $env:JAVA_HOME = $jdk21.FullName
        $hasJavaHome = $true
    }
}

if ($hasJavaHome) {
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
}

$java = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $java) {
    throw 'Java 21 ou supérieur est nécessaire. Installez Java puis relancez Start-RedaApp.bat.'
}

$env:REDA_APP_PORT = [string]$serverPort

if (-not (Test-LocalPort $serverPort)) {
    Write-Host 'Démarrage du serveur Reda_App...'
    $serverCommand = '""' + $mavenWrapper + '" javafx:run -Pserver"'
    $serverProcess = Start-Process -FilePath $env:ComSpec `
        -ArgumentList @('/c', $serverCommand) `
        -WorkingDirectory $projectDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDirectory "$launchId-server.log") `
        -RedirectStandardError (Join-Path $logDirectory "$launchId-server-error.log") `
        -PassThru

    $serverStarted = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        Start-Sleep -Seconds 1
        if (Test-LocalPort $serverPort) {
            $serverStarted = $true
            break
        }
        if ($serverProcess.HasExited) {
            break
        }
    }
    if (-not $serverStarted) {
        $errorLog = Join-Path $logDirectory "$launchId-server-error.log"
        $details = if (Test-Path $errorLog) { Get-Content $errorLog -Raw } else { '' }
        throw "Le serveur n'a pas démarré sur le port $serverPort. Journaux : $logDirectory`n$details"
    }
} else {
    Write-Host 'Serveur déjà démarré : lancement d''un nouvel utilisateur.'
}

if (-not [string]::IsNullOrWhiteSpace($Username)) {
    $env:REDA_APP_USERNAME = $Username
} else {
    Remove-Item Env:\REDA_APP_USERNAME -ErrorAction SilentlyContinue
}

Write-Host 'Ouverture de Reda_App. Fermez la fenêtre de discussion pour quitter cet utilisateur.'
$clientCommand = '""' + $mavenWrapper + '" javafx:run"'
$clientProcess = Start-Process -FilePath $env:ComSpec `
    -ArgumentList @('/c', $clientCommand) `
    -WorkingDirectory $projectDirectory `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDirectory "$launchId-client.log") `
    -RedirectStandardError (Join-Path $logDirectory "$launchId-client-error.log") `
    -PassThru

Start-Sleep -Seconds 3
if ($clientProcess.HasExited -and $clientProcess.ExitCode -ne 0) {
    $errorLog = Join-Path $logDirectory "$launchId-client-error.log"
    $details = if (Test-Path $errorLog) { Get-Content $errorLog -Raw } else { '' }
    throw "Le client n'a pas démarré. Journaux : $logDirectory`n$details"
}
