# Scripted acceptance check of the installed Offline Maps (Windows PowerShell 5.1).
# -Fixture is a map store with Illinois and its optional layers. Only the active
# Illinois files are copied. Each run has its own map store and settings.
param(
    [ValidateSet('online', 'offline')][string]$Mode = 'online',
    [string]$Fixture,
    [string]$Out,
    [switch]$RendererOnly
)

$ErrorActionPreference = 'Stop'
if (Get-Process -Name OfflineMaps -ErrorAction SilentlyContinue) {
    throw 'Close Offline Maps before starting this isolated check.'
}
if (-not $Fixture -and -not $RendererOnly) {
    throw 'Pass -Fixture with an Illinois map store, or use -RendererOnly for the bundled overview.'
}
if (-not $RendererOnly) {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms
    Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); }
'@
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $env:LOCALAPPDATA 'Programs\Offline Maps\OfflineMaps.exe'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Install Offline Maps before running this check.' }
if (-not $Out) { $Out = Join-Path $env:TEMP 'offline-maps-checks' }
$Out = [IO.Path]::GetFullPath((Join-Path $Out ([guid]::NewGuid().ToString('N'))))
$profile = Join-Path $Out 'settings'
$store = Join-Path $Out 'Maps'
New-Item -ItemType Directory -Path $profile,$store -ErrorAction Stop | Out-Null
$log = Join-Path $Out 'report.txt'
Set-Content $log "Offline Maps check, mode $Mode, started $(Get-Date -Format o)" -Encoding UTF8
function Note($s) { Add-Content $log $s -Encoding UTF8 }

function Online {
    $up = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -notmatch 'Hyper-V|Loopback' }
    $inet = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.IPv4Connectivity -eq 'Internet' -or $_.IPv6Connectivity -eq 'Internet' }
    $tcp = $false
    try { $c = New-Object Net.Sockets.TcpClient; $tcp = $c.ConnectAsync('1.1.1.1', 443).Wait(3000); $c.Close() } catch { $tcp = $false }
    return [pscustomobject]@{ Adapters = @($up | ForEach-Object Name); Internet = [bool]$inet; Tcp = $tcp; Any = ([bool]$up -or [bool]$inet -or $tcp) }
}

if ($Mode -eq 'offline') {
    $deadline = (Get-Date).AddMinutes(30)
    do { $n = Online; if (-not $n.Any) { break }; Start-Sleep 5 } while ((Get-Date) -lt $deadline)
    if ($n.Any) { Note "ABORT: network still available after 30 minutes"; exit 3 }
    Start-Sleep 5
}
$n = Online
Note "Network before launch: adapters up [$($n.Adapters -join ', ')], internet profile $($n.Internet), TCP to 1.1.1.1:443 $($n.Tcp)"

