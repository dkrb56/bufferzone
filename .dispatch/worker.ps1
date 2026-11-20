<#
Dispatch worker script. Triage starts and resumes a worker only through this file.
  plan <ticket-id>              start the plan run (plan-flags.txt); state: planning
  approve <ticket-id>           start the work run as a new session (work-flags.txt) that reads the brief and the plan; state: working
  reply <ticket-id> <message>   send the user's words to the worker; the state picks the options
  status                        the tasks with their state and the state of their worker
  stop <ticket-id>              stop the worker of a task; state: stopped
  resume <ticket-id>            start the stopped run of a task again in its session; state: planning or working
  measure <ticket-id> <n>       run command n of measure-commands.txt for the plan run (Measure before the plan)
  merge <ticket-id>             merge the task branch into its base after the gate command (Task branches; Dispatch only);
                                with Push after the merge, then push the base to its upstream branch, never with force
  check <ticket-id>             run the picked checks of <ticket-id>.checks in the checkout (Pick the checks after the work run);
                                the logs go to checks\<ticket-id>\<n>.log, the results to <ticket-id>.checked
  check full                    run every check of checks\full\checks (the scheduled full run) in the checkout on
                                the base branch; refused on a task/ branch; the logs go to checks\full\<n>.log,
                                the results to checks\full\checked
Exit codes: 0 done, 2 refused (the reason is on stderr), 124 a measuring command stopped at its
time limit, other: the exit code of claude or of the measuring command.
The gate command of merge gets DISPATCH_TICKET (the ticket id) and DISPATCH_CHECKS (the path of
<ticket-id>.checks: the checks that the user chose, one line each, the name, a tab and the command).
With Pick the checks after the work run, Dispatch writes <ticket-id>.picked (the pick of the agent)
and, after the user's choice, <ticket-id>.checks; the merge runs these checks before the gate, unless
Checks at the gate gives them to the gate command.
<ticket-id>.models, when it exists, gives the model and the effort of a run of the task: a line
`plan <model> <effort>` or `work <model> <effort>` replaces those of the options file.
The script finds the project from its own folder, and the options files name paths relative to the
project, so a clone on another path or computer needs no change.
#>
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet('plan', 'approve', 'reply', 'status', 'stop', 'resume', 'measure', 'merge', 'check', IgnoreCase = $false)]
  [string]$Verb,
  [Parameter(Position = 1)]
  [string]$Id,
  [Parameter(Position = 2, ValueFromRemainingArguments = $true)]
  [string[]]$Message
)

$ErrorActionPreference = 'Stop'
$Reports = $PSScriptRoot
# The reports folder is the folder .dispatch of the project.
$Project = Split-Path -Parent $Reports

function Refuse([string]$Why) {
  [Console]::Error.WriteLine("refused: $Why")
  exit 2
}

function Need-Id {
  if (-not $Id) { Refuse "the verb $Verb needs a ticket id" }
}

# The options of a run, one argument per line. Setup pipeline writes the files. The settings file
# after --settings is relative to the project; claude gets it as an absolute path. Claude Code takes
# a relative rule path from the current folder of the shell, and a cd moves it (ticket
# a-plan-run-that-ends-without-a-plan), so each Edit rule after --allowedTools and --disallowedTools
# with a relative path gets the project folder in front: D:\p and Edit(.dispatch/plans/**) give
# Edit(//d/p/.dispatch/plans/**).
function Read-Flags([string]$Name) {
  $file = Join-Path $Reports $Name
  if (-not (Test-Path -LiteralPath $file)) { Refuse "$file is missing; run Setup pipeline" }
  $lines = @(Get-Content -LiteralPath $file | Where-Object { $_.Trim() -ne '' })
  $root = $Project -replace '\\', '/'
  $rulePrefix = if ($root -match '^([A-Za-z]):/(.*)$') { '//' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2].TrimEnd('/') } else { '' }
  for ($at = 1; $at -lt $lines.Count; $at++) {
    $value = $lines[$at].Trim()
    $option = $lines[$at - 1].Trim()
    if ($option -eq '--settings' -and -not [IO.Path]::IsPathRooted($value)) {
      $lines[$at] = [IO.Path]::GetFullPath((Join-Path $Project $value))
    }
    if ($rulePrefix -and ($option -eq '--allowedTools' -or $option -eq '--disallowedTools')) {
      $rules = foreach ($rule in $value -split ',') {
        if ($rule -match '^Edit\((?![/~])(.+)\)$') { "Edit($rulePrefix/$($Matches[1] -replace '^\./', ''))" } else { $rule }
      }
      $lines[$at] = $rules -join ','
    }
  }
  $lines
}

# The options of a run with the model and the effort of the task: the line of the run in
# <ticket-id>.models (`plan <model> <effort>` or `work <model> <effort>`, `-` for no value) replaces
# the value after --model and after --effort, or goes in front when the options file lacks it.
function Run-Flags([string]$Run) {
  $lines = @(Read-Flags "$Run-flags.txt")
  $file = Join-Path $Reports "$Id.models"
  if (-not $Id -or -not (Test-Path -LiteralPath $file)) { return $lines }
  $line = @(Get-Content -LiteralPath $file | Where-Object { ($_.Trim() -split '\s+')[0] -eq $Run }) | Select-Object -First 1
  if (-not $line) { return $lines }
  $parts = @($line.Trim() -split '\s+')
  $front = @()
  foreach ($pair in @(@('--model', $parts[1]), @('--effort', $parts[2]))) {
    $value = "$($pair[1])"
    if ($value -notmatch '^[A-Za-z0-9._\[\]-]+$' -or $value -eq '-') { continue }
    $at = [Array]::IndexOf([string[]]($lines | ForEach-Object { $_.Trim() }), $pair[0])
    if ($at -ge 0 -and $at + 1 -lt $lines.Count) { $lines[$at + 1] = $value } else { $front += @($pair[0], $value) }
  }
  @($front) + @($lines)
}

function State-Path { Join-Path $Reports "$Id.state" }
function Get-State {
  if (Test-Path -LiteralPath (State-Path)) { (Get-Content -LiteralPath (State-Path) -Raw).Trim() } else { '' }
}
function Set-State([string]$State) {
  [IO.File]::WriteAllText((State-Path), "$State`n")
}

# The background sessions, as `claude agents --json --all` gives them.
function Get-Workers {
  $text = (& claude agents --json --all) -join "`n"
  if ($LASTEXITCODE -ne 0) { Refuse "claude agents failed with exit code $LASTEXITCODE" }
  @($text | ConvertFrom-Json)
}

# Claude Code marks a session that ended its turn with a question as blocked, like a session at a
# permission prompt. Only the prompt waits (status waiting); the question is in the report, and
# the session is done: a resume of it is no copy. Claude Code can also keep a session at working
# after its turn ended (status idle). A run "<ticket> [plan]" or "<ticket> [work]" that is idle and
# wrote its plan or report at or after its start is done. Busy and shell still run.
function Worker-State($Worker) {
  if ($null -eq $Worker) { return '' }
  $state = "$($Worker.state)"
  if (-not $state) { return "$($Worker.status)" }
  if ($state -eq 'blocked' -and "$($Worker.status)" -ne 'waiting') { return 'done' }
  if ($state -eq 'working' -and "$($Worker.status)" -eq 'idle' -and "$($Worker.name)" -match '^(.+) \[(plan|work)\]$') {
    $file = if ($Matches[2] -eq 'plan') { Join-Path (Join-Path $Reports 'plans') "$($Matches[1]).md" } else { Join-Path $Reports "$($Matches[1]).md" }
    if (Test-Path -LiteralPath $file -PathType Leaf) {
      $written = [DateTimeOffset]::new((Get-Item -LiteralPath $file).LastWriteTimeUtc).ToUnixTimeMilliseconds()
      if ($Worker.startedAt -and $written -ge [int64]$Worker.startedAt) { return 'done' }
    }
  }
  $state
}

# The session names of a ticket: the plan run is "<ticket> [plan]", the work run "<ticket> [work]",
# so the agent view of Claude Code tells them apart. A worker of an older script has the bare id.
function Run-Name([string]$Run) { "$Id [$Run]" }

# The newest worker of a ticket, of either run. With a run: the newest worker named "<ticket> [<run>]"
# when one exists, else the newest of any name (a worker of an older script has the bare id). The
# plan run and the work run are two sessions (ticket work-fresh-session), and a follow-up of the work
# run must resume the work session, never the plan session.
function Find-Worker($Workers, [string]$Ticket, [string]$Run) {
  $names = @($Ticket, "$Ticket [plan]", "$Ticket [work]")
  $mine = @($Workers | Where-Object { $names -contains $_.name } | Sort-Object { $_.startedAt })
  if ($Run) {
    $named = @($mine | Where-Object { $_.name -eq "$Ticket [$Run]" })
    if ($named.Count -gt 0) { $mine = $named }
  }
  if ($mine.Count -eq 0) { return $null }
  $mine[$mine.Count - 1]
}

