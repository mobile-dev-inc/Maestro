$env:MAESTRO_CLI_NO_ANALYTICS = 1
$pass = 0; $fail = 0

function Check([string]$desc, [string]$cmd, [string]$assertion, [string]$expected) {
    $actual = (Invoke-Expression $cmd 2>&1 | Out-String).Trim()
    $ok = switch ($assertion) {
        'equals'   { $actual -eq $expected }
        'includes' { $actual -like "*$expected*" }
        'excludes' { $actual -notlike "*$expected*" }
    }
    if ($ok) {
        Write-Host "PASS: $desc"
        $script:pass++
    } else {
        Write-Host "FAIL: $desc"
        Write-Host "      expected ($assertion): $expected"
        Write-Host "      got:                   $actual"
        $script:fail++
    }
}

$version = (Select-String -Path 'maestro-cli/gradle.properties' -Pattern '^CLI_VERSION=').Line `
    -replace '^CLI_VERSION=', ''

Check "maestro -v equals gradle.properties version" "maestro -v" "equals" $version

Write-Host ""
Write-Host "$pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