if ($Fixture) {
    $source = Join-Path (Resolve-Path -LiteralPath $Fixture).Path 'us-il'
    foreach ($layer in '', 'extras', 'overlays', 'terrain') {
        $from = if ($layer) { Join-Path $source $layer } else { $source }
        $pointer = Join-Path $from 'active.json'
        if ($layer -and -not (Test-Path -LiteralPath $pointer -PathType Leaf)) { continue }
        $active = Get-Content -LiteralPath $pointer -Raw | ConvertFrom-Json
        if ($active.schema -ne 1 -or $active.version -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw "Invalid active pointer at $pointer" }
        $to = Join-Path $store 'us-il'
        if ($layer) { $to = Join-Path $to $layer }
        $versionDir = Join-Path $from ('versions\' + $active.version)
        $versions = Join-Path $to 'versions'
        New-Item -ItemType Directory -Path $versions | Out-Null
        Copy-Item -LiteralPath $pointer -Destination (Join-Path $to 'active.json')
        Copy-Item -LiteralPath $versionDir -Destination $versions -Recurse
    }
}
$places = Join-Path $store 'saved-places.json'
@'
{"schema":1,"next_id":2,"places":[{"id":1,"name":"Millennium Park","note":"Acceptance fixture","latitude":41.8826,"longitude":-87.6226,"created_at":"2026-11-18T12:00:00Z","updated_at":"2026-11-18T12:00:00Z"}]}
'@ | Set-Content -LiteralPath $places -Encoding ascii
$placesBefore = (Get-FileHash -LiteralPath $places).Hash

if (Get-Process -Name OfflineMaps -ErrorAction SilentlyContinue) {
    throw 'Offline Maps started while the fixture was being prepared.'
}
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$previousArguments = $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
$previousPort = $env:OFFLINE_MAPS_CDP_PORT
$proc = $null
try {
$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--remote-debugging-address=127.0.0.1 --remote-debugging-port=$port"
$env:OFFLINE_MAPS_CDP_PORT = $port
$t0 = Get-Date
try {
    $arguments = '--settings "{0}" --store "{1}"' -f $profile, $store
    $proc = Start-Process -FilePath $exe -ArgumentList $arguments -PassThru
} finally {
    $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previousArguments
}

# Wait for the local document and the renderer worker. The initial target can
# advertise the navigation URL while its JavaScript context is still blank.
$ready = $false
for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
    $proc.Refresh()
    if ($proc.HasExited) { throw "Offline Maps exited before opening the test window (exit $($proc.ExitCode))." }
    try {
        $pages = Invoke-RestMethod "http://127.0.0.1:$port/json" -TimeoutSec 1
        $document = $pages | Where-Object { $_.type -eq 'page' -and $_.url -eq 'https://map.local/index.html' }
        $worker = $pages | Where-Object { $_.type -eq 'worker' -and $_.url -like 'https://map.local/maplibre/*' }
        $ready = [bool]($document -and $worker)
    } catch {}
    if (-not $ready) { Start-Sleep -Seconds 1 }
}
if (-not $ready) { throw 'The local map page did not open within a minute.' }
Note "Map page ready after $([int]((Get-Date) - $t0).TotalSeconds) s"

if ($RendererOnly) {
    $report = Join-Path $Out 'renderer.json'
    $view = $null
    for ($i = 0; $i -lt 20; $i++) {
        & node (Join-Path $here 'cdp.mjs') info $report
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the map page.' }
        $view = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
        if ($view.canvas -and $view.attribution -and $view.markers -eq 1) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($view.canvas[0] -le 0 -or $view.canvas[1] -le 0 -or -not $view.attribution -or
        $view.markers -ne 1 -or $view.remote.Count) {
        throw 'The local renderer failed its canvas, attribution, sample marker or network check.'
    }
    & node (Join-Path $here 'cdp.mjs') shot (Join-Path $Out 'map.png')
    if ($LASTEXITCODE -ne 0) { throw 'Could not capture the local map.' }
    if ((Get-FileHash -LiteralPath $places).Hash -ne $placesBefore) { throw 'The sample saved-place file changed.' }
    Note 'The local renderer drew attribution and a sample marker with no external page requests.'
    Write-Output $Out
    if ($Mode -eq 'offline') { Write-Output 'The offline check has finished. You can reconnect the test machine.' }
    return
}

$AE = [System.Windows.Automation.AutomationElement]
$TS = [System.Windows.Automation.TreeScope]
$TW = [System.Windows.Automation.TreeWalker]::RawViewWalker
# A Descendants search stops inside the WebView2, so walk the tree and skip it.
function ById($root, $id) {
    $c = $TW.GetFirstChild($root)
    while ($c) {
        if ($c.Current.AutomationId -eq $id) { return $c }
        if ($c.Current.ClassName -ne 'Microsoft.UI.Xaml.Controls.WebView2') { $f = ById $c $id; if ($f) { return $f } }
        $c = $TW.GetNextSibling($c)
    }
    return $null
}

$win = $null
for ($i = 0; $i -lt 120 -and -not $win; $i++) {
    Start-Sleep -Milliseconds 500; $proc.Refresh()
    if ($proc.MainWindowHandle -ne 0) { $win = $AE::FromHandle($proc.MainWindowHandle) }
}
if (-not $win) { Note "FAIL: no window after 60 s"; exit 4 }
Note "Window '$($win.Current.Name)' after $([int]((Get-Date) - $t0).TotalSeconds) s"

Start-Sleep 8

# Remote connections by the app and its WebView2 processes, sampled through the run.
$remote = @{}
function Tree { $all = Get-CimInstance Win32_Process; $ids = @($proc.Id); do { $added = $all | Where-Object { $ids -contains $_.ParentProcessId -and $ids -notcontains $_.ProcessId } | ForEach-Object ProcessId; $ids += $added } while ($added); $ids }
function Sample {
    $ids = Tree
    Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.OwningProcess -and $_.RemoteAddress -notmatch '^(127\.|::1|0\.0\.0\.0|::$)' } | ForEach-Object {
        $p = (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        $remote["$p -> $($_.RemoteAddress):$($_.RemotePort) $($_.State)"] = 1
    }
}
function Cdp($cmd, $file) {
    & node (Join-Path $here 'cdp.mjs') $cmd (Join-Path $Out $file) 2>&1 | ForEach-Object { Note "  cdp: $_" }
    if ($LASTEXITCODE -ne 0) { throw "WebView2 check failed: $cmd" }
}
function Text($id) { $e = ById $win $id; if ($e) { $e.Current.Name } else { '(not found)' } }
function Items {
    $list = ById $win 'Results'
    if (-not $list) { return @('(no list)') }
    $c = New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::ListItem)
    @($list.FindAll($TS::Children, $c) | ForEach-Object {
        $names = @($_.FindAll($TS::Descendants, [System.Windows.Automation.Condition]::TrueCondition) | ForEach-Object { $_.Current.Name } | Where-Object { $_ })
        if ($names.Count) { $names[0] } else { $_.Current.Name }
    })
}
function ErrorBar { $e = ById $win 'ErrorBar'; if ($e -and -not $e.Current.IsOffscreen -and $e.Current.BoundingRectangle.Height -gt 0) { "shown: " + (@($e.FindAll($TS::Descendants, [System.Windows.Automation.Condition]::TrueCondition) | ForEach-Object { $_.Current.Name } | Where-Object { $_ }) -join ' | ') } else { 'not shown' } }

Sample
Cdp info '01-start.json'; Cdp shot '01-start.png'
Note "Status bar: $(Text 'StatusText') | $(Text 'ViewText')"
Note "Error bar: $(ErrorBar)"
Note "List heading: $(Text 'ListHeading'); items: $((Items) -join ' || ')"

function Search($q, $tag, [switch]$Select) {
    $box = ById $win 'SearchBox'
    $edit = $box.FindFirst($TS::Descendants, (New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)))
    [W]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
    $edit.SetFocus(); Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait('^a{BACKSPACE}')
    [System.Windows.Forms.SendKeys]::SendWait(($q -replace '([+^%~(){}\[\]])', '{$1}'))
    $sw = [Diagnostics.Stopwatch]::StartNew(); Start-Sleep 2
    Note ""
    Note "Search '$q': heading '$(Text 'ListHeading')'; empty text '$(Text 'ListEmpty')'"
    $items = Items
    $k = 0; foreach ($it in ($items | Select-Object -First 8)) { $k++; Note "  $k. $it" }
    Note "  ($($items.Count) items listed)"
    if ($Select -and $items.Count) {
        $list = ById $win 'Results'
        $first = $list.FindFirst($TS::Children, (New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::ListItem)))
        $first.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
        Start-Sleep 6; Sample
        Note "  selected first result; view now: $(Text 'ViewText')"
        Cdp shot "$tag.png"
    }
    Sample
}

