param(
    [string]$Token = $env:SHOPSPHERE_MONITORING_TOKEN
)

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "Set SHOPSPHERE_MONITORING_TOKEN before starting the controller."
}

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:8767/")
$listener.Start()
Write-Host "ShopSphere monitoring controller listening on http://127.0.0.1:8767"

function Send-Json($context, $status, $body) {
    $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    $context.Response.StatusCode = $status
    $context.Response.ContentType = "application/json"
    $context.Response.AddHeader("Access-Control-Allow-Origin", "http://localhost:8090")
    $context.Response.AddHeader("Access-Control-Allow-Headers", "X-Monitoring-Token, Content-Type")
    if ($status -eq 204) {
        $context.Response.ContentLength64 = 0
    } else {
        $context.Response.ContentLength64 = $bytes.Length
        $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    }
    $context.Response.Close()
}

function Invoke-Compose($file, $arguments) {
    $output = & docker compose -f $file @arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw $output }
}

function Get-State {
    $names = @("shopsphere-prometheus", "shopsphere-grafana", "shopsphere-otel-collector")
    $running = @(& docker ps --format "{{.Names}}" 2>$null)
    return [ordered]@{
        prometheus = $running -contains $names[0]
        grafana = $running -contains $names[1]
        otel = $running -contains $names[2]
    }
}

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        if ($context.Request.HttpMethod -eq "OPTIONS") { Send-Json $context 204 @{}; continue }
        if ($context.Request.HttpMethod -eq "GET" -and $context.Request.Url.AbsolutePath -eq "/") {
            Send-Json $context 200 @{ service = "ShopSphere monitoring controller"; message = "Use the ShopSphere GUI with the configured controller token." }; continue
        }
        if ($context.Request.Headers["X-Monitoring-Token"] -ne $Token) { Send-Json $context 401 @{ error = "Invalid controller token" }; continue }

        try {
            $parts = $context.Request.Url.AbsolutePath.Trim("/").Split("/")
            if ($parts[0] -eq "status" -and $context.Request.HttpMethod -eq "GET") {
                Send-Json $context 200 (Get-State); continue
            }
            if ($parts.Count -ne 2 -or $context.Request.HttpMethod -ne "POST" -or $parts[1] -notin @("start", "stop")) {
                Send-Json $context 404 @{ error = "Use GET /status or POST /prometheus|grafana|otel/start|stop" }; continue
            }

            $service = switch ($parts[0]) { "prometheus" { "prometheus" } "grafana" { "grafana" } "otel" { "otel-collector" } default { $null } }
            if (-not $service) { Send-Json $context 404 @{ error = "Unknown monitoring service" }; continue }
            $compose = if ($service -eq "otel-collector") { Join-Path $root "docker-compose.app.yml" } else { Join-Path $root "docker-compose.monitoring.yml" }
            if ($parts[1] -eq "start") { Invoke-Compose $compose @("up", "-d", $service) } else { Invoke-Compose $compose @("stop", $service) }
            Start-Sleep -Milliseconds 500
            Send-Json $context 200 (Get-State)
        } catch { Send-Json $context 500 @{ error = $_.Exception.Message } }
    }
} finally { $listener.Stop(); $listener.Close() }
