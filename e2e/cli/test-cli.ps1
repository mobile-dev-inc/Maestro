$env:MAESTRO_CLI_NO_ANALYTICS = 1
$pass = 0; $fail = 0

function Check([string]$desc, [string]$cmd, [string]$assertion, [string]$expected) {
    $actual = (Invoke-Expression "$cmd 2>&1" | Out-String).Trim()
    $ok = switch ($assertion) {
        'equals'   { $actual -eq $expected }
        'includes' { $actual -like "*$expected*" }
        'excludes' { $actual -notlike "*$expected*" }
        default    { Write-Error "unknown assertion '$assertion' (use: equals, includes, excludes)"; exit 1 }
    }
    if ($ok) {
        Write-Host "PASS: $desc"
        $script:pass++
    } else {
        Write-Host ("FAIL: {0}" -f $desc)
        Write-Host ("      {0,-11} {1}" -f "cmd:",       $cmd)
        Write-Host ("      {0,-11} {1}" -f "assertion:", $assertion)
        Write-Host ("      {0,-11} {1}" -f "expected:",  $expected)
        Write-Host ("      {0,-11} {1}" -f "got:",       $actual)
        $script:fail++
    }
}

$version = (Select-String -Path 'maestro-cli/gradle.properties' -Pattern '^CLI_VERSION=').Line `
    -replace '^CLI_VERSION=', ''

# TESTS START HERE:

Check "maestro -v equals gradle.properties version" `
    "maestro -v" "equals" $version
Check "maestro gives usage instructions when called without parameters" `
    "maestro" "includes" "Usage: maestro"
Check "maestro gives usage instructions when called with --help" `
    "maestro --help" "includes" "Usage: maestro"
Check "maestro test subcommand gives usage instructions when called with --help" `
    "maestro test --help" "includes" "Usage: maestro test"
Check "maestro bugreport gives instruction" `
    "maestro bugreport" "includes" "https://github.com/mobile-dev-inc/Maestro/issues"

Write-Host ""
Write-Host "$pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
exit 0
