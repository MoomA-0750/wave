# Windows 版の確かめ用: UI Automation で wave の窓の要素を名前で探して押す・文字を入れる。
# ログオン中の画面のセッションで動かす（win.sh uia が予定タスク経由で起こす）。結果は uia-out.txt に書く。
#   手順は uia-steps.txt（同じ場所）に1行に1つ。 invoke:名前 / text:名前=文字 / list / sleep:ミリ秒 / key:キー名
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$out = Join-Path $env:LOCALAPPDATA 'wave\uia-out.txt'
$log = [System.Collections.Generic.List[string]]::new()
$proc = Get-Process Wave.App -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $proc) { 'no wave' | Set-Content $out; exit }
$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id)
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)
if (-not $win) {
  $names = $root.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) | ForEach-Object { "$($_.Current.ProcessId) $($_.Current.Name)" }
  (@("no window (pid $($proc.Id)) session=$((Get-Process -Id $PID).SessionId)") + $names) | Set-Content $out -Encoding utf8; exit
}
$log.Add("window: $($win.Current.Name)")
function Find([string]$name) {
  $c = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $name)
  return $win.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $c)
}
foreach ($step in Get-Content (Join-Path $env:LOCALAPPDATA 'wave\uia-steps.txt') -Encoding utf8) {
  if ($step -like 'sleep:*') { Start-Sleep -Milliseconds ([int]$step.Substring(6)); continue }
  if ($step -eq 'list') {
    $all = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($e in $all) { $n = $e.Current.Name; if ($n) { $log.Add("[$($e.Current.ControlType.ProgrammaticName)] $n") } }
    continue
  }
  if ($step -like 'invoke:*') {
    $e = Find $step.Substring(7)
    if (-not $e) { $log.Add("not found: $step"); continue }
    $p = $null
    if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$p)) { $p.Invoke(); $log.Add("invoked: $step") }
    elseif ($e.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$p)) { $p.Toggle(); $log.Add("toggled: $step") }
    elseif ($e.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$p)) { $p.Select(); $log.Add("selected: $step") }
    else { $log.Add("no pattern: $step") }
    continue
  }
  if ($step -like 'text:*') {
    $kv = $step.Substring(5).Split('=', 2)
    $e = Find $kv[0]
    $p = $null
    if ($e -and $e.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$p)) { $p.SetValue($kv[1]); $log.Add("text: $($kv[0])") } else { $log.Add("no text box: $($kv[0])") }
  }
}
$log | Set-Content $out -Encoding utf8
