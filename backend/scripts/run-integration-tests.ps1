<#
.SYNOPSIS
    Runs the Module 1 live integration suites against disposable PostgreSQL and
    Redis instances (see backend/docker-compose.test.yml).

.DESCRIPTION
    The suites are build-tagged `integration`. If either dependency is missing or
    unreachable, the affected tests skip with a clear message instead of failing
    the build. Each PostgreSQL test creates (and drops) its own database; the
    Redis tests use TEST_REDIS_DB (default 15) and flush only that logical DB.

.EXAMPLE
    # Start dependencies, then run:
    docker compose -f backend/docker-compose.test.yml up -d
    backend/scripts/run-integration-tests.ps1

.EXAMPLE
    # Point at existing infrastructure:
    backend/scripts/run-integration-tests.ps1 `
        -DatabaseURL "postgres://nexa:nexa_test@127.0.0.1:55432/nexa_test?sslmode=disable" `
        -RedisAddr "127.0.0.1:56379"
#>
param(
    [string]$DatabaseURL = "postgres://nexa:nexa_test@127.0.0.1:55432/nexa_test?sslmode=disable",
    [string]$RedisAddr = "127.0.0.1:56379",
    [string]$RedisDB = "15",
    [string]$RedisPassword = "",
    [switch]$SkipVet
)

$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
Push-Location $backendRoot
try {
    $env:TEST_DATABASE_URL = $DatabaseURL
    $env:TEST_REDIS_ADDR = $RedisAddr
    $env:TEST_REDIS_DB = $RedisDB
    if ($RedisPassword) {
        $env:TEST_REDIS_PASSWORD = $RedisPassword
    } else {
        Remove-Item Env:TEST_REDIS_PASSWORD -ErrorAction SilentlyContinue
    }

    Write-Host "Integration targets:"
    Write-Host "  TEST_DATABASE_URL = $env:TEST_DATABASE_URL"
    Write-Host "  TEST_REDIS_ADDR   = $env:TEST_REDIS_ADDR (db $env:TEST_REDIS_DB)"
    Write-Host ""

    if (-not $SkipVet) {
        Write-Host "==> go vet -tags=integration ./..."
        go vet -tags=integration ./...
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host "==> go test -tags=integration -count=1 ./..."
    go test -tags=integration -count=1 ./...
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