# Approve starts the work run as a new session and resumes nothing. It still refuses a stopped task,
# and a worker of the ticket that still runs or waits at a prompt: the plan run must end first.
# Without a worker of the ticket in the list (an ended or cleaned plan session) approve goes on.
function Approve-Check {
  if ((Get-State) -eq 'stopped') { Refuse "the task $Id was stopped; start a new task" }
  $worker = Find-Worker (Get-Workers) $Id
  if ($null -eq $worker) { return }
  $state = Worker-State $worker
  if ($state -eq 'working' -or $state -eq 'blocked') {
    Refuse "the worker $Id still runs ($state); the work run starts when it has ended. A permission prompt is answered in the agent view: claude attach $($worker.id)"
  }
}

# A resume of a session that still runs starts a copy of it. Only a finished session is resumed,
# and never one of a task that was stopped: a stopped task is ended, the next task starts clean.
# $Run picks the session of that run.
function Resume-Target([string]$Run) {
  if ((Get-State) -eq 'stopped') { Refuse "the task $Id was stopped; start a new task" }
  $worker = Find-Worker (Get-Workers) $Id $Run
  if ($null -eq $worker) { Refuse "no worker named $Id on this computer. A run that started on another computer cannot be resumed here: start the task again, or answer on the computer where it ran" }
  $state = Worker-State $worker
  if ($state -eq 'working' -or $state -eq 'blocked') {
    Refuse "the worker $Id still runs ($state); a resume now would start a copy. A permission prompt is answered in the agent view: claude attach $($worker.id)"
  }
  if (-not $worker.sessionId) { Refuse "the worker $Id has no session id" }
  $worker
}

# Runs claude in the project folder: a worker reads the settings and the allow rules of the folder
# it starts in, not of the folder the caller is in. A failure sets the state back to $StateOnFailure
# when one is given, so the task card of Dispatch comes back with the error, and ends the script
# with the exit code of claude; a claude that is not found counts as exit code 1. A failure also
# checks out $BranchOnFailure again when one is given: the branch before the task branch. Gives the
# lines that claude printed (claude --bg prints the job id) and writes them on to the caller.
function Run-Claude([string[]]$Arguments, [string]$StateOnFailure, [string]$BranchOnFailure) {
  Push-Location -LiteralPath $Project
  $global:LASTEXITCODE = 0
  $printed = @(& claude @Arguments)
  $ok = $?
  $code = $LASTEXITCODE
  Pop-Location
  foreach ($line in $printed) { [Console]::Out.WriteLine("$line") }
  if ($ok -and $code -eq 0) { return ,$printed }
  if ($code -eq 0) { $code = 1 }
  if ($StateOnFailure) { Set-State $StateOnFailure }
  if ($BranchOnFailure) { Invoke-Git @('checkout', '-q', $BranchOnFailure) | Out-Null }
  exit $code
}

# The session file of a ticket (ticket task-tokens): one line "<run> <session id>" per Claude session
# of the task, in order. Dispatch sums the tokens of these sessions for the task card. The worker of
# the job id that claude printed gives the session id; without it, the newest worker of the run name.
# The work run is its own session (ticket work-fresh-session), so Approve adds its line. A resume of
# the same session adds nothing; a resume that starts a copy adds the copy. A missing id is a
# warning, no refusal: the run has started.
function Session-Path { Join-Path $Reports "$Id.session" }
function Save-Session([string]$Run, [string[]]$Printed) {
  $words = @($Printed | ForEach-Object { "$_" -split '\s+' } | Where-Object { $_ })
  $workers = @()
  try {
    $text = (& claude agents --json --all) -join "`n"
    if ($LASTEXITCODE -eq 0) { $workers = @($text | ConvertFrom-Json | ForEach-Object { $_ }) }
  } catch { }
  $worker = @($workers | Where-Object { $_.id -and $words -contains "$($_.id)" }) | Select-Object -First 1
  if ($null -eq $worker) {
    $named = @($workers | Where-Object { $_.name -eq (Run-Name $Run) } | Sort-Object { $_.startedAt })
    if ($named.Count -gt 0) { $worker = $named[$named.Count - 1] }
  }
  if ($null -eq $worker -or -not $worker.sessionId) {
    [Console]::Error.WriteLine("warning: no session id for $(Run-Name $Run); the task card counts no tokens of this run")
    return
  }
  $session = "$($worker.sessionId)"
  $file = Session-Path
  $known = if (Test-Path -LiteralPath $file) { @(Get-Content -LiteralPath $file) } else { @() }
  if (@($known | Where-Object { (("$_".Trim()) -split '\s+')[-1] -eq $session }).Count -eq 0) {
    [IO.File]::AppendAllText($file, "$Run $session`n")
  }
}

# The setting "Measure before the plan" of the project in options.json; off without the file or the field.
function Measure-On {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return $false }
  try { return [bool]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).measureBeforePlan } catch { return $false }
}

# One boolean setting of the project in options.json (ticket simpler-process); off without the file
# or the field.
function Option-On([string]$Name) {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return $false }
  try { return [bool]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).$Name } catch { return $false }
}

# The command lines of a list file of .dispatch: the lines that are not empty and do not start with #.
# Setup pipeline writes the files; command n is the line n of this list.
function Command-Lines([string]$Name) {
  $file = Join-Path $Reports $Name
  if (-not (Test-Path -LiteralPath $file)) { return @() }
  @(Get-Content -LiteralPath $file | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' -and -not $_.StartsWith('#') })
}

# The time limit of the commands of a list file in seconds: a first line "# limit <seconds>", else $Default.
function Command-Limit([string]$Name, [int]$Default) {
  $file = Join-Path $Reports $Name
  if (Test-Path -LiteralPath $file) {
    $first = @(Get-Content -LiteralPath $file | Where-Object { $_.Trim() -ne '' } | Select-Object -First 1)
    if ($first.Count -gt 0 -and $first[0].Trim() -match '^#\s*limit\s+(\d+)\s*$') { return [int]$Matches[1] }
  }
  $Default
}

# The permitted measuring commands and their time limit (Measure before the plan).
function Measure-Commands { Command-Lines 'measure-commands.txt' }
function Measure-Limit { Command-Limit 'measure-commands.txt' 600 }

# The locks of the project: one measuring at a time (projects use fixed test ports), and one merge at
# a time. A lock file holds the process id, the ticket and the time. A lock whose process is gone is
# removed.
function Lock-Path { Join-Path $Reports 'measure\lock' }
function Merge-Lock { Join-Path $Reports 'merge\lock' }

# The ticket of a lock whose process still runs, else $null.
function Lock-Holder([string]$Lock) {
  if (-not (Test-Path -LiteralPath $Lock)) { return $null }
  $holder = "$(Get-Content -LiteralPath $Lock -Raw -ErrorAction SilentlyContinue)".Trim() -split "`t"
  $other = 0
  if ([int]::TryParse($holder[0], [ref]$other) -and $null -ne (Get-Process -Id $other -ErrorAction SilentlyContinue)) {
    if ($holder.Count -gt 1) { return $holder[1] }
    return 'another task'
  }
  Remove-Item -LiteralPath $Lock -Force -ErrorAction SilentlyContinue
  $null
}

# Takes a lock. A second caller waits up to $Limit seconds (a check every 5 s), then is refused.
function Enter-Lock([string]$Lock, [int]$Limit, [string]$What) {
  $dir = Split-Path -Parent $Lock
  if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
  $deadline = (Get-Date).AddSeconds($Limit)
  while ($true) {
    # A move to a name that exists fails, so only one caller gets the lock, and it is never half written.
    $mine = "$Lock.$PID"
    [IO.File]::WriteAllText($mine, "$PID`t$Id`t$((Get-Date).ToString('o'))`n")
    try { [IO.File]::Move($mine, $Lock); return } catch { Remove-Item -LiteralPath $mine -Force -ErrorAction SilentlyContinue }
    $owner = Lock-Holder $Lock
    if (-not $owner) { continue }
    $left = ($deadline - (Get-Date)).TotalSeconds
    if ($left -le 0) {
      $held = if ($Limit -gt 0) { "; it held the lock for longer than the limit of $Limit s" } else { '' }
      Refuse "another $What of this project runs (task $owner)$held"
    }
    Start-Sleep -Milliseconds ([int](1000 * [Math]::Min(5, $left)))
  }
}
function Exit-Lock([string]$Lock) {
  $holder = "$(Get-Content -LiteralPath $Lock -Raw -ErrorAction SilentlyContinue)".Trim() -split "`t"
  if ($holder[0] -eq "$PID") { Remove-Item -LiteralPath $Lock -Force -ErrorAction SilentlyContinue }
}

# Runs git in the project folder. Gives the exit code and the lines of stdout and stderr. Windows
# PowerShell turns the stderr lines of a native command into errors, so the preference is local.
function Invoke-Git([string[]]$A) {
  $ErrorActionPreference = 'Continue'
  $lines = @(& git -C $Project @A 2>&1 | ForEach-Object { "$_" })
  [pscustomobject]@{ Code = $LASTEXITCODE; Lines = $lines; Text = ($lines -join "`n").Trim() }
}

# The setting "Task branches" of the project in options.json; off without the file or the field.
function Branches-On {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return $false }
  try { return [bool]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).taskBranches } catch { return $false }
}