Search 'springfield' '02-springfield' -Select
Search '1060 w addison st chicago' '03-address' -Select
Search 'fuel' '04-fuel' -Select
Search 'campground' '05-campground' -Select
Search 'drinking water' '06-water'
Search 'charging' '07-charging'

# Back to the saved place.
$box = ById $win 'SearchBox'
$edit = $box.FindFirst($TS::Descendants, (New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)))
[W]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
$edit.SetFocus(); [System.Windows.Forms.SendKeys]::SendWait('^a{BACKSPACE}'); Start-Sleep 2
Note ""
Note "Cleared search: heading '$(Text 'ListHeading')'; items: $((Items) -join ' || ')"
$list = ById $win 'Results'
$first = $list.FindFirst($TS::Children, (New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::ListItem)))
if ($first) { $first.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select(); Start-Sleep 6; Note "Selected saved place; view now: $(Text 'ViewText')"; Cdp shot '08-saved-place.png' }

Sample
Cdp info '09-end.json'
Note "Error bar at end: $(ErrorBar)"
$n = Online
Note "Network at end: adapters up [$($n.Adapters -join ', ')], internet profile $($n.Internet), TCP $($n.Tcp)"
Note ""
Note "Remote TCP connections seen from the app's process tree:"
if ($remote.Count) { $remote.Keys | Sort-Object | ForEach-Object { Note "  $_" } } else { Note "  none" }

if ((Get-FileHash -LiteralPath $places).Hash -ne $placesBefore) { throw 'The sample saved-place file changed.' }
Note "Sample saved places unchanged."
$newLog = @(Get-Content (Join-Path $profile 'offline-maps.log') -ErrorAction SilentlyContinue)
Note "App log lines: $($newLog.Count)"; $newLog | Select-Object -First 20 | ForEach-Object { Note "  $_" }
Note "Finished $(Get-Date -Format o)"
Write-Output $Out
} finally {
    $env:OFFLINE_MAPS_CDP_PORT = $previousPort
    if ($proc -and -not $proc.HasExited) {
        $proc.CloseMainWindow() | Out-Null
        if (-not $proc.WaitForExit(15000)) { Write-Warning 'The isolated Offline Maps window is still open.' }
    }
}

if ($Mode -eq 'offline') {
    Write-Output 'The offline check has finished. You can reconnect the test machine.'
}
