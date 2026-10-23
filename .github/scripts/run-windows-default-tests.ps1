# Run only on a disposable Windows CI runner. No SDK production setup is performed.
$ErrorActionPreference = 'Stop'
$sourceRoot = (Get-Location).Path
$evidence = Join-Path $sourceRoot 'target/windows-default'
$root = Join-Path $env:SystemDrive ("sandbox-ci-" + [guid]::NewGuid().ToString('N'))
$user = 'SbxCi' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$created = $false
$process = $null
$exitCode = 1
New-Item -ItemType Directory -Path $root, $evidence -Force | Out-Null
try {
    # Export tracked source only; do not expose .git, runner credentials, or the runner's cache.
    $archive = Join-Path $root 'source.zip'
    git archive --format=zip "--output=$archive" HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Source archive failed' }
    $workspace = Join-Path $root 'source'
    Expand-Archive -LiteralPath $archive -DestinationPath $workspace
    $repository = Join-Path $root 'repository'
    $temp = Join-Path $root 'temp'
    New-Item -ItemType Directory -Path $temp -Force | Out-Null
    $maven = (Get-Command mvn.cmd).Source
    Push-Location $workspace
    try {
        & $maven --batch-mode --no-transfer-progress "-Dmaven.repo.local=$repository" -DskipTests test |
            Tee-Object -FilePath (Join-Path $root 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Default acceptance compilation failed' }
        $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
        & $compiler /nologo /target:exe /platform:x64 "/out:$workspace/target/resource-probe.exe" "$workspace/.github/scripts/resource-probe.cs"
        if ($LASTEXITCODE -ne 0) { throw 'Resource probe compilation failed' }
    } finally { Pop-Location }

    # The password is used only for local logon and never written to disk or output.
    $password = ConvertTo-SecureString ([guid]::NewGuid().ToString('N') + '!aA9') -AsPlainText -Force
    New-LocalUser -Name $user -Password $password -AccountNeverExpires -PasswordNeverExpires | Out-Null
    $created = $true
    $users = Get-LocalGroup -SID 'S-1-5-32-545'
    Add-LocalGroupMember -Group $users.Name -Member $user
    $sid = (Get-LocalUser -Name $user).SID.Value
    & icacls.exe $root /grant "*${sid}:(OI)(CI)F" /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not grant access to isolated CI files' }

    $tests = 'WindowsRestrictedTokenSandboxRunnerTest,WindowsDefaultAcceptanceTest,WindowsLifecycleIntegrationTest,WindowsOutputLimitIntegrationTest,WindowsReadinessIntegrationTest,WindowsResourceLimitsIntegrationTest,SandboxStrictEnvironmentIntegrationTest'
    $entry = Join-Path $root 'run.cmd'
    # Deliberately do not inherit the runner's Maven settings or production backend selector.
    $emptySettings = Join-Path $root 'settings.xml'
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" />' | Set-Content $emptySettings -Encoding ascii
    @"
@echo off
set "JAVA_HOME=$env:JAVA_HOME"
set "PATH=$env:JAVA_HOME\bin;$(Split-Path $maven);$env:SystemRoot\System32;$env:SystemRoot;$env:SystemRoot\System32\WindowsPowerShell\v1.0"
set "TEMP=$temp"
set "TMP=$temp"
set "MAVEN_OPTS="
set "MAVEN_ARGS="
set "SANDBOX_WINDOWS_PRODUCTION_TEST="
set "SANDBOX_WINDOWS_DEFAULT_ACCEPTANCE=1"
set "SANDBOX_RUN_RESOURCE_INTEGRATION=1"
set "SANDBOX_RUN_ENVIRONMENT_INTEGRATION=1"
set "SANDBOX_SYNTHETIC_PARENT_SECRET=synthetic-parent-secret-must-not-inherit"
call "$maven" --batch-mode --no-transfer-progress --settings "$emptySettings" "-Dmaven.repo.local=$repository" "-Dtest=$tests" test
exit /b %ERRORLEVEL%
"@ | Set-Content $entry -Encoding ascii
    $credential = [pscredential]::new("$env:COMPUTERNAME\$user", $password)
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList "/d /s /c `"`"$entry`"`"" `
        -Credential $credential -LoadUserProfile -WorkingDirectory $workspace `
        -RedirectStandardOutput (Join-Path $root 'stdout.log') `
        -RedirectStandardError (Join-Path $root 'stderr.log') -PassThru
    $null = $process.Handle
    if (-not $process.WaitForExit(600000)) {
        throw 'Standard-user acceptance exceeded its ten-minute bound'
    }
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    if ($null -eq $exitCode) { throw 'Standard-user process exit status is unavailable' }
} catch {
    $detail = "Standard-user acceptance: $($_.Exception.Message) (script line $($_.InvocationInfo.ScriptLineNumber))"
    $detail = $detail.Replace('%', '%25').Replace("`r", '%0D').Replace("`n", '%0A')
    Write-Output "::error::$detail"
    throw
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F | Out-Null
    }
    # Copy reports even on failure; no account name/password is included in the identity evidence.
    foreach ($name in @('surefire-reports', 'backend-evidence')) {
        $path = Join-Path $root "source/target/$name"
        if (Test-Path $path) { Copy-Item $path $evidence -Recurse -Force }
    }
    foreach ($name in @('stdout.log', 'stderr.log')) {
        $path = Join-Path $root $name
        if (Test-Path $path) { Copy-Item $path $evidence -Force }
    }
    $reports = Join-Path $evidence 'surefire-reports'
    if (Test-Path $reports) {
        Get-ChildItem $reports -Filter 'TEST-*.xml' | ForEach-Object {
            [xml]$report = Get-Content -LiteralPath $_.FullName -Raw
            foreach ($case in $report.SelectNodes('//testcase[failure or error]')) {
                $failure = $case.SelectSingleNode('failure|error')
                $message = "$($case.classname).$($case.name): $($failure.GetAttribute('message'))"
                if ($message.Length -gt 2000) { $message = $message.Substring(0, 2000) }
                $message = $message.Replace('%', '%25').Replace("`r", '%0D').Replace("`n", '%0A')
                Write-Output "::error::$message"
            }
        }
    }
    $buildLog = Join-Path $root 'build.log'
    if (Test-Path $buildLog) {
        Get-Content $buildLog | Where-Object { $_ -match '^\[ERROR\]' } | Select-Object -First 8 | ForEach-Object {
            $message = $_.Replace('%', '%25').Replace("`r", '%0D').Replace("`n", '%0A')
            Write-Output "::error::$message"
        }
    }
    try {
        if ($created) {
            $sid = (Get-LocalUser -Name $user).SID.Value
            try {
                Get-CimInstance Win32_UserProfile | Where-Object SID -eq $sid | Remove-CimInstance
            } finally {
                Remove-LocalUser -Name $user
            }
        }
    } finally {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
if ($exitCode -ne 0) { throw "Standard-user Maven acceptance failed (exit $exitCode); consult test evidence" }