# The setting "Task files local only" of the project in options.json; off without the file or the field.
function Tasks-Local {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return $false }
  try { return [bool]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).ignoreTasks } catch { return $false }
}

# The branch that the main checkout is on; empty when HEAD is detached or the folder is no repository.
function Current-Branch {
  $result = Invoke-Git @('symbolic-ref', '--short', '-q', 'HEAD')
  if ($result.Code -ne 0) { return '' }
  $result.Text
}

# The branch file of a ticket: "branch: task/<ticket>", "base: <branch>", and "merge: done <sha> <time>"
# after the merge; with Push after the merge also "push: pushed <remote>/<branch> <time>", "push: failed
# <reason>" or "push: no-upstream". $null without the file.
function Branch-Path([string]$Ticket) { Join-Path $Reports "$Ticket.branch" }
function Read-BranchFile([string]$Ticket) {
  $file = Branch-Path $Ticket
  if (-not (Test-Path -LiteralPath $file)) { return $null }
  $info = @{ branch = ''; base = ''; merge = ''; push = '' }
  foreach ($line in Get-Content -LiteralPath $file) {
    if ($line -match '^\s*(branch|base|merge|push):\s*(.*?)\s*$') { $info[$Matches[1]] = $Matches[2] }
  }
  [pscustomobject]$info
}
function Write-BranchFile([string]$Branch, [string]$Base, [string]$Merge, [string]$Push = '') {
  $text = "branch: $Branch`nbase: $Base`n"
  if ($Merge) { $text += "merge: $Merge`n" }
  if ($Push) { $text += "push: $Push`n" }
  [IO.File]::WriteAllText((Branch-Path $Id), $text)
}

# The branch file of the ticket, written when it is missing or names a merged branch. The base is the
# current branch; while the checkout is on the branch of another task, the base of that task.
function Ensure-BranchFile {
  $info = Read-BranchFile $Id
  if ($info -and $info.branch -and $info.base -and -not $info.merge) { return $info }
  $current = Current-Branch
  if (-not $current) { Refuse 'Task branches needs a current branch as the base, and the checkout has none (detached HEAD, or no git repository)' }
  $base = $current
  if ($current.StartsWith('task/')) {
    $other = Read-BranchFile $current.Substring(5)
    if ($other -and $other.base) { $base = $other.base }
  }
  Write-BranchFile "task/$Id" $base ''
  Read-BranchFile $Id
}

# Puts the main checkout on the branch of the task, made from its base when it does not exist yet.
# Gives the branch that was checked out before, or '' when the checkout was on the task branch.
function Enter-TaskBranch {
  $info = Ensure-BranchFile
  $current = Current-Branch
  if ($current -eq $info.branch) { return '' }
  if ($current.StartsWith('task/')) { Refuse "the checkout is on $current, the branch of another task; Dispatch switches back to the base when that work run has ended" }
  # Dispatch holds the merge lock while it switches back to the base and copies the task files
  # (ticket merge-issue), for seconds: the switch waits up to 60 s for it, then reads the branch again.
  $holder = Lock-Holder (Merge-Lock)
  $deadline = (Get-Date).AddSeconds(60)
  while ($holder -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $holder = Lock-Holder (Merge-Lock)
  }
  if ($holder) { Refuse "a merge of this project runs (task $holder); try again when it has ended" }
  # The steps below change the index (ticket title-auto-merge-left-the-main-checkout): an open merge
  # would take them in.
  if ((Invoke-Git @('rev-parse', '-q', '--verify', 'MERGE_HEAD')).Code -eq 0) { Refuse 'the checkout has an unfinished merge; finish it or run git merge --abort first' }
  $current = Current-Branch
  if ($current -eq $info.branch) { return '' }
  if ($current.StartsWith('task/')) { Refuse "the checkout is on $current, the branch of another task; Dispatch switches back to the base when that work run has ended" }
  $exists = (Invoke-Git @('rev-parse', '--verify', '--quiet', "refs/heads/$($info.branch)")).Code -eq 0
  $kept = @()
  if ($exists) {
    $aligned = "$(Align-Tasks $info.branch)"
    if ($aligned) { Refuse "the checkout stays on ${current}: $aligned" }
    # The branch takes the newer commits of the base before the checkout (ticket merge-issue), so it
    # holds the card files as the base does; a clash leaves it as it was, and Sync-Note tells the
    # worker after the checkout. The card files that still differ go along by hand.
    Sync-Base -Before | Out-Null
    if (-not (Tasks-Local)) { $kept = Set-CardFilesAside $info.branch }
  }
  $result = if ($exists) { Invoke-Git @('checkout', '-q', $info.branch) } else { Invoke-Git @('checkout', '-q', '-b', $info.branch, $info.base) }
  Put-CardFiles $kept
  if ($result.Code -ne 0) { Refuse "git could not check out $($info.branch): $($result.Text)" }
  $current
}

# The card files that a checkout of $Target would delete or change (ticket merge-issue): the changed
# files of .dispatch and the files of .dispatch that differ between HEAD and $Target. Keeps their
# bytes and times and gives each file the copy of HEAD (a file that HEAD lacks goes from the disk), so
# git meets no changed card file; Put-CardFiles writes them again after the checkout. Dispatch commits
# no card file on a task branch, so they stay changes there until the switch back.
function Set-CardFilesAside([string]$Target) {
  $paths = @((Invoke-Git @('status', '--porcelain', '--untracked-files=all', '--', '.dispatch')).Lines | Where-Object { $_.Length -gt 3 } | ForEach-Object { $_.Substring(3) -split ' -> ' })
  $paths += @((Invoke-Git @('diff', '--name-only', '--no-renames', 'HEAD', $Target, '--', '.dispatch')).Lines)
  $kept = @()
  foreach ($path in @($paths | ForEach-Object { "$_".Trim().Trim('"') } | Where-Object { $_ } | Select-Object -Unique)) {
    $file = Join-Path $Project $path
    if (Test-Path -LiteralPath $file -PathType Leaf) {
      $kept += [pscustomobject]@{ Path = $path; Bytes = [IO.File]::ReadAllBytes($file); Time = [IO.File]::GetLastWriteTimeUtc($file) }
    } else {
      $kept += [pscustomobject]@{ Path = $path; Bytes = $null; Time = $null }
    }
  }
  foreach ($card in $kept) {
    if ((Invoke-Git @('cat-file', '-e', "HEAD:$($card.Path)")).Code -eq 0) {
      Invoke-Git @('checkout', '-q', 'HEAD', '--', $card.Path) | Out-Null
    } else {
      Invoke-Git @('rm', '-q', '--cached', '--ignore-unmatch', '--', $card.Path) | Out-Null
      Remove-Item -LiteralPath (Join-Path $Project $card.Path) -Force -ErrorAction SilentlyContinue
    }
  }
  ,$kept
}

# Writes the card files that Set-CardFilesAside kept onto the disk again, with their times.
function Put-CardFiles($Kept) {
  foreach ($card in @($Kept)) {
    $file = Join-Path $Project $card.Path
    if ($null -eq $card.Bytes) { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue; continue }
    $folder = Split-Path -Parent $file
    if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder -Force | Out-Null }
    [IO.File]::WriteAllBytes($file, $card.Bytes)
    [IO.File]::SetLastWriteTimeUtc($file, $card.Time)
  }
}

# Merges the task branch into the base in the main checkout: git merge --no-ff --no-commit, the gate
# command on the merged code, then the merge commit, or git merge --abort. A conflict only in the task
# files of .dispatch takes the version of the base, where Dispatch keeps them current, except the
# report and the log of this task, which take the branch version; a side with a conflict marker stops
# the merge (ticket board-in-progress). Gives the
# reason of a refusal, or '' after the merge; $script:Merged holds the commit then. The verb merge
# commits the changed task files first.
function Merge-Branch($Info, [string]$Gate, [int]$Limit) {
  $branch = $Info.branch
  $merge = Invoke-Git @('merge', '--no-ff', '--no-commit', $branch)
  $open = (Invoke-Git @('rev-parse', '-q', '--verify', 'MERGE_HEAD')).Code -eq 0
  if (-not $open) {
    if ($merge.Code -ne 0) { return "git could not merge ${branch}: $($merge.Text)" }
    # Nothing to merge: the branch has no commit that the base lacks.
    Invoke-Git @('branch', '-d', $branch) | Out-Null
    $script:Merged = 'nothing to merge'
    Write-BranchFile $branch $Info.base "done nothing-to-merge $((Get-Date).ToString('s'))"
    return ''
  }
  if ($merge.Code -ne 0) {
    $conflicts = @((Invoke-Git @('diff', '--name-only', '--diff-filter=U')).Lines | Where-Object { $_ })
    $code = @($conflicts | Where-Object { -not $_.StartsWith('.dispatch/') })
    if ($code.Count -gt 0 -or $conflicts.Count -eq 0) {
      Invoke-Git @('merge', '--abort') | Out-Null
      if ($code.Count -gt 0) { return "the merge of $branch into $($Info.base) does not apply; nothing was merged. Conflicts in:`n$($code -join "`n")" }
      return "git could not merge ${branch}: $($merge.Text)"
    }
    # The report and the log of this task take the branch side, where its work run wrote them; every
    # other task file takes the base side (ticket board-in-progress, user decision 5).
    $own = @(".dispatch/$Id.md", ".dispatch/logs/$Id.md")
    foreach ($file in $conflicts) {
      $side = if ($own -contains $file) { '--theirs' } else { '--ours' }
      if ((Invoke-Git @('checkout', $side, '--', $file)).Code -eq 0) { Invoke-Git @('add', '--', $file) | Out-Null }
      else { Invoke-Git @('rm', '-q', '--', $file) | Out-Null }
    }
    # No task file keeps a conflict marker: a side that has one stops the merge.
    foreach ($file in $conflicts) {
      $path = Join-Path $Project $file
      if (-not (Test-Path -LiteralPath $path)) { continue }
      $marked = @(Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object { $_ -match '^(<<<<<<<|>>>>>>>)( |$)' })
      if ($marked.Count -gt 0) {
        Invoke-Git @('merge', '--abort') | Out-Null
        return "the merge of $branch into $($Info.base) would keep conflict markers in $file; nothing was merged. Remove the lines that start with <<<<<<< or >>>>>>> from that file on both branches, then merge again"
      }
    }
  }
  # Pick the checks after the work run: the ticked checks run on the merged code before the gate, and
  # the first failed one aborts the merge. With Checks at the gate and a gate command, the gate runs them.
  if ((Option-On 'pickChecks') -and -not ($Gate -and (Option-On 'checksAtGate'))) {
    $folder = Join-Path $Reports 'merge'
    if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
    $failed = Run-Checks 'merge' { param($n) Join-Path $Reports "merge\$Id-$n.log" } (Command-Limit 'checks.txt' 14400) -StopAtFailure
    if ($null -ne $failed) {
      Invoke-Git @('merge', '--abort') | Out-Null
      $tail = if (Test-Path -LiteralPath $failed.Log) { (Get-Content -LiteralPath $failed.Log -Tail 40) -join "`n" } else { '' }
      return "the check $($failed.Name) failed with exit code $($failed.Code), so nothing was merged ($($failed.Command); the log is $($failed.Log)). Its last 40 lines:`n$tail"
    }
  }
  if ($Gate) {
    $log = Join-Path $Reports "merge\$Id.log"
    # The gate reads the ticket and the checks that the user chose for it (ticket task-checks); the
    # file can be absent: then the user chose no check, or the project has no list.
    $env:DISPATCH_TICKET = $Id
    $env:DISPATCH_CHECKS = Join-Path $Reports "$Id.checks"
    $code = Run-Measured $Gate $log $Limit
    Remove-Item Env:DISPATCH_TICKET, Env:DISPATCH_CHECKS -ErrorAction SilentlyContinue
    if ($code -ne 0) {
      Invoke-Git @('merge', '--abort') | Out-Null
      $tail = if (Test-Path -LiteralPath $log) { (Get-Content -LiteralPath $log -Tail 40) -join "`n" } else { '' }
      return "the gate command failed with exit code $code, so nothing was merged ($Gate; the log is $log). Its last 40 lines:`n$tail"
    }
  }
  $commit = Invoke-Git @('commit', '-q', '-m', "Merge $branch into $($Info.base) ($Id)")
  if ($commit.Code -ne 0) {
    Invoke-Git @('merge', '--abort') | Out-Null
    return "git could not commit the merge: $($commit.Text)"
  }
  $script:Merged = (Invoke-Git @('rev-parse', '--short', 'HEAD')).Text
  Invoke-Git @('branch', '-d', $branch) | Out-Null
  Write-BranchFile $branch $Info.base "done $script:Merged $((Get-Date).ToString('s'))"
  ''
}

# Pushes the base after its merge (Push after the merge, ticket simpler-process): only the base to its
# upstream branch (branch.<base>.remote and branch.<base>.merge), never with force, without a prompt
# for a password, with the time limit of 300 s. Gives the value of the push line of the branch file.
function Push-Base([string]$Base) {
  $remote = (Invoke-Git @('config', '--get', "branch.$Base.remote")).Text
  $target = (Invoke-Git @('config', '--get', "branch.$Base.merge")).Text
  if (-not $remote -or $remote -eq '.' -or -not $target.StartsWith('refs/heads/')) { return 'no-upstream' }
  $folder = Join-Path $Reports 'merge'
  if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
  $log = Join-Path $folder "$Id-push.log"
  # cmd.exe reads % in a batch file as a variable; a git name can hold one.
  $command = "git push `"$remote`" `"refs/heads/${Base}:$target`"".Replace('%', '%%')
  $before = @{ GIT_TERMINAL_PROMPT = $env:GIT_TERMINAL_PROMPT; GCM_INTERACTIVE = $env:GCM_INTERACTIVE }
  $env:GIT_TERMINAL_PROMPT = '0'
  $env:GCM_INTERACTIVE = 'never'
  try {
    $code = Run-Measured $command $log 300
  } finally {
    foreach ($name in @($before.Keys)) {
      if ($null -eq $before[$name]) { Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue }
      else { Set-Item -Path "Env:$name" -Value $before[$name] }
    }
  }
  $branch = $target.Substring('refs/heads/'.Length)
  if ($code -eq 0) { return "pushed $remote/$branch $((Get-Date).ToString('s'))" }
  $lines = if (Test-Path -LiteralPath $log) { @(Get-Content -LiteralPath $log | ForEach-Object { "$_".Trim() } | Where-Object { $_ }) } else { @() }
  $reason = @($lines | Where-Object { $_ -match '^(!|error:|fatal:|stopped after)' } | Select-Object -First 1)
  $why = if ($reason.Count -gt 0) { $reason[0] } elseif ($code -eq 124) { 'the push took longer than 300 s' } else { "git push ended with exit code $code" }
  "failed $why (the log is $log)"
}

# The changed files of the project outside .dispatch, as git status gives them; none outside git.
function Tree-Status {
  if (-not (Test-Path -LiteralPath (Join-Path $Project '.git'))) { return @() }
  @(& git -C $Project status --porcelain --untracked-files=all -- . ':(exclude).dispatch')
}

# The paths of the section "Files to change" of the plan of the task (ticket leftover-files-block-tasks):
# each path in backticks, and the first word of a list item when it is no backtick span. A path has a
# / or a dot and no space; a trailing ,:;.) goes. The Rust rule plan_files is the same.
function Plan-Files {
  $file = Join-Path $Reports "plans\$Id.md"
  if (-not (Test-Path -LiteralPath $file)) { return @() }
  $files = New-Object System.Collections.Generic.List[string]
  $inside = $false
  foreach ($raw in Get-Content -LiteralPath $file -Encoding UTF8) {
    $line = $raw.Trim()
    if ($line.StartsWith('#')) { $inside = $line.TrimStart('#').Trim() -eq 'Files to change'; continue }
    if (-not $inside) { continue }
    $tokens = @()
    if ($line -match '^[-*] +(\S+)' -and -not $Matches[1].StartsWith('`')) { $tokens += $Matches[1] }
    $spans = $line.Split('`')
    for ($at = 1; $at -lt $spans.Count - 1; $at += 2) { $tokens += $spans[$at] }
    foreach ($token in $tokens) {
      $path = $token.Trim().TrimEnd(',', ':', ';', '.', ')')
      if (-not $path -or $path -match '\s' -or -not ($path.Contains('/') -or $path.Contains('.'))) { continue }
      $path = $path.Replace('\', '/')
      if ($path.StartsWith('./')) { $path = $path.Substring(2) }
      if ($path -and -not $files.Contains($path)) { $files.Add($path) }
    }
  }
  $files.ToArray()
}

# The lines of git status outside .dispatch that touch a file of the task (ticket
# leftover-files-block-tasks): the path, or one of the two paths of a rename, is a task file, lies in a
# task folder, or is an untracked folder that holds a task file. Without case. The task files are the
# files that the task branch changed while it exists and is not merged, and with -Plan also the paths
# of Plan-Files. The Rust rule task_leftovers is the same.
function Task-Leftovers([switch]$Plan) {
  $dirty = @(& git -C $Project status --porcelain -- . ':(exclude).dispatch' | Where-Object { $_ })
  if ($dirty.Count -eq 0) { return @() }
  $files = @()
  if ($Plan) { $files += @(Plan-Files) }
  $info = Read-BranchFile $Id
  if ($info -and $info.branch -and $info.base -and -not $info.merge -and (Invoke-Git @('rev-parse', '--verify', '--quiet', "refs/heads/$($info.branch)")).Code -eq 0) {
    $diff = Invoke-Git @('diff', '--name-only', "$($info.base)...$($info.branch)")
    if ($diff.Code -eq 0) { $files += @($diff.Lines | Where-Object { $_ }) }
  }
  $files = @($files | ForEach-Object { $_.Replace('\', '/').ToLowerInvariant() })
  if ($files.Count -eq 0) { return @() }
  foreach ($line in $dirty) {
    $paths = @($line.Substring([Math]::Min(3, $line.Length)) -split ' -> ' | ForEach-Object { $_.Trim().Trim('"').Replace('\', '/').ToLowerInvariant() } | Where-Object { $_ })
    $hit = $false
    foreach ($path in $paths) {
      foreach ($file in $files) {
        if ($path -eq $file -or ($file.EndsWith('/') -and $path.StartsWith($file)) -or ($path.EndsWith('/') -and $file.StartsWith($path))) { $hit = $true }
      }
    }
    if ($hit) { $line }
  }
}

# The lines of Task-Leftovers that the work run wrote itself (ticket task-branches-blocked-work): the
# newest file under the path (the new path of a rename, each file of an untracked folder) is not later
# than the end of the run plus 2 s. The end is the report time, for a stopped task the state file time;
# without it nothing counts. A path without a file (a deletion) never counts. The Rust rule
# own_changes is the same.
function Own-Changes([string[]]$Lines) {
  $end = if ((Get-State) -eq 'stopped') { State-Path } else { Join-Path $Reports "$Id.md" }
  if (-not (Test-Path -LiteralPath $end)) { return }
  $limit = (Get-Item -LiteralPath $end).LastWriteTimeUtc.AddSeconds(2).Ticks
  foreach ($line in $Lines) {
    if (-not $line -or $line.Length -le 3) { continue }
    $full = Join-Path $Project ((($line.Substring(3) -split ' -> ')[-1]).Trim().Trim('"'))
    if (-not (Test-Path -LiteralPath $full)) { continue }
    $item = Get-Item -LiteralPath $full -Force
    $files = if ($item.PSIsContainer) { @(Get-ChildItem -LiteralPath $full -Recurse -File -Force) } else { @($item) }
    if ($files.Count -eq 0) { continue }
    $newest = 0
    foreach ($file in $files) { if ($file.LastWriteTimeUtc.Ticks -gt $newest) { $newest = $file.LastWriteTimeUtc.Ticks } }
    if ($newest -le $limit) { $line }
  }
}

# Commits the own changes of the work run (Own-Changes) by path on the current branch as "<ticket> WIP"
# (ticket task-branches-blocked-work). Gives the reason of a failure, or ''.
function Commit-Own([string[]]$Lines) {
  $paths = @($Lines | Where-Object { $_ -and $_.Length -gt 3 } | ForEach-Object { $_.Substring(3) -split ' -> ' } | ForEach-Object { $_.Trim().Trim('"') } | Where-Object { $_ })
  if ($paths.Count -eq 0) { return '' }
  $add = Invoke-Git (@('add', '--') + $paths)
  if ($add.Code -ne 0) { return "git could not add the files of the work run: $($add.Text)" }
  $commit = Invoke-Git (@('commit', '-q', '-m', "$Id WIP (Dispatch: files of the work run)", '--') + $paths)
  if ($commit.Code -ne 0) { return "git could not commit the files of the work run: $($commit.Text)" }
  ''
}

# Brings the newer commits of the base into the task branch in the checkout (ticket
# task-branches-blocked-work), so a fix that reached the base after the branch started reaches the
# task: git merge-tree of the branch and the base, the folder .dispatch as the base has it (Dispatch
# keeps the task files current there), a merge commit with both parents, and a fast-forward of the
# branch. A file outside .dispatch that changed on both sides stops it, and the branch stays as it
# was. Gives the reason when the base has newer commits that did not come in, else ''.
# -Before: the checkout is not on the branch yet (ticket merge-issue); the merge commit then moves the
# ref of the branch only. Its .dispatch is the one of the base, so `git log <base>..<branch> --
# .dispatch` does not list it.
function Sync-Base([switch]$Before) {
  $info = Read-BranchFile $Id
  if ($null -eq $info -or -not $info.branch -or -not $info.base -or $info.merge) { return '' }
  $on = (Current-Branch) -eq $info.branch
  if ($on -eq [bool]$Before) { return '' }
  if ((Invoke-Git @('rev-parse', '--verify', '--quiet', "refs/heads/$($info.base)")).Code -ne 0) { return "the branch $($info.base) does not exist" }
  if ((Invoke-Git @('merge-base', '--is-ancestor', $info.base, $info.branch)).Code -eq 0) { return '' }
  $tip = "$((Invoke-Git @('rev-parse', '--verify', "refs/heads/$($info.branch)")).Lines | Select-Object -Last 1)".Trim()
  $merge = Invoke-Git @('merge-tree', '--write-tree', '--name-only', '--no-messages', $info.branch, $info.base)
  $lines = @($merge.Lines | Where-Object { $_ })
  if (($merge.Code -ne 0 -and $merge.Code -ne 1) -or $lines.Count -eq 0) { return "git merge-tree failed: $($merge.Text)" }
  $clash = @($lines | Select-Object -Skip 1 | Where-Object { -not $_.StartsWith('.dispatch/') })
  if ($clash.Count -gt 0) { return "these files changed on both sides: $($clash -join ', ')" }
  # A separate index builds the tree; the index of the checkout stays as it is.
  $index = Join-Path ([IO.Path]::GetTempPath()) "dispatch-sync-$PID.index"
  $env:GIT_INDEX_FILE = $index
  try {
    $built = (Invoke-Git @('read-tree', $lines[0])).Code -eq 0
    if ($built) { $built = (Invoke-Git @('rm', '--cached', '-r', '-q', '-f', '--ignore-unmatch', '--', '.dispatch')).Code -eq 0 }
    if ($built -and (Invoke-Git @('rev-parse', '--verify', '--quiet', "$($info.base):.dispatch")).Code -eq 0) {
      $built = (Invoke-Git @('read-tree', '--prefix=.dispatch/', "$($info.base):.dispatch")).Code -eq 0
    }
    $tree = if ($built) { Invoke-Git @('write-tree') } else { $null }
  } finally {
    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $index -Force -ErrorAction SilentlyContinue
  }
  if ($null -eq $tree -or $tree.Code -ne 0) { return 'git could not build the merged tree' }
  $commit = Invoke-Git @('commit-tree', "$($tree.Lines | Select-Object -Last 1)".Trim(), '-p', $tip, '-p', $info.base, '-m', "Merge $($info.base) into $($info.branch) (${Id}: the newer commits of the base)")
  if ($commit.Code -ne 0) { return "git could not make the merge commit: $($commit.Text)" }
  $made = "$($commit.Lines | Select-Object -Last 1)".Trim()
  $forward = if ($on) { Invoke-Git @('merge', '--ff-only', '-q', $made) } else { Invoke-Git @('update-ref', "refs/heads/$($info.branch)", $made, $tip) }
  if ($forward.Code -ne 0) { return "git could not move the branch: $($forward.Text)" }
  ''
}

# Whether a commit tracks a file under .dispatch.
function Tracks-Tasks([string]$Rev) {
  $result = Invoke-Git @('ls-tree', '-r', '--name-only', $Rev, '--', '.dispatch')
  $result.Code -eq 0 -and @($result.Lines | Where-Object { $_ }).Count -gt 0
}

# Makes a task branch hold the task files the way the checkout holds them (ticket
# local-only-deletes-task-files), so no checkout, sync or merge deletes or overwrites a file of
# .dispatch on the disk. "Task files local only" on: a branch that tracks a file of .dispatch, while
# HEAD tracks none, gets a commit that takes the folder out of git. Off: a branch that tracks none,
# while HEAD tracks some, gets the folder as HEAD has it. Git writes a tracked file of the target over
# an ignored file, and deletes a tracked file that the target lacks. It moves the ref only, through an
# index of its own; nothing is checked out. Gives the reason of a failure, or ''.
function Align-Tasks([string]$Branch) {
  $ref = "refs/heads/$Branch"
  $old = Invoke-Git @('rev-parse', '--verify', '--quiet', $ref)
  if ($old.Code -ne 0) { return '' }
  $tip = "$($old.Lines | Select-Object -Last 1)".Trim()
  $local = Tasks-Local
  $branchTracks = Tracks-Tasks $tip
  $headTracks = Tracks-Tasks 'HEAD'
  if ($local -and -not ($branchTracks -and -not $headTracks)) { return '' }
  if (-not $local -and -not ($headTracks -and -not $branchTracks)) { return '' }
  $message = if ($local) { 'Dispatch: task files are local only' } else { 'Dispatch: task files in git again' }
  $index = Join-Path ([IO.Path]::GetTempPath()) "dispatch-align-$PID.index"
  $env:GIT_INDEX_FILE = $index
  try {
    $built = (Invoke-Git @('read-tree', $tip)).Code -eq 0
    if ($built -and $local) { $built = (Invoke-Git @('rm', '--cached', '-r', '-q', '-f', '--ignore-unmatch', '--', '.dispatch')).Code -eq 0 }
    if ($built -and -not $local) { $built = (Invoke-Git @('read-tree', '--prefix=.dispatch/', 'HEAD:.dispatch')).Code -eq 0 }
    $tree = if ($built) { Invoke-Git @('write-tree') } else { $null }
  } finally {
    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $index -Force -ErrorAction SilentlyContinue
  }
  if ($null -eq $tree -or $tree.Code -ne 0) { return "git could not build the task files of $Branch" }
  $commit = Invoke-Git @('commit-tree', "$($tree.Lines | Select-Object -Last 1)".Trim(), '-p', $tip, '-m', $message)
  if ($commit.Code -ne 0) { return "git could not commit the task files of ${Branch}: $($commit.Text)" }
  $move = Invoke-Git @('update-ref', $ref, "$($commit.Lines | Select-Object -Last 1)".Trim(), $tip)
  if ($move.Code -ne 0) { return "git could not move ${Branch}: $($move.Text)" }
  ''
}

# The sentence for the prompt of a run when Sync-Base did not bring the newer commits of the base, else
# ''. Quotes and line breaks go, as in a reply.
function Sync-Note {
  $why = "$(Sync-Base)"
  if (-not $why) { return '' }
  $why = (($why -replace '"', "'") -replace "`r?`n", ' ').Trim().TrimEnd('.')
  " Dispatch could not bring the newer commits of $((Read-BranchFile $Id).base) into this branch: $why."
}

# Runs one measuring command with cmd.exe in the project folder, its output into the log. The
# command goes into a batch file next to the log, so a redirect of its own stays its own. A command
# that runs longer than the limit is ended with its process tree; the result is then 124.
function Run-Measured([string]$Command, [string]$Log, [int]$Limit) {
  $batch = [IO.Path]::ChangeExtension($Log, '.cmd')
  [IO.File]::WriteAllText($batch, "@echo off`r`n$Command`r`n")
  $info = New-Object System.Diagnostics.ProcessStartInfo
  $info.FileName = $env:ComSpec
  $info.Arguments = "/d /s /c `"`"$batch`" > `"$Log`" 2>&1`""
  $info.WorkingDirectory = $Project
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $process = [Diagnostics.Process]::Start($info)
  if ($process.WaitForExit($Limit * 1000)) {
    $process.WaitForExit()
    return $process.ExitCode
  }
  # cmd.exe redirects the output of taskkill itself, so PowerShell sees no error text.
  & $env:ComSpec /d /c "taskkill /PID $($process.Id) /T /F >nul 2>&1"
  $process.WaitForExit(10000) | Out-Null
  try { [IO.File]::AppendAllText($Log, "`nstopped after $Limit s: the time limit of the measuring commands`n") } catch { }
  124
}

# The lines of <ticket-id>.checks: the name, a tab and the command of each check that runs (Pick the
# checks after the work run). $List, when given, is another list file of that form (the full run).
# Empty without the file.
function Chosen-Checks([string]$List) {
  $file = if ($List) { $List } else { Join-Path $Reports "$Id.checks" }
  if (-not (Test-Path -LiteralPath $file)) { return @() }
  @(Get-Content -LiteralPath $file | Where-Object { $_.Trim() -ne '' })
}

# Removes the pick files of the task before a new work run (Pick the checks after the work run): a
# new report gets a new pick, a new choice and a new run.
function Clear-Picks {
  foreach ($name in @("$Id.checks", "$Id.picked", "$Id.checked")) {
    Remove-Item -LiteralPath (Join-Path $Reports $name) -Force -ErrorAction SilentlyContinue
  }
  $folder = Join-Path $Reports "checks\$Id"
  if (Test-Path -LiteralPath $folder) { Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue }
}

# Runs the checks of <ticket-id>.checks one by one with Run-Measured and writes <ticket-id>.checked
# line by line: "running", the time and the place first, then the name, the exit code and the seconds
# of each check as it ends, then "done" and the time. $LogOf gives the log path of check n. In the
# places 'run' and 'full' each result goes to the console as "<name>: exit <code>", not into the pipeline. With -StopAtFailure the
# run ends at the first failed check and gives its name, exit code, command and log; else it gives
# $null after every check ran. $List and $Checked, when given, replace the list file and the results
# file of the task (the full run).
function Run-Checks([string]$Place, [scriptblock]$LogOf, [int]$Limit, [switch]$StopAtFailure, [string]$List, [string]$Checked) {
  $checked = if ($Checked) { $Checked } else { Join-Path $Reports "$Id.checked" }
  [IO.File]::WriteAllText($checked, "running`t$((Get-Date).ToString('o'))`t$Place`n")
  $n = 0
  foreach ($line in @(Chosen-Checks $List)) {
    $n++
    $parts = $line -split "`t", 2
    $name = $parts[0].Trim()
    $command = if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }
    if (-not $name -or -not $command) { continue }
    $log = & $LogOf $n
    $started = Get-Date
    $code = Run-Measured $command $log $Limit
    $seconds = [int][Math]::Round(((Get-Date) - $started).TotalSeconds)
    [IO.File]::AppendAllText($checked, "$name`t$code`t$seconds`n")
    # The merge verb prints its merge line first: Dispatch reads the first line of its output.
    if ($Place -eq 'run' -or $Place -eq 'full') { [Console]::Out.WriteLine("$name`: exit $code") }
    if ($StopAtFailure -and $code -ne 0) {
      [IO.File]::AppendAllText($checked, "done`t$((Get-Date).ToString('o'))`n")
      return [pscustomobject]@{ Name = $name; Code = $code; Command = $command; Log = $log }
    }
  }
  [IO.File]::AppendAllText($checked, "done`t$((Get-Date).ToString('o'))`n")
  return $null
}

# Resumes the session of $Worker for $Run with $Text: the start of reply and of resume. $State, when
# given, is written before the branch switch; without it a tracked branch rewrites the current state.
# The own changes of a stopped run are found while the state still says stopped: the state file time
# is the end of that run. A failed claude writes $StateOnFailure back when one is given.
function Resume-Run([string]$Run, $Worker, [string]$Text, [string]$State, [string]$StateOnFailure) {
  $flags = Run-Flags $Run
  # Task branches: a work run goes on on the branch of its task, also after Dispatch switched back.
  $branch = if ($Run -eq 'work' -and (Branches-On)) { Read-BranchFile $Id } else { $null }
  $tracked = $null -ne $branch -and -not $branch.merge
  # The state file is newer than the report before the checkout: Dispatch keeps the checkout on the
  # task branch until the worker writes its report again, also while Claude Code starts.
  if ($tracked -and -not $State) { Set-State (Get-State) }
  # A switch onto the task branch waits for the changes in files of the task (ticket
  # leftover-files-block-tasks); on its branch already, the run's own edits do not hold it. The
  # run's own files off its branch (a forgotten WIP commit) go along and are committed on the
  # branch (ticket task-branches-blocked-work).
  $own = @()
  if ($tracked -and (Current-Branch) -ne $branch.branch) {
    $held = @(Task-Leftovers -Plan)
    $own = @(Own-Changes $held)
    $held = @($held | Where-Object { $own -notcontains $_ })
    if ($held.Count -gt 0) { Refuse "these changes in files of this task are not committed; commit, stash or undo them first:`n$($held -join "`n")" }
  }
  if ($State) { Set-State $State }
  $back = if ($tracked) { Enter-TaskBranch } else { '' }
  if ($own.Count -gt 0) {
    $failed = Commit-Own $own
    if ($failed) { [Console]::Error.WriteLine("warning: $failed; the files stay uncommitted on $($branch.branch)") }
  }
  # The branch takes the newer commits of the base at each start (ticket task-branches-blocked-work).
  # Its fast-forward can write the report of the base; the state file is then written again, so it
  # stays newer than the report and Dispatch keeps the checkout on the branch.
  if ($tracked) {
    $Text += Sync-Note
    Set-State (Get-State)
  }
  $printed = Run-Claude (@($Text, '--resume', $Worker.sessionId, '--bg', '--name', (Run-Name $Run)) + $flags) $StateOnFailure $back
  Save-Session $Run $printed
}

# Starts a plan run as a new session: the verb plan, and a Reject of a plan whose plan run is not on
# this computer (ticket reject-isnt-working). $Extra goes into the prompt before the measure note.
function Start-Plan([string]$Extra) {
  $brief = Join-Path $Reports "briefs\$Id.md"
  if (-not (Test-Path -LiteralPath $brief)) { Refuse "no brief at $brief" }
  $plans = Join-Path $Reports 'plans'
  if (-not (Test-Path -LiteralPath $plans)) { New-Item -ItemType Directory -Path $plans | Out-Null }
  $plan = Join-Path $plans "$Id.md"
  $flags = Run-Flags 'plan'
  $prompt = "Plan ticket $Id. Read the brief $brief. Write the plan to $plan with the sections that the section Background workers in CLAUDE.md names. Edit nothing. Then end your turn."
  if ($Extra) { $prompt += " $Extra" }
  # Task branches: the base of the task is the branch of the checkout at the plan run.
  if (Branches-On) { Ensure-BranchFile | Out-Null }
  # The scratch folder of an earlier plan run of the ticket goes: its logs belong to that plan.
  $scratch = Join-Path $Reports "measure\$Id"
  if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
  if (Measure-On) { $prompt += " Measure before the plan is on: first run the listed measuring commands as the section Background workers says, and write the section Measured." }
  Set-State 'planning'
  $printed = Run-Claude (@($prompt, '--bg', '--name', (Run-Name 'plan')) + $flags)
  Save-Session 'plan' $printed
}

switch ($Verb) {
  'plan' {
    Need-Id
    Start-Plan ''
  }
  'approve' {
    Need-Id
    $plan = Join-Path $Reports "plans\$Id.md"
    if (-not (Test-Path -LiteralPath $plan)) { Refuse "no plan at $plan" }
    if (Test-Path -LiteralPath (Join-Path $Project '.git')) {
      # Only a change in a file of this task holds it (ticket leftover-files-block-tasks); the other
      # changes stay where they are. The task files in .dispatch do not count: Dispatch commits them
      # itself, and triage can write a brief at any time.
      $held = @(Task-Leftovers -Plan)
      if ($held.Count -gt 0) { Refuse "these changes in files of this task are not committed; commit, stash or undo them first:`n$($held -join "`n")" }
    }
    Approve-Check
    $flags = Run-Flags 'work'
    # The work run is a new session (ticket work-fresh-session): the prompt names the ticket, the
    # brief, the plan and the report file, and the files are its whole input.
    $brief = Join-Path $Reports "briefs\$Id.md"
    $report = Join-Path $Reports "$Id.md"
    $prompt = "Plan approved: $plan. This is the work run of ticket $Id. Read the brief $brief and the plan first: they are the whole context of this run, and the sections User decisions and Checks chosen of the plan hold the choices of the user. Execute the plan. A deviation goes into the report, not into the code. Commit only the files that this run changed, each by name in git add and git commit; never run git add -A, git add . or git commit -a, and leave every other change in the checkout as it is. Write the report to $report."
    # Pick the checks after the work run: the files of an earlier pick go, and the run is told that the
    # checks come after it. Else the checks that the user chose at Approve (ticket task-checks): one
    # line per check, the name, a tab and the command. A quote of a name becomes an apostrophe, as in a reply.
    $chosen = Join-Path $Reports "$Id.checks"
    if (Option-On 'pickChecks') {
      Clear-Picks
      $prompt += " The checks of this project are picked by Dispatch after this run: run the Acceptance commands of the plan, but no check of the list checks.txt beyond them."
    }
    # Confirm check results automatically (ticket confirm-a-check-step-s-result-on-the): the run does
    # not stop at a check step whose result the user would confirm.
    if (Option-On 'confirmResults') {
      $prompt += " Confirm check results automatically is on: a check step whose result the user would confirm does not stop this run; take the result as it is, write it into the report under the heading Check result, and go on."
    }
    if (Test-Path -LiteralPath $chosen) {
      $names = @(Get-Content -LiteralPath $chosen | Where-Object { $_.Trim() -ne '' } | ForEach-Object { ($_ -split "`t", 2)[0].Trim() -replace '"', "'" })
      # Checks at the gate (ticket simpler-process): with task branches and a gate command, the gate
      # runs the chosen checks once, at the merge, so the work run does not run them.
      $gateLine = "$(@(Command-Lines 'merge-gate.txt') | Select-Object -First 1)"
      if ($names.Count -gt 0 -and (Option-On 'checksAtGate') -and (Branches-On) -and $gateLine) {
        $prompt += " The user chose these checks: $($names -join ', '). Checks at the gate is on: the gate command runs them once, at the merge, so this run does not run them and needs no Verification claim for them."
      } elseif ($names.Count -gt 0) {
        $prompt += " The user chose these checks: $($names -join ', '). Run each with the command of the section Checks chosen of the plan, as written there: Dispatch looks for that command in the commands of this run."
      }
    }
    # Task branches: the work run works on the branch of the task; a failed start checks out the branch before.
    $back = ''
    if (Branches-On) {
      $back = Enter-TaskBranch
      $prompt += " Task branches is on: the checkout is on the branch $((Read-BranchFile $Id).branch) of this task. Commit there; switch no branch, merge nothing, push nothing. When this run stops with a question or fails, first commit its own changed files by name on this branch with the message $Id WIP."
      # An existing branch (a new plan after an earlier work run) takes the newer commits of the base.
      $prompt += Sync-Note
    }
    Set-State 'approved'
    # A new session, no resume: the work run gets the brief, the plan and the decisions of the user,
    # and nothing of the context of the plan run (ticket work-fresh-session); the model switch stays.
    # The name keeps the session with the task and marks it as the work run: Dispatch and this script
    # find a worker by its name.
    $printed = Run-Claude (@($prompt, '--bg', '--name', (Run-Name 'work')) + $flags) 'planning' $back
    Set-State 'working'
    Save-Session 'work' $printed
  }
  'reply' {
    Need-Id
    # Windows PowerShell 5.1 does not escape a double quote in an argument of a native command, and
    # claude is a command file, so cmd.exe parses the line too. A quote becomes an apostrophe and a
    # line break a space, so the text can never cut or merge the arguments after it.
    $text = ((($Message -join ' ') -replace '"', "'") -replace "`r?`n", ' ').Trim()
    if (-not $text) { Refuse 'reply needs a message' }
    $run = if ((Get-State) -eq 'planning') { 'plan' } else { 'work' }
    # A Reject of a plan whose plan run is not on this computer (made on another computer, or its
    # session is gone): a new plan run starts here and revises the old plan (ticket reject-isnt-working).
    $oldPlan = Join-Path $Reports "plans\$Id.md"
    if ($run -eq 'plan' -and (Test-Path -LiteralPath $oldPlan) -and $null -eq (Find-Worker (Get-Workers) $Id 'plan')) {
      Start-Plan "The user rejected the plan $oldPlan of an earlier plan run: $text Read that plan and revise it."
      break
    }
    $worker = Resume-Target $run
    # A reply of the work run brings a new report: the pick of the old one goes (Pick the checks after the work run).
    if ($run -eq 'work' -and (Option-On 'pickChecks')) { Clear-Picks }
    Resume-Run $run $worker $text '' ''
  }
  'status' {
    $workers = Get-Workers
    Get-ChildItem -LiteralPath $Reports -Filter '*.state' | ForEach-Object {
      $ticket = $_.BaseName
      $state = (Get-Content -LiteralPath $_.FullName -Raw).Trim()
      "$ticket`t$state`t$(Worker-State (Find-Worker $workers $ticket))"
    }
  }
  'measure' {
    Need-Id
    if (-not (Measure-On)) { Refuse 'Measure before the plan is off in this project; Setup pipeline turns it on' }
    if ((Get-State) -ne 'planning') { Refuse "the task $Id is not in its plan run; only a plan run measures" }
    # @() keeps a list of one command a list: a plain string would give its first character.
    $commands = @(Measure-Commands)
    $n = 0
    if ($null -eq $Message -or @($Message).Count -ne 1 -or -not [int]::TryParse("$($Message[0])", [ref]$n) -or $n -lt 1 -or $n -gt $commands.Count) {
      Refuse "measure takes the number of a command of measure-commands.txt (1 to $($commands.Count)); nothing else runs"
    }
    $folder = Join-Path $Reports "measure\$Id"
    if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
    $command = $commands[$n - 1].Replace('{measure}', $folder)
    $log = Join-Path $folder "$n.log"
    $limit = Measure-Limit
    Enter-Lock (Lock-Path) $limit 'measuring'
    try {
      $before = Tree-Status
      $code = Run-Measured $command $log $limit
      $after = Tree-Status
    } finally {
      Exit-Lock (Lock-Path)
    }
    # The working tree must stay as it was; a change is noted for the task card of Dispatch.
    $changed = @($after | Where-Object { $_ -and $before -notcontains $_ }) + @($before | Where-Object { $_ -and $after -notcontains $_ })
    if ($changed.Count -gt 0) {
      $note = Join-Path $folder 'changed.txt'
      $known = if (Test-Path -LiteralPath $note) { @(Get-Content -LiteralPath $note) } else { @() }
      $new = @($changed | Where-Object { $known -notcontains $_ })
      if ($new.Count -gt 0) { [IO.File]::AppendAllText($note, (($new -join "`n") + "`n")) }
      [Console]::Error.WriteLine("warning: command $n changed files of the project outside .dispatch:`n$($changed -join "`n")")
    }
    "command $n ($($commands[$n - 1])) ended with exit code $code; the output is in $log. Its last 40 lines:"
    if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 40 }
    exit $code
  }
  'merge' {
    Need-Id
    if ($env:CLAUDECODE) { Refuse 'only the Merge button of Dispatch merges a task branch; a Claude Code session may not run this verb' }
    # A branch that waits keeps its merge also with Task branches off (ticket local-only-deletes-task-files).
    $info = Read-BranchFile $Id
    if ($null -eq $info -or -not $info.branch -or -not $info.base) { Refuse "the task $Id has no branch file" }
    if ($info.merge) { Refuse "the branch $($info.branch) is merged already: $($info.merge)" }
    if ((Invoke-Git @('rev-parse', '--verify', '--quiet', "refs/heads/$($info.branch)")).Code -ne 0) { Refuse "the branch $($info.branch) does not exist; the task had no work run" }
    $current = Current-Branch
    if ($current -ne $info.base) { Refuse "the checkout is on $current, not on the base $($info.base); Dispatch switches back when the work run has ended" }
    if ((Invoke-Git @('rev-parse', '-q', '--verify', 'MERGE_HEAD')).Code -eq 0) { Refuse 'the checkout has an unfinished merge; finish it or run git merge --abort first' }
    # Only a change in a file that the branch changes holds the merge (ticket leftover-files-block-tasks);
    # the gate then runs with the other changes in the folder.
    $held = @(Task-Leftovers)
    if ($held.Count -gt 0) { Refuse "the checkout has uncommitted changes in files that the branch changes; commit, stash or undo them first:`n$($held -join "`n")" }
    # The changed task files (the tickets file among them) go into one commit first, so git merge
    # never meets a changed file of .dispatch. With "Task files local only" on, git tracks none.
    $local = Tasks-Local
    $tasks = if ($local) { @() } else { @((Invoke-Git @('status', '--porcelain', '--untracked-files=all', '--', '.dispatch')).Lines | Where-Object { $_ }) }
    if ($tasks.Count -gt 0) {
      Invoke-Git @('add', '--all', '--', '.dispatch') | Out-Null
      $saved = Invoke-Git @('commit', '-q', '-m', "Dispatch: task files before the merge of $Id", '--', '.dispatch')
      if ($saved.Code -ne 0) { Refuse "git could not commit the task files before the merge: $($saved.Text)" }
    }
    # The branch holds the task files as the base does, so the merge deletes none of them.
    $aligned = "$(Align-Tasks $info.branch)"
    if ($aligned) { Refuse $aligned }
    $gate ="$(@(Command-Lines 'merge-gate.txt') | Select-Object -First 1)"
    $limit = Command-Limit 'merge-gate.txt' 14400
    # A switch back of Dispatch holds the lock for seconds (ticket merge-issue): the merge waits up to 60 s.
    Enter-Lock (Merge-Lock) 60 'merge'
    $script:Merged = ''
    # The marker of the open merge (ticket title-auto-merge-left-the-main-checkout): the process id,
    # the ticket, the tip of the task branch, the HEAD of the base and the time. A killed script runs
    # no finally; Dispatch then finds the marker and cancels the merge that it names.
    $aborted = Join-Path $Reports "merge\$Id.aborted"
    Remove-Item -LiteralPath $aborted -Force -ErrorAction SilentlyContinue
    $marker = Join-Path $Reports 'merge\open'
    $tip = (Invoke-Git @('rev-parse', "refs/heads/$($info.branch)")).Text
    $head = (Invoke-Git @('rev-parse', 'HEAD')).Text
    [IO.File]::WriteAllText($marker, "$PID`t$Id`t$tip`t$head`t$((Get-Date).ToString('o'))`n")
    try {
      $refusal = "$(Merge-Branch $info $gate $limit)"
    } finally {
      # Every way out inside PowerShell (a refusal, an error, Ctrl+C) leaves no open merge.
      if (-not $script:Merged -and (Invoke-Git @('rev-parse', '-q', '--verify', 'MERGE_HEAD')).Code -eq 0) {
        Invoke-Git @('merge', '--abort') | Out-Null
      }
      Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
      Exit-Lock (Merge-Lock)
    }
    if ($refusal) { Refuse $refusal }
    "merged $($info.branch) into $($info.base): $script:Merged"
    # Push after the merge (ticket simpler-process): after a merge commit, outside the merge lock, which
    # guards the checkout and the gate, not the network. A failed push leaves the merge as it is, and
    # the verb ends with 0: the merge is done.
    if ($script:Merged -ne 'nothing to merge' -and (Option-On 'pushAfterMerge') -and (Branches-On)) {
      $push = Push-Base $info.base
      $merged = Read-BranchFile $Id
      Write-BranchFile $merged.branch $merged.base $merged.merge $push
      if ($push.StartsWith('pushed ')) { "pushed $($info.base) to $(($push -split ' ')[1])" }
      elseif ($push -eq 'no-upstream') { "no push: $($info.base) has no upstream branch" }
      else { "push failed: $($push.Substring(7)); $($info.base) is merged on this computer only" }
    }
  }
  'check' {
    Need-Id
    if ($Id -eq 'full') {
      # The scheduled full run: every check of the list that Dispatch wrote, in the checkout on the
      # base branch. A task branch in the checkout would test the half-done change of a task.
      $branch = Current-Branch
      if ($branch.StartsWith('task/')) { Refuse "the checkout is on the task branch $branch; the full run waits for the base branch" }
      $folder = Join-Path $Reports 'checks\full'
      $list = Join-Path $folder 'checks'
      if (-not (Test-Path -LiteralPath $list)) { Refuse "the full run has no list of checks at $list; Dispatch writes it when the run is due" }
      Get-ChildItem -LiteralPath $folder -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -eq '.log' -or $_.Extension -eq '.cmd' } | Remove-Item -Force -ErrorAction SilentlyContinue
      $limit = Command-Limit 'checks.txt' 14400
      Enter-Lock (Lock-Path) $limit 'full check run'
      try {
        Run-Checks 'full' { param($n) Join-Path $folder "$n.log" } $limit -List $list -Checked (Join-Path $folder 'checked') | Out-Null
      } finally {
        Exit-Lock (Lock-Path)
      }
      return
    }
    if (-not (Option-On 'pickChecks')) { Refuse 'Pick the checks after the work run is off in this project; Setup pipeline turns it on' }
    if (-not (Test-Path -LiteralPath (Join-Path $Reports "$Id.checks"))) { Refuse "the task $Id has no chosen checks; Run checks in its task card writes them first" }
    $folder = Join-Path $Reports "checks\$Id"
    if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder -Force | Out-Null }
    $limit = Command-Limit 'checks.txt' 14400
    # One command run at a time per project, as a measuring: the projects use fixed test ports. The
    # merge lock stays free: it guards the checkout, and Dispatch waits for it at the switch back.
    Enter-Lock (Lock-Path) $limit 'check run'
    try {
      Run-Checks 'run' { param($n) Join-Path $folder "$n.log" } $limit | Out-Null
    } finally {
      Exit-Lock (Lock-Path)
    }
  }
  'stop' {
    Need-Id
    $worker = Find-Worker (Get-Workers) $Id
    # A finished plan run whose session is no longer listed has nothing to stop: only the state is written.
    if ($null -eq $worker) {
      if ((Get-State) -ne 'planning') { Refuse "no worker named $Id" }
    } else {
      & claude stop $worker.id
      if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    Set-State 'stopped'
  }
  'resume' {
    Need-Id
    # Only a stopped task is resumed; the newest worker of the ticket is the one that stop stopped.
    if ((Get-State) -ne 'stopped') { Refuse "the task $Id is not stopped; only a stopped task is resumed" }
    $worker = Find-Worker (Get-Workers) $Id
    if ($null -eq $worker) { Refuse "no worker named $Id on this computer. A run that started on another computer cannot be resumed here: start the task again, or resume it on the computer where it ran" }
    $state = Worker-State $worker
    if ($state -eq 'working' -or $state -eq 'blocked') {
      Refuse "the worker $Id still runs ($state); a resume now would start a copy. A permission prompt is answered in the agent view: claude attach $($worker.id)"
    }
    if (-not $worker.sessionId) { Refuse "the worker $Id has no session id" }
    # The name gives the run; a worker of an older script has the bare id: a plan file means the work run.
    $run = if ($worker.name -eq "$Id [plan]") { 'plan' }
      elseif ($worker.name -eq "$Id [work]") { 'work' }
      elseif (Test-Path -LiteralPath (Join-Path $Reports "plans\$Id.md")) { 'work' }
      else { 'plan' }
    $text = 'The user stopped this run in Dispatch and now resumes it. Go on where you stopped.'
    $next = if ($run -eq 'plan') { 'planning' } else { 'working' }
    Resume-Run $run $worker $text $next 'stopped'
  }
}
