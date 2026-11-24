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
work-tools.txt, when it exists, adds one tool name per line to the --tools list of the work run.
The script finds the project from its own folder, and the options files name paths relative to the
project, so a clone on another path or computer needs no change.
With Work folder on (workFolders and taskBranches in options.json), approve, reply, resume of a work run and check <ticket-id> work in the work folder work\1, a git worktree on the task branch;
work-setup.txt runs once in a new one, work\1.record names the task that uses it, and a second task is refused until the folder is free.
merge <ticket-id> then builds the merge commit and runs the checks and the gate in the work folder (it waits up to 60 s for it),
and moves the base in one step: git merge --ff-only in the project folder, or git update-ref when it is on another branch.
A work run whose session started in the project folder before the switch goes on as a new session in the work folder.
With workFolderCount 2 to 4 in options.json, the work folders are work\1 to work\<n>, each with its own record work\<n>.record
and its own set-up log work\<n>-setup.log, and up to <n> tasks are in their work run at the same time. Approve and merge take
the free folder that has the task branch checked out, else the one whose record names the task, else the lowest free one;
a reply, a resume and check <ticket-id> go into the folder where the session of the work run runs, also above the number.
A work run is refused while a task in another folder has a plan that names a file of its plan. The refusals:
"all <n> work folders of this project are in use (tasks a, b); the run starts when one is free" (the merge: "...; the merge
starts when one is free"), "the work folder <k> of this project, where the session of this task runs, is in use (task x);
the run starts when it is free", and "the task x works on the same files (a, b); this work run starts when it has ended".
With one folder the texts name "the work folder of this project", as before. A folder above the number stays on the disk.
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
# For the work run, each tool name of work-tools.txt (one per line; # starts a note) that the list
# after --tools lacks goes at its end.
function Run-Flags([string]$Run) {
  $lines = @(Read-Flags "$Run-flags.txt")
  $tools = Join-Path $Reports 'work-tools.txt'
  if ($Run -eq 'work' -and (Test-Path -LiteralPath $tools)) {
    $at = [Array]::IndexOf([string[]]($lines | ForEach-Object { $_.Trim() }), '--tools')
    if ($at -ge 0 -and $at + 1 -lt $lines.Count) {
      $list = @($lines[$at + 1].Trim() -split ',' | Where-Object { $_ -ne '' })
      foreach ($name in @(Get-Content -LiteralPath $tools)) {
        $name = $name.Trim()
        if ($name -notmatch '^[A-Za-z][A-Za-z0-9_-]*$' -or $list -contains $name) { continue }
        $list += $name
      }
      $lines[$at + 1] = $list -join ','
    }
  }
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
# wrote its plan or report at or after its start is done. Without that file it is idle: the turn
# ended with no result and waits for a follow-up, unless its job still has background work in flight
# (ticket a-worker-that-waits-with-no-turn-does). Busy and shell still run.
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
    if (-not (Job-InFlight $Worker)) { return 'idle' }
  }
  $state
}

# True when ~\.claude\jobs\<id>\state.json of the worker says inFlight.tasks or inFlight.queued above 0:
# background work can still go on by itself. A missing or bad file counts as nothing in flight.
function Job-InFlight($Worker) {
  $jobId = "$($Worker.id)"
  if ($jobId -notmatch '^[A-Za-z0-9-]+$' -or -not $env:USERPROFILE) { return $false }
  $file = Join-Path $env:USERPROFILE ".claude\jobs\$jobId\state.json"
  if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return $false }
  try { $job = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json } catch { return $false }
  $flight = $job.inFlight
  if ($null -eq $flight) { return $false }
  try { ([int64]"0$($flight.tasks)" -gt 0) -or ([int64]"0$($flight.queued)" -gt 0) } catch { $false }
}

# The session names of a ticket: the plan run is "<ticket> [plan]", the work run "<ticket> [work]",
# so the agent view of Claude Code tells them apart. A worker of an older script has the bare id.
function Run-Name([string]$Run) { "$Id [$Run]" }

# The newest worker of a ticket, of either run. With a run: the newest worker named "<ticket> [<run>]"
# when one exists, else the newest of any name (a worker of an older script has the bare id). The
# plan run and the work run are two sessions (ticket work-fresh-session), and a follow-up of the work
# run must resume the work session, never the plan session.
# The list of claude agents has the sessions of every project, so only a worker of this project counts
# (Own-Worker): a ticket id of another project with the same name is never stopped or resumed here
# (ticket stop-on-a-task-stops-only-the-worker-of).
function Find-Worker($Workers, [string]$Ticket, [string]$Run) {
  $names = @($Ticket, "$Ticket [plan]", "$Ticket [work]")
  $mine = @($Workers | Where-Object { $names -contains $_.name -and (Own-Worker $_) } | Sort-Object { $_.startedAt })
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
  $worker = Find-Worker (Listed-Workers) $Id
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
  $worker = Find-Worker (Listed-Workers) $Id $Run
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
# $RunIn, when given, is the folder of the run in place of the project folder (the work folder); the
# variable DISPATCH_WORK_FOLDER then holds its number while claude starts (ticket
# up-to-4-work-folders-more-than-one-task).
function Run-Claude([string[]]$Arguments, [string]$StateOnFailure, [string]$BranchOnFailure, [string]$RunIn) {
  Push-Location -LiteralPath $(if ($RunIn) { $RunIn } else { $Project })
  if ($RunIn) { $env:DISPATCH_WORK_FOLDER = "$script:FolderNo" }
  $global:LASTEXITCODE = 0
  $printed = @(& claude @Arguments)
  $ok = $?
  $code = $LASTEXITCODE
  if ($RunIn) { Remove-Item Env:DISPATCH_WORK_FOLDER -ErrorAction SilentlyContinue }
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
  # The list after the start replaces the one before it (ticket the-worker-script-asks-git-each-fact):
  # Work-Job reads it; a failed list leaves none, and Work-Job lists again.
  $script:ListedOk = $false
  try {
    $text = (& claude agents --json --all) -join "`n"
    if ($LASTEXITCODE -eq 0) {
      $workers = @($text | ConvertFrom-Json | ForEach-Object { $_ })
      $script:Listed = $workers
      $script:ListedOk = $true
    }
  } catch { }
  $worker = @($workers | Where-Object { $_.id -and $words -contains "$($_.id)" }) | Select-Object -First 1
  if ($null -eq $worker) {
    $named = @($workers | Where-Object { $_.name -eq (Run-Name $Run) -and (Own-Worker $_) } | Sort-Object { $_.startedAt })
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

# The process $ProcessId runs and is the one that wrote a file at the time $Since. Windows gives the
# id of an ended process to a new process, so a process that started after $Since (1 s for a time
# without fractions) is another one. A time that does not parse leaves the id alone to decide; a
# start time that cannot be read (a process of another user) is another process.
function Owner-Runs([string]$ProcessId, [string]$Since) {
  $other = 0
  if (-not [int]::TryParse($ProcessId, [ref]$other)) { return $false }
  $process = Get-Process -Id $other -ErrorAction SilentlyContinue
  if ($null -eq $process) { return $false }
  $time = [DateTimeOffset]::MinValue
  if (-not [DateTimeOffset]::TryParse("$Since", [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeLocal, [ref]$time)) { return $true }
  $started = $null
  try { $started = $process.StartTime } catch { }
  if ($null -eq $started) { return $false }
  $started.ToUniversalTime() -le $time.UtcDateTime.AddSeconds(1)
}

# The ticket of a lock whose process still runs, else $null.
function Lock-Holder([string]$Lock) {
  if (-not (Test-Path -LiteralPath $Lock)) { return $null }
  $holder = "$(Get-Content -LiteralPath $Lock -Raw -ErrorAction SilentlyContinue)".Trim() -split "`t"
  if (Owner-Runs $holder[0] $(if ($holder.Count -gt 2) { $holder[2] } else { '' })) {
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
# Invoke-GitAt runs it in the folder $Dir (the work folder). A command in the project folder that can
# write to its index or its files ends the look at the uncommitted changes (Dirty-Lines).
function Invoke-GitAt([string]$Dir, [string[]]$A) {
  $ErrorActionPreference = 'Continue'
  $lines = @(& git -C $Dir @A 2>&1 | ForEach-Object { "$_" })
  $code = $LASTEXITCODE
  if ($Dir -eq $Project -and (Git-Writes $A)) { $script:Dirty = $null }
  [pscustomobject]@{ Code = $code; Lines = $lines; Text = ($lines -join "`n").Trim() }
}
function Invoke-Git([string[]]$A) { Invoke-GitAt $Project $A }

# Whether the git command $A can write to the index or the files of its folder (ticket
# the-worker-script-asks-git-about-20): its subcommand (the first word that is no option, after the
# value of -c or -C) is none of the reads and none of the commands that write objects or refs only;
# read-tree and rm write a separate index while GIT_INDEX_FILE names one.
$script:GitReads = @('status', 'diff', 'rev-parse', 'ls-files', 'ls-tree', 'cat-file', 'merge-base', 'merge-tree', 'config', 'log', 'show', 'for-each-ref', 'symbolic-ref', 'check-ignore', 'write-tree', 'commit-tree')
function Git-Writes([string[]]$A) {
  $sub = ''
  for ($i = 0; $i -lt $A.Count; $i++) {
    if ($A[$i] -eq '-c' -or $A[$i] -eq '-C') { $i++; continue }
    if ($A[$i].StartsWith('-')) { continue }
    $sub = $A[$i]
    break
  }
  if ($script:GitReads -contains $sub) { return $false }
  # update-ref moves a ref and no file (ticket the-worker-script-asks-git-each-fact): only HEAD, the
  # branch of the project folder, or a form it cannot tell (no full ref name) changes what status says.
  if ($sub -eq 'update-ref') {
    $ref = ''
    for ($j = $i + 1; $j -lt $A.Count; $j++) {
      if ($A[$j].StartsWith('-')) { continue }
      $ref = $A[$j]
      break
    }
    if (-not $ref.StartsWith('refs/')) { return $true }
    $on = Head-Branch $Project
    return (-not $on -or $ref -eq "refs/heads/$on")
  }
  if (($sub -eq 'read-tree' -or $sub -eq 'rm') -and $env:GIT_INDEX_FILE) { return $false }
  $true
}

# The facts that the files of git hold (ticket the-worker-script-asks-git-about-20): the branch, the
# hash of a ref, an open merge and the worktrees come from the files, as src-tauri/src/core/gitfiles.rs
# reads them for Dispatch, so a run starts git only for the index, the trees and the writes. A
# repository that keeps its refs in the reftable format (a folder reftable in the common folder, and
# HEAD names refs/heads/.invalid) asks git as before, and so does a reader whose file read fails.
$script:GitDirs = @{}
$script:RefsReadable = @{}
# The first line of a file, trimmed; '' when the file is missing.
function First-Line([string]$File) {
  if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { return '' }
  "$(([IO.File]::ReadAllText($File) -split "`r?`n")[0])".Trim()
}
# The git folder of the checkout $Dir: $Dir\.git when it is a folder, else the path after `gitdir:` in
# that file (relative to $Dir, forward slashes); '' when neither exists. A found folder is kept.
function Git-Dir([string]$Dir) {
  if (-not $Dir) { return '' }
  if ($script:GitDirs.ContainsKey($Dir)) { return $script:GitDirs[$Dir] }
  $dotGit = Join-Path $Dir '.git'
  $found = ''
  if (Test-Path -LiteralPath $dotGit -PathType Container) { $found = $dotGit }
  elseif (Test-Path -LiteralPath $dotGit -PathType Leaf) {
    $first = First-Line $dotGit
    if ($first.StartsWith('gitdir:')) {
      $target = $first.Substring(7).Trim()
      if ($target) { $found = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($target)) { $target } else { Join-Path $Dir $target })) }
    }
  }
  if ($found -and -not (Test-Path -LiteralPath (Join-Path $found 'HEAD') -PathType Leaf)) { $found = '' }
  if ($found) { $script:GitDirs[$Dir] = $found }
  $found
}
# The common folder of the checkout $Dir: the path in the file commondir of its git folder (relative to
# the git folder), else the git folder; '' without one.
function Git-Common([string]$Dir) {
  $gitDir = Git-Dir $Dir
  if (-not $gitDir) { return '' }
  $target = First-Line (Join-Path $gitDir 'commondir')
  if (-not $target) { return $gitDir }
  [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($target)) { $target } else { Join-Path $gitDir $target }))
}
# Whether the files of the repository of $Dir hold its refs: false without a git folder, with the
# reftable format, or when HEAD names refs/heads/.invalid.
function Refs-Readable([string]$Dir) {
  if ($script:RefsReadable.ContainsKey($Dir)) { return $script:RefsReadable[$Dir] }
  $gitDir = Git-Dir $Dir
  $readable = $false
  if ($gitDir) {
    $readable = -not (Test-Path -LiteralPath (Join-Path (Git-Common $Dir) 'reftable') -PathType Container)
    if ($readable -and (First-Line (Join-Path $gitDir 'HEAD')) -eq 'ref: refs/heads/.invalid') { $readable = $false }
  }
  $script:RefsReadable[$Dir] = $readable
  $readable
}
# The branch of the checkout $Dir, as `git symbolic-ref --short -q HEAD` gives it: the target of the
# `ref: ` line of its HEAD after refs/heads/ (after refs/ for another kind of ref); '' when HEAD is
# detached or the folder is no repository.
function Head-Branch([string]$Dir) {
  if (Refs-Readable $Dir) {
    try {
      $line = First-Line (Join-Path (Git-Dir $Dir) 'HEAD')
      if (-not $line.StartsWith('ref:')) { return '' }
      $target = $line.Substring(4).Trim()
      if ($target.StartsWith('refs/heads/')) { return $target.Substring(11) }
      if ($target.StartsWith('refs/')) { return $target.Substring(5) }
      return ''
    } catch { }
  }
  $result = Invoke-GitAt $Dir @('symbolic-ref', '--short', '-q', 'HEAD')
  if ($result.Code -ne 0) { return '' }
  $result.Text
}
# The hash of the ref $Name (a full name, refs/heads/<branch>, or HEAD) of the checkout $Dir, as
# `git rev-parse --verify --quiet <name>` gives it: the loose file (HEAD and the refs of the worktree in
# the git folder, the others in the common folder), a `ref: ` line followed at most 5 times, else the
# line `<hash> <name>` of packed-refs; '' when the ref does not exist. A hash has 40 or 64 hex digits.
function Ref-Hash([string]$Dir, [string]$Name) {
  if (Refs-Readable $Dir) {
    try {
      $gitDir = Git-Dir $Dir
      $common = Git-Common $Dir
      $target = $Name
      for ($step = 0; $step -lt 6; $step++) {
        if (-not $target -or $target.Contains('..')) { return '' }
        $own = $target -eq 'HEAD' -or $target.StartsWith('refs/bisect/') -or $target.StartsWith('refs/worktree/') -or $target.StartsWith('refs/rewritten/')
        $file = Join-Path $(if ($own) { $gitDir } else { $common }) ($target -replace '/', '\')
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return Packed-Ref $common $target }
        $line = First-Line $file
        if ($line.StartsWith('ref:')) { $target = $line.Substring(4).Trim(); continue }
        if ($line -match '^([0-9a-f]{40}|[0-9a-f]{64})$') { return $line }
        return ''
      }
      return ''
    } catch { }
  }
  $result = Invoke-GitAt $Dir @('rev-parse', '--verify', '--quiet', $Name)
  if ($result.Code -ne 0) { return '' }
  "$($result.Lines | Select-Object -Last 1)".Trim()
}
# The hash of the ref $Name in the file packed-refs of the common folder $Common: the line
# `<hash> <name>`; the header lines (#) and the peeled lines (^) do not count. '' when none.
function Packed-Ref([string]$Common, [string]$Name) {
  $file = Join-Path $Common 'packed-refs'
  if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return '' }
  foreach ($line in [IO.File]::ReadAllLines($file)) {
    if ($line.StartsWith('#') -or $line.StartsWith('^')) { continue }
    $at = $line.IndexOf(' ')
    if ($at -lt 0 -or $line.Substring($at + 1).TrimEnd() -ne $Name) { continue }
    $hash = $line.Substring(0, $at)
    if ($hash -match '^([0-9a-f]{40}|[0-9a-f]{64})$') { return $hash }
    return ''
  }
  ''
}
# The hash of HEAD of the checkout $Dir; '' for an unborn branch.
function Head-Hash([string]$Dir) { Ref-Hash $Dir 'HEAD' }
# Whether the checkout $Dir has an open merge, as `git rev-parse -q --verify MERGE_HEAD` tells it: the
# file MERGE_HEAD of its git folder exists.
function Merge-Open([string]$Dir) {
  $gitDir = Git-Dir $Dir
  if ($gitDir -and (Refs-Readable $Dir)) { return (Test-Path -LiteralPath (Join-Path $gitDir 'MERGE_HEAD') -PathType Leaf) }
  (Invoke-GitAt $Dir @('rev-parse', '-q', '--verify', 'MERGE_HEAD')).Code -eq 0
}
# The linked worktrees of the repository of $Dir, as `git worktree list --porcelain` lists them: one
# object per entry of <common>\worktrees\<n> with Folder (the parent of the path in its file gitdir),
# Branch (the target of its HEAD after refs/heads/, '' when detached) and Stale (the .git file that
# gitdir names is gone: `git worktree prune` would remove the entry). None without the folder.
function Worktree-Entries([string]$Dir) {
  $common = Git-Common $Dir
  if (-not $common) { return @() }
  $folder = Join-Path $common 'worktrees'
  if (-not (Test-Path -LiteralPath $folder -PathType Container)) { return @() }
  foreach ($entry in @(Get-ChildItem -LiteralPath $folder -Directory)) {
    $dotGit = First-Line (Join-Path $entry.FullName 'gitdir')
    $stale = -not $dotGit -or -not (Test-Path -LiteralPath $dotGit -PathType Leaf)
    $head = First-Line (Join-Path $entry.FullName 'HEAD')
    $branch = if ($head.StartsWith('ref: refs/heads/')) { $head.Substring(16) } else { '' }
    [pscustomobject]@{ Folder = $(if ($dotGit) { Split-Path -Parent $dotGit } else { '' }); Branch = $branch; Stale = $stale }
  }
}

# One look at the uncommitted changes of the project folder for the whole run (ticket
# the-worker-script-asks-git-about-20, user decision 1): the lines of one `git status --porcelain
# --untracked-files=all`, each file of an untracked folder on its own line. The lines serve every check
# until a git command that can write in the project folder runs (Invoke-GitAt), or Wait-MergeLock saw
# a switch of Dispatch; none when git fails. The measure verb keeps Tree-Status.
$script:Dirty = $null
function Dirty-Lines {
  if ($null -eq $script:Dirty) {
    $result = Invoke-Git @('status', '--porcelain', '--untracked-files=all')
    # An assignment from an if that gives no line would leave $null, so the array is made first.
    $found = @()
    if ($result.Code -eq 0) { $found = @($result.Lines | Where-Object { $_.Length -gt 3 -and $_[2] -eq ' ' }) }
    $script:Dirty = $found
  }
  $script:Dirty
}
# The path of a line of git status: after the two letters and the blank, the new path of a rename,
# without quotes, with forward slashes.
function Line-Path([string]$Line) {
  if ($Line.Length -le 3) { return '' }
  "$(($Line.Substring(3) -split ' -> ')[-1])".Trim().Trim('"').Replace('\', '/')
}
# Whether a path of git lies under .dispatch, the folder of the task files.
function Under-Tasks([string]$Path) { $Path.StartsWith('.dispatch/', [StringComparison]::OrdinalIgnoreCase) }

# The paths that a git command lists, unquoted, without the message lines of git ("warning: in the
# working copy of …" comes through stderr); none when git fails. A Windows path holds no colon.
function Git-Paths([string[]]$A) {
  $result = Invoke-Git (@('-c', 'core.quotePath=false') + $A)
  if ($result.Code -ne 0) { return @() }
  @($result.Lines | Where-Object { $_ -and $_ -notmatch '^(warning|error|fatal|hint): ' })
}

# The setting "Task branches" of the project in options.json; off without the file or the field.
function Branches-On {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return $false }
  try { return [bool]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).taskBranches } catch { return $false }
}

# The setting "Work folder" of the project (ticket work-run-in-the-work-folder): on with Task branches
# and the field workFolders in a git repository; off without the file or the field.
function Folders-On {
  (Branches-On) -and (Option-On 'workFolders') -and (Test-Path -LiteralPath (Join-Path $Project '.git'))
}
# The setting "Work folders" of the project (ticket up-to-4-work-folders-more-than-one-task): the
# number of work folders, 1 to 4; 1 without the file or the field.
function Folder-Count {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return 1 }
  $count = 1
  try { $count = [int]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).workFolderCount } catch { return 1 }
  [Math]::Max(1, [Math]::Min(4, $count))
}
# The work folders of the project, git worktrees, and their records: work\1 to work\4. $N is the
# number of a folder; without it, the folder of this run, which Enter-WorkFolder picks. Not $Folder:
# the verbs approve and check have a $folder of their own, and PowerShell names ignore case.
$script:FolderNo = 1
function Work-Folder([int]$N = $script:FolderNo) { Join-Path $Reports "work\$N" }
function Work-Record([int]$N = $script:FolderNo) { Join-Path $Reports "work\$N.record" }

# The record of a work folder (rule 5 of docs/work-folders.md): the lines ticket, use (work, check
# or merge), pid (of the script that wrote it), job (of the claude run, or -) and since. $null without it.
function Read-Record([int]$N = $script:FolderNo) {
  $file = Work-Record $N
  if (-not (Test-Path -LiteralPath $file)) { return $null }
  $record = @{ ticket = ''; use = ''; pid = ''; job = ''; since = '' }
  foreach ($line in Get-Content -LiteralPath $file) {
    if ($line -match '^\s*(ticket|use|pid|job|since):\s*(.*?)\s*$') { $record[$Matches[1]] = $Matches[2] }
  }
  [pscustomobject]$record
}
function Write-Record([string]$Use, [string]$Job, [int]$N = $script:FolderNo) {
  [IO.File]::WriteAllText((Work-Record $N), "ticket: $Id`nuse: $Use`npid: $PID`njob: $Job`nsince: $((Get-Date).ToString('o'))`n")
}

# The workers for the holders of the work folders: Folder-Holders lists them once per look, and only
# when a record needs them.
$script:Listed = $null
$script:ListedOk = $false
function Listed-Workers {
  if (-not $script:ListedOk) {
    $script:Listed = Get-Workers
    $script:ListedOk = $true
  }
  $script:Listed
}

# The ticket that holds a work folder: the script of the record still runs, or the use is work and
# the work run of that ticket still runs, waits at a prompt or is idle with no turn (a follow-up
# continues it in that folder). Else $null: a stale record is
# overwritten by the next user. No file time and no reflog decides; the time of the record only
# tells the script of the record from a later process with the same id (Owner-Runs).
function Record-Holder([int]$N = $script:FolderNo) {
  $record = Read-Record $N
  if ($null -eq $record -or -not $record.ticket) { return $null }
  if ($record.pid -ne "$PID" -and (Owner-Runs $record.pid $record.since)) { return $record.ticket }
  if ($record.use -eq 'work') {
    $state = Worker-State (Find-Worker (Listed-Workers) $record.ticket 'work')
    if ($state -eq 'working' -or $state -eq 'blocked' -or $state -eq 'idle') { return $record.ticket }
  }
  $null
}

# The number of the work folder of this project that the folder $Path is, or 0 (ticket
# up-to-4-work-folders-more-than-one-task): <project>\.dispatch\work\<n>, with / or \, also with the
# prefix \\?\, without case.
function Folder-Number([string]$Path) {
  $path = "$Path" -replace '/', '\'
  if ($path.StartsWith('\\?\')) { $path = $path.Substring(4) }
  $work = [IO.Path]::GetFullPath((Join-Path $Reports 'work')).TrimEnd('\')
  if ($path -match ('^' + [regex]::Escape($work) + '\\([1-4])\\?$')) { return [int]$Matches[1] }
  0
}

# True when the worker $Worker is a worker of this project (ticket stop-on-a-task-stops-only-the-worker-of):
# its cwd is the project folder or a work folder of it, compared as in Folder-Number. The list of claude
# agents has the sessions of every project. A worker without cwd (an older Claude Code) counts as own.
function Own-Worker($Worker) {
  $cwd = "$($Worker.cwd)"
  if (-not $cwd) { return $true }
  if ((Folder-Number $cwd) -gt 0) { return $true }
  $path = $cwd -replace '/', '\'
  if ($path.StartsWith('\\?\')) { $path = $path.Substring(4) }
  try { $path = [IO.Path]::GetFullPath($path).TrimEnd('\') } catch { return $false }
  $path -eq [IO.Path]::GetFullPath($Project).TrimEnd('\')
}

# The work folder where the session of the work run of the task runs: the number in the cwd of its
# newest worker "<ticket> [work]" ($Worker, or the listed one), or 0: a session in the project folder,
# a worker without cwd (an older Claude Code), or none.
function Session-Folder($Worker) {
  if ($null -eq $Worker) { $Worker = Find-Worker (Listed-Workers) $Id 'work' }
  if ($null -eq $Worker) { return 0 }
  Folder-Number "$($Worker.cwd)"
}

# The work folders of the project with their holders: each folder 1 to $Count, and a folder above it
# that is still on the disk or has a record (the user lowered the number while a task worked there).
# One object per folder: N, Holder (the ticket, or $null when the folder is free) and Made (the
# worktree exists).
function Folder-Holders([int]$Count) {
  for ($n = 1; $n -le 4; $n++) {
    $made = Test-Path -LiteralPath (Join-Path (Work-Folder $n) '.git')
    if ($n -gt $Count -and -not $made -and -not (Test-Path -LiteralPath (Work-Record $n))) { continue }
    [pscustomobject]@{ N = $n; Holder = (Record-Holder $n); Made = $made }
  }
}

# The free work folder for a run of the task without a session folder (approve, a merge, a session
# that started in the project folder), or 0 when none is free: the folder that has the branch of the
# task checked out (git checks out a branch in one folder only), else the folder whose record names
# this ticket, else the lowest free folder that exists, else the lowest number without a folder. A
# folder above $Count serves only the task of its branch or of its record.
function Pick-Folder($Holders, [int]$Count) {
  $free = @($Holders | Where-Object { -not $_.Holder })
  $info = Read-BranchFile $Id
  $branch = if ($info -and $info.branch) { $info.branch } else { "task/$Id" }
  foreach ($entry in @(Worktree-Entries $Project)) {
    if ($entry.Branch -ne $branch -or -not $entry.Folder) { continue }
    $n = Folder-Number $entry.Folder
    if ($n -gt 0 -and @($free | Where-Object { $_.N -eq $n }).Count -gt 0) { return $n }
  }
  foreach ($entry in $free) {
    $record = Read-Record $entry.N
    if ($null -ne $record -and $record.ticket -eq $Id) { return $entry.N }
  }
  foreach ($entry in $free) { if ($entry.Made -and $entry.N -le $Count) { return $entry.N } }
  foreach ($entry in $free) { if ($entry.N -le $Count) { return $entry.N } }
  0
}

# The paths of the plan of this task that the plan of $Ticket names too (ticket
# up-to-4-work-folders-more-than-one-task, user decision 2): the same path, or a path under a folder
# path of the other plan, without case; a path under .dispatch never counts.
function Same-Files([string]$Ticket) {
  $theirs = @(Plan-Files $Ticket | ForEach-Object { $_.ToLowerInvariant() } | Where-Object { -not $_.StartsWith('.dispatch/') })
  if ($theirs.Count -eq 0) { return @() }
  foreach ($path in @(Plan-Files $Id)) {
    $mine = $path.ToLowerInvariant()
    if ($mine.StartsWith('.dispatch/')) { continue }
    $hit = $false
    foreach ($other in $theirs) {
      if ($mine -eq $other -or ($other.EndsWith('/') -and $mine.StartsWith($other)) -or ($mine.EndsWith('/') -and $other.StartsWith($mine))) { $hit = $true }
    }
    if ($hit) { $path }
  }
}

# The job id of the newest worker named "<ticket> [work]", or - (for the record).
function Work-Job {
  $workers = @()
  if ($script:ListedOk) { $workers = @($script:Listed) }
  else {
    try {
      $text = (& claude agents --json --all) -join "`n"
      if ($LASTEXITCODE -eq 0) { $workers = @($text | ConvertFrom-Json | ForEach-Object { $_ }) }
    } catch { }
  }
  $worker = Find-Worker $workers $Id 'work'
  if ($null -eq $worker -or "$($worker.name)" -ne (Run-Name 'work') -or -not $worker.id) { return '-' }
  "$($worker.id)"
}

# Takes the work folder for the task (ticket work-run-in-the-work-folder) and gives the note for the
# prompt, or ''. $Use is work (approve, reply, resume) or check (check <ticket-id>). The folder is made
# once: a git worktree of the base, without .dispatch when the project tracks it, then the set-up
# command; a failed set-up removes the folder again. Then a change that a run left is committed on
# its task branch as WIP (or dropped on any other checkout), the task branch is checked out, and for
# work it takes the newer commits of the base. The settings of Claude Code that the work folder does
# not track come from the project folder. Last, the record names this task.
# $Use merge (ticket merge-in-the-work-folder) waits up to 60 s for a free folder (DISPATCH_FOLDER_WAIT
# seconds, for the tests), writes no branch file, and checks out the tip of the base without a branch.
# The folder (ticket up-to-4-work-folders-more-than-one-task): a work run with a session ($Worker:
# reply, resume) and a check run go into the folder where the session of the work run runs, also
# above the number of work folders, and wait while it is held (user decision 1); approve, a merge and
# a session without such a folder take Pick-Folder. A work run also waits while a task in another
# folder has a plan that names a file of this plan (user decision 2). $script:FolderNo is the folder then.
function Enter-WorkFolder([string]$Use, $Worker) {
  $count = Folder-Count
  $session = 0
  if ($Use -eq 'check' -or ($Use -eq 'work' -and $null -ne $Worker)) { $session = Session-Folder $Worker }
  $holders = @(Folder-Holders $count)
  if ($session -gt 0) {
    $number = $session
    $holder = Record-Holder $session
    if ($holder -and $count -eq 1 -and $session -eq 1) { Refuse "the work folder of this project is in use (task $holder); the run starts when it is free" }
    if ($holder) { Refuse "the work folder $session of this project, where the session of this task runs, is in use (task $holder); the run starts when it is free" }
  } else {
    $number = Pick-Folder $holders $count
    if ($number -eq 0 -and $Use -eq 'merge') {
      $wait = 60
      $seconds = 0
      if ([int]::TryParse("$env:DISPATCH_FOLDER_WAIT", [ref]$seconds) -and $seconds -ge 0) { $wait = $seconds }
      $deadline = (Get-Date).AddSeconds($wait)
      while ($number -eq 0 -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        # A new poll looks at the sessions again.
        $script:ListedOk = $false
        $holders = @(Folder-Holders $count)
        $number = Pick-Folder $holders $count
      }
    }
    if ($number -eq 0) {
      $what = if ($Use -eq 'merge') { 'merge' } else { 'run' }
      if ($count -eq 1) { Refuse "the work folder of this project is in use (task $(Record-Holder 1)); the $what starts when it is free" }
      $names = @($holders | Where-Object { $_.N -le $count -and $_.Holder } | ForEach-Object { $_.Holder } | Select-Object -Unique)
      Refuse "all $count work folders of this project are in use (tasks $($names -join ', ')); the $what starts when one is free"
    }
  }
  if ($Use -eq 'work') {
    foreach ($entry in $holders) {
      if (-not $entry.Holder -or $entry.Holder -eq $Id) { continue }
      $same = @(Same-Files $entry.Holder)
      if ($same.Count -gt 0) { Refuse "the task $($entry.Holder) works on the same files ($($same -join ', ')); this work run starts when it has ended" }
    }
  }
  $script:FolderNo = $number
  $folder = Work-Folder
  $info = if ($Use -eq 'merge') { Read-BranchFile $Id } else { Ensure-BranchFile }
  # A task of the old path (ticket moving-a-project): git checks out a branch in one folder only, and
  # Dispatch switches the project folder back to the base when the run of that task has ended.
  if ($Use -ne 'merge' -and (Current-Branch) -eq $info.branch) {
    Refuse "the project folder is on $($info.branch), the branch of this task, from before Work folder was on; Dispatch switches it back to $($info.base) when its run has ended and no file of the task has uncommitted changes (the task card names what holds it); then try again"
  }
  # A stale entry (its folder is gone) would refuse the worktree add of its path; a prune with nothing to prune is skipped.
  if (@(Worktree-Entries $Project | Where-Object { $_.Stale }).Count -gt 0) { Invoke-Git @('worktree', 'prune') | Out-Null }
  if (-not (Test-Path -LiteralPath (Join-Path $folder '.git'))) {
    $parent = Split-Path -Parent $folder
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    if (Test-Path -LiteralPath $folder) { Remove-Item -LiteralPath $folder -Recurse -Force }
    # --detach: the base is checked out in the project folder, and git checks out a branch in one folder only.
    $made = Invoke-Git @('worktree', 'add', '--detach', $folder, $info.base)
    if ($made.Code -ne 0) { Refuse "git could not make the work folder $folder (exit code $($made.Code)): $($made.Text)" }
    $failed = ''
    if (Tracks-Tasks (Tree-Tasks 'HEAD')) {
      $sparse = Invoke-GitAt $folder @('sparse-checkout', 'set', '--no-cone', '/*', '!/.dispatch/')
      if ($sparse.Code -ne 0) { $failed = "git could not leave .dispatch out of the work folder: $($sparse.Text)" }
    }
    $setup = "$(@(Command-Lines 'work-setup.txt') | Select-Object -First 1)"
    if (-not $failed -and $setup) {
      $log = Join-Path $Reports "work\$number-setup.log"
      $code = Run-Measured $setup $log (Command-Limit 'work-setup.txt' 3600) $folder
      if ($code -ne 0) { $failed = "the set-up command of the work folder failed with exit code $code ($setup; the log is $log)" }
    }
    if ($failed) {
      Invoke-Git @('worktree', 'remove', '--force', $folder) | Out-Null
      if (Test-Path -LiteralPath $folder) { Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue }
      Invoke-Git @('worktree', 'prune') | Out-Null
      Refuse "${failed}; Dispatch removed the work folder, and the next start makes it again"
    }
  }
  # Only Dispatch and its workers write in the work folder, so it is cleaned without a question.
  if (Merge-Open $folder) { Invoke-GitAt $folder @('merge', '--abort') | Out-Null }
  $left = @((Invoke-GitAt $folder @('status', '--porcelain')).Lines | Where-Object { $_ })
  if ($left.Count -gt 0) {
    $on = Head-Branch $folder
    if ($on.StartsWith('task/')) {
      Invoke-GitAt $folder @('add', '-A', '--', '.', ':(exclude).claude/settings.json', ':(exclude).claude/settings.local.json') | Out-Null
      $staged = (Invoke-GitAt $folder @('diff', '--cached', '--quiet')).Code -ne 0
      if ($staged) {
        $wip = Invoke-GitAt $folder @('commit', '-q', '-m', "$($on.Substring(5)) WIP (Dispatch: files of the work run)")
        if ($wip.Code -ne 0) { Refuse "git could not commit the files that a run left in the work folder on ${on}: $($wip.Text)" }
      }
    }
    Invoke-GitAt $folder @('reset', '-q', '--hard') | Out-Null
    Invoke-GitAt $folder @('clean', '-fdq') | Out-Null
  }
  $note = ''
  if ($Use -eq 'merge') {
    # Step 1 of the section Merge of docs/work-folders.md: the tip of the base, detached.
    $baseTip = Ref-Hash $Project "refs/heads/$($info.base)"
    $out = Invoke-GitAt $folder @('checkout', '-q', '-f', '--detach', $baseTip)
    if ($out.Code -ne 0) { Refuse "git could not check out $($info.base) in the work folder: $($out.Text)" }
  } elseif (Ref-Hash $Project "refs/heads/$($info.branch)") {
    # A branch that holds its base, with the folder on it already, stays as it is (ticket
    # the-worker-script-asks-git-each-fact): the detach, the sync and the checkout would change nothing.
    $sync = $Use -eq 'work' -and (Sync-Needed)
    if ($sync) {
      # The branch takes the newer commits of the base before its checkout (ticket moving-a-project):
      # Sync-Base takes .dispatch of the base (rule 4), so task files that a branch of the old path
      # committed make no conflict. It moves the ref only, so the work folder lets go of the branch first.
      $detach = Invoke-GitAt $folder @('checkout', '-q', '--detach')
      if ($detach.Code -ne 0) { Refuse "git could not detach the work folder: $($detach.Text)" }
      $why = "$(Sync-Base -Before)"
      if ($why) {
        $why = (($why -replace '"', "'") -replace "`r?`n", ' ').Trim().TrimEnd('.')
        $note = " Dispatch could not bring the newer commits of $($info.base) into this branch: $why."
      }
    }
    if ($sync -or (Head-Branch $folder) -ne $info.branch) {
      $out = Invoke-GitAt $folder @('checkout', '-q', $info.branch)
      if ($out.Code -ne 0) { Refuse "git could not check out $($info.branch) in the work folder: $($out.Text)" }
    }
  } else {
    $out = Invoke-GitAt $folder @('checkout', '-q', '-b', $info.branch, $info.base)
    if ($out.Code -ne 0) { Refuse "git could not make $($info.branch) in the work folder: $($out.Text)" }
  }
  # A session reads .claude of its start folder (open point 2 of docs/work-folders.md). One ls-files
  # tells for both names whether the work folder tracks them.
  $names = @(@('settings.json', 'settings.local.json') | Where-Object { Test-Path -LiteralPath (Join-Path $Project ".claude\$_") })
  if ($names.Count -gt 0) {
    $tracked = @((Invoke-GitAt $folder (@('ls-files', '--') + @($names | ForEach-Object { ".claude/$_" }))).Lines)
    foreach ($name in $names) {
      if ($tracked -contains ".claude/$name") { continue }
      $target = Join-Path $folder '.claude'
      if (-not (Test-Path -LiteralPath $target)) { New-Item -ItemType Directory -Path $target -Force | Out-Null }
      Copy-Item -LiteralPath (Join-Path $Project ".claude\$name") -Destination (Join-Path $target $name) -Force
    }
  }
  Write-Record $Use '-'
  $note
}

# The setting "Task files local only" of the project in options.json; off without the file or the field.
function Tasks-Local {
  $file = Join-Path $Reports 'options.json'
  if (-not (Test-Path -LiteralPath $file)) { return $false }
  try { return [bool]((Get-Content -LiteralPath $file -Raw) | ConvertFrom-Json).ignoreTasks } catch { return $false }
}

# The branch that the main checkout is on; empty when HEAD is detached or the folder is no repository.
function Current-Branch { Head-Branch $Project }

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

# Waits up to 60 s while another process holds the merge lock, then refuses with the task of the
# holder. Without a lock it goes on at once.
function Wait-MergeLock {
  $holder = Lock-Holder (Merge-Lock)
  # The holder switches branches and writes task files: the look at the uncommitted changes is taken again.
  if ($holder) { $script:Dirty = $null }
  $deadline = (Get-Date).AddSeconds(60)
  while ($holder -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $holder = Lock-Holder (Merge-Lock)
  }
  if ($holder) { Refuse "a merge of this project runs (task $holder); try again when it has ended" }
}

# Puts the main checkout on the branch of the task, made from its base when it does not exist yet.
# Gives the branch that was checked out before, or '' when the checkout was on the task branch.
function Enter-TaskBranch {
  $info = Ensure-BranchFile
  $current = Current-Branch
  if ($current -eq $info.branch) { return '' }
  if ($current.StartsWith('task/')) { Refuse "the checkout is on $current, the branch of another task; Dispatch switches back to the base when that work run has ended" }
  # Dispatch holds the merge lock while it switches back to the base and copies the task files
  # (ticket merge-issue), for seconds: the switch waits for it, then reads the branch again.
  Wait-MergeLock
  # The steps below change the index (ticket title-auto-merge-left-the-main-checkout): an open merge
  # would take them in.
  if (Merge-Open $Project) { Refuse 'the checkout has an unfinished merge; finish it or run git merge --abort first' }
  $current = Current-Branch
  if ($current -eq $info.branch) { return '' }
  if ($current.StartsWith('task/')) { Refuse "the checkout is on $current, the branch of another task; Dispatch switches back to the base when that work run has ended" }
  $tip = Ref-Hash $Project "refs/heads/$($info.branch)"
  $exists = [bool]$tip
  $kept = @()
  $synced = ''
  $headTasks = $null
  $tipTasks = $null
  if ($exists) {
    # The task files of HEAD and of the branch, read once for Align-Tasks and Set-CardFilesAside; the
    # branch is read again when the alignment or the sync moved it.
    $headTasks = Tree-Tasks 'HEAD'
    $tipTasks = Tree-Tasks $tip
    $aligned = "$(Align-Tasks $info.branch (Tracks-Tasks $tipTasks) (Tracks-Tasks $headTasks))"
    if ($aligned) { Refuse "the checkout stays on ${current}: $aligned" }
    # The branch takes the newer commits of the base before the checkout (ticket merge-issue), so it
    # holds the card files as the base does; a clash leaves it as it was, and Sync-Note tells the
    # worker after the checkout. The card files that still differ go along by hand.
    $synced = "$(Sync-Base -Before)"
    $moved = Ref-Hash $Project "refs/heads/$($info.branch)"
    if ($moved -ne $tip) { $tipTasks = Tree-Tasks $moved }
  }
  # An uncommitted file outside .dispatch that the target holds in another version holds the switch:
  # git would refuse it (ticket bug-report-from-the-project-rockhoppers). The list comes before
  # Set-CardFilesAside, so the refusal leaves the disk as it was.
  $target = if ($exists) { $info.branch } else { $info.base }
  $blocking = @(Switch-Blockers $target)
  $why = if ($synced) { " (the newer commits of $($info.base) did not come into $($info.branch): $(($synced -replace "`r?`n", ' ').Trim().TrimEnd('.')))" } else { '' }
  if ($blocking.Count -gt 0) { Refuse "the checkout stays on ${current}: $($info.branch) has another version of these uncommitted files, and git would overwrite them: $($blocking -join ', '); commit or stash them on $current, then try again$why" }
  if ($exists -and -not (Tasks-Local)) { $kept = Set-CardFilesAside $info.branch $headTasks $tipTasks }
  $result = if ($exists) { Invoke-Git @('checkout', '-q', $info.branch) } else { Invoke-Git @('checkout', '-q', '-b', $info.branch, $info.base) }
  Put-CardFiles $kept
  if ($result.Code -ne 0) { Refuse "git could not check out $($info.branch): $($result.Text)$why" }
  $current
}

# The uncommitted files outside .dispatch (changed tracked files and untracked files) that HEAD and
# $Target hold in different versions: a checkout of $Target would overwrite them, so git refuses it.
function Switch-Blockers([string]$Target) {
  # From the one look at the uncommitted changes: the changed tracked files first, then the untracked files (?? lines), as git lists them.
  $lines = @(Dirty-Lines | Where-Object { -not (Under-Tasks (Line-Path $_)) })
  $dirty = @($lines | Where-Object { -not $_.StartsWith('??') } | ForEach-Object { Line-Path $_ })
  $dirty += @($lines | Where-Object { $_.StartsWith('??') } | ForEach-Object { Line-Path $_ })
  if ($dirty.Count -eq 0) { return @() }
  $other = @(Git-Paths @('diff', '--name-only', '--no-renames', 'HEAD', $Target, '--', '.', ':(exclude).dispatch'))
  @($dirty | Select-Object -Unique | Where-Object { $other -contains $_ })
}

# The card files that a checkout of $Target would delete or change (ticket merge-issue): the changed
# files of .dispatch and the files of .dispatch that differ between HEAD and $Target. Keeps their
# bytes and times and gives each file the copy of HEAD (a file that HEAD lacks goes from the disk), so
# git meets no changed card file; Put-CardFiles writes them again after the checkout. Dispatch commits
# no card file on a task branch, so they stay changes there until the switch back.
# $HeadTasks and $TargetTasks are the task files of HEAD and of $Target (Tree-Tasks): a path whose hash
# differs, or that one of them lacks, differs between the two. A card is tracked when HEAD has it. The
# git calls go in two batches, and only for the cards with an uncommitted change: a card without one
# equals HEAD in the index and on the disk already, so git had nothing to do for it.
function Set-CardFilesAside([string]$Target, $HeadTasks, $TargetTasks) {
  if ($null -eq $HeadTasks) { $HeadTasks = @{} }
  if ($null -eq $TargetTasks) { $TargetTasks = @{} }
  $changed = @(Dirty-Lines | Where-Object { Under-Tasks (Line-Path $_) } | ForEach-Object { $_.Substring(3) -split ' -> ' } | ForEach-Object { "$_".Trim().Trim('"') } | Where-Object { $_ })
  $paths = @($changed)
  foreach ($path in @(@($HeadTasks.Keys) + @($TargetTasks.Keys) | Sort-Object -Unique)) {
    if ($HeadTasks[$path] -ne $TargetTasks[$path]) { $paths += $path }
  }
  $kept = @()
  foreach ($path in @($paths | Where-Object { $_ } | Select-Object -Unique)) {
    $file = Join-Path $Project $path
    if (Test-Path -LiteralPath $file -PathType Leaf) {
      $kept += [pscustomobject]@{ Path = $path; Bytes = [IO.File]::ReadAllBytes($file); Time = [IO.File]::GetLastWriteTimeUtc($file) }
    } else {
      $kept += [pscustomobject]@{ Path = $path; Bytes = $null; Time = $null }
    }
  }
  $reset = @()
  $drop = @()
  foreach ($card in $kept) {
    if ($HeadTasks.ContainsKey($card.Path)) {
      if ($changed -contains $card.Path) { $reset += $card.Path }
    } else {
      if ($changed -contains $card.Path) { $drop += $card.Path }
      Remove-Item -LiteralPath (Join-Path $Project $card.Path) -Force -ErrorAction SilentlyContinue
    }
  }
  if ($reset.Count -gt 0) { Invoke-Git (@('checkout', '-q', 'HEAD', '--') + $reset) | Out-Null }
  if ($drop.Count -gt 0) { Invoke-Git (@('rm', '-q', '--cached', '--ignore-unmatch', '--') + $drop) | Out-Null }
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
  $open = Merge-Open $Project
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
  # The tracked files outside .dispatch that differ from the index before the checks and the gate
  # (ticket bug-report-from-the-project-rockhoppers): the merged files match the index, so these are
  # the changes of the user. A file that differs only after the gate is a file that the gate wrote.
  $dirtyBefore = @(Git-Paths @('diff', '--name-only', '--', '.', ':(exclude).dispatch'))
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
  # The tracked files that the checks and the gate changed go into a commit of their own on the base,
  # so no line of the gate (a log) stays uncommitted and holds the next switch to a task branch.
  # Untracked files stay: they can be build output. A failure does not undo the merge.
  $written = @(Git-Paths @('diff', '--name-only', 'HEAD', '--', '.', ':(exclude).dispatch') | Where-Object { $dirtyBefore -notcontains $_ })
  if ($written.Count -gt 0) {
    $own = Invoke-Git (@('commit', '-q', '-m', "${Id}: files that the merge gate wrote", '--') + $written)
    if ($own.Code -ne 0) { [Console]::Error.WriteLine("warning: git could not commit the files that the merge gate wrote ($($written -join ', ')): $($own.Text)") }
  }
  Invoke-Git @('branch', '-d', $branch) | Out-Null
  Write-BranchFile $branch $Info.base "done $script:Merged $((Get-Date).ToString('s'))"
  ''
}

# The picked checks and the gate command in the work folder on an open merge of the base tip and the
# branch tip with the files of the merge commit $Merge, as with Work folders off (ticket
# merge-in-the-work-folder, steps 3 and 4 of the section Merge of docs/work-folders.md), with the texts
# of Merge-Branch; a failure cancels the open merge. After they pass, HEAD moves to $Merge; the tree
# there was clean, so each tracked file that differs from HEAD then is a file that they wrote, and it
# goes into a commit on top. Gives the reason of a refusal, or ''; then
# $script:GateTop is the commit on top, or '' when they wrote no tracked file.
function Gate-InWorkFolder([string]$Merge, [string]$Gate, [int]$Limit) {
  $work = Work-Folder
  $script:GateTop = ''
  $out = Invoke-GitAt $work @('checkout', '-q', '-f', '--detach', $Merge)
  if ($out.Code -ne 0) { return "git could not check out the merge in the work folder: $($out.Text)" }
  # The open merge of git merge --no-ff --no-commit (ticket the-gate-in-the-work-folder-sees-an-open):
  # HEAD on the first parent (the base tip), MERGE_HEAD the second (the branch tip); the index and the
  # work tree keep the merged files of $Merge.
  $parents = @((Invoke-GitAt $work @('rev-parse', "$Merge^1", "$Merge^2")).Lines | Where-Object { $_ -match '^[0-9a-f]{40,64}$' })
  if ($parents.Count -ne 2) { return "git could not read the two parents of the merge $Merge in the work folder" }
  $gitDir = Git-Dir $work
  if (-not $gitDir) { return "Dispatch could not find the git folder of the work folder $work" }
  $out = Invoke-GitAt $work @('update-ref', '--no-deref', 'HEAD', $parents[0])
  if ($out.Code -ne 0) { return "git could not open the merge in the work folder: $($out.Text)" }
  $subject = (Invoke-GitAt $work @('log', '-1', '--format=%s', $Merge)).Text
  [IO.File]::WriteAllText((Join-Path $gitDir 'MERGE_HEAD'), "$($parents[1])`n")
  [IO.File]::WriteAllText((Join-Path $gitDir 'MERGE_MODE'), 'no-ff')
  [IO.File]::WriteAllText((Join-Path $gitDir 'MERGE_MSG'), "$subject`n")
  if ((Option-On 'pickChecks') -and -not ($Gate -and (Option-On 'checksAtGate'))) {
    $dir = Join-Path $Reports 'merge'
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $failed = Run-Checks 'merge' { param($n) Join-Path $Reports "merge\$Id-$n.log" } (Command-Limit 'checks.txt' 14400) -StopAtFailure -RunIn $work
    if ($null -ne $failed) {
      Invoke-GitAt $work @('merge', '--abort') | Out-Null
      $tail = if (Test-Path -LiteralPath $failed.Log) { (Get-Content -LiteralPath $failed.Log -Tail 40) -join "`n" } else { '' }
      return "the check $($failed.Name) failed with exit code $($failed.Code), so nothing was merged ($($failed.Command); the log is $($failed.Log)). Its last 40 lines:`n$tail"
    }
  }
  if ($Gate) {
    $log = Join-Path $Reports "merge\$Id.log"
    $env:DISPATCH_TICKET = $Id
    $env:DISPATCH_CHECKS = Join-Path $Reports "$Id.checks"
    $code = Run-Measured $Gate $log $Limit $work
    Remove-Item Env:DISPATCH_TICKET, Env:DISPATCH_CHECKS -ErrorAction SilentlyContinue
    if ($code -ne 0) {
      Invoke-GitAt $work @('merge', '--abort') | Out-Null
      $tail = if (Test-Path -LiteralPath $log) { (Get-Content -LiteralPath $log -Tail 40) -join "`n" } else { '' }
      return "the gate command failed with exit code $code, so nothing was merged ($Gate; the log is $log). Its last 40 lines:`n$tail"
    }
  }
  # A check or a gate that committed or cancelled the open merge stops it (user decision 1): HEAD and
  # MERGE_HEAD are still the two parents of $Merge, or nothing is merged.
  $still = @((Invoke-GitAt $work @('rev-parse', 'HEAD', 'MERGE_HEAD')).Lines | Where-Object { $_ -match '^[0-9a-f]{40,64}$' })
  if ($still.Count -ne 2 -or $still[0] -ne $parents[0] -or $still[1] -ne $parents[1]) {
    if (Test-Path -LiteralPath (Join-Path $gitDir 'MERGE_HEAD') -PathType Leaf) { Invoke-GitAt $work @('merge', '--abort') | Out-Null }
    return "the checks or the gate command committed or cancelled the open merge in the work folder, so nothing was merged; a gate must leave the merge open"
  }
  # The merge closes on $Merge: the diff below then lists only the files that the checks and the gate wrote.
  foreach ($name in @('MERGE_HEAD', 'MERGE_MODE', 'MERGE_MSG')) { Remove-Item -LiteralPath (Join-Path $gitDir $name) -Force -ErrorAction SilentlyContinue }
  $out = Invoke-GitAt $work @('update-ref', '--no-deref', 'HEAD', $Merge)
  if ($out.Code -ne 0) { return "git could not set the work folder on the merge commit: $($out.Text)" }
  $changed = Invoke-GitAt $work @('-c', 'core.quotePath=false', 'diff', '--name-only', 'HEAD', '--', '.', ':(exclude).dispatch')
  $written = @($changed.Lines | Where-Object { $_ -and $_ -notmatch '^(warning|error|fatal|hint): ' })
  if ($changed.Code -eq 0 -and $written.Count -gt 0) {
    $own = Invoke-GitAt $work (@('commit', '-q', '-m', "${Id}: files that the merge gate wrote", '--') + $written)
    if ($own.Code -eq 0) { $script:GateTop = Head-Hash $work }
    else { [Console]::Error.WriteLine("warning: git could not commit the files that the merge gate wrote ($($written -join ', ')): $($own.Text)") }
  }
  ''
}

# The commit that the base can move to with no new gate run (ticket merge-in-the-work-folder, user
# decision 1): the file merge\<ticket>.gated names the merge on which the checks and the gate passed,
# the commit on top of it (or -) and the tip of the task branch. With the same branch tip and the same
# files outside .dispatch as $Merge, the gated commit is taken as it is when it has the same first
# parent, else its commit on top is picked onto $Merge in the work folder. '' when no gate passed on
# this code.
function Gated-Target([string]$Merge, [string]$BranchTip, [string]$MergeParent) {
  $file = Join-Path $Reports "merge\$Id.gated"
  if (-not (Test-Path -LiteralPath $file)) { return '' }
  $fields = @("$(Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue)".Trim() -split "`t")
  if ($fields.Count -lt 3 -or $fields[2] -ne $BranchTip) { return '' }
  $old = $fields[0]
  $top = if ($fields[1] -eq '-') { '' } else { $fields[1] }
  # One cat-file --batch-check through stdin (ticket the-worker-script-asks-git-each-fact): the gated
  # commit, its commit on top, and the first parent of the gated commit; a line "<name> missing" means
  # the object is gone. $MergeParent is the base tip that Build-Merge took as the first parent of $Merge.
  $asked = @("${old}^{commit}")
  if ($top) { $asked += "${top}^{commit}" }
  $asked += "${old}^1"
  $ErrorActionPreference = 'Continue'
  $found = @($asked | & git -C $Project cat-file --batch-check 2>&1 | ForEach-Object { "$_" })
  $ErrorActionPreference = 'Stop'
  if ($found.Count -ne $asked.Count) { return '' }
  for ($i = 0; $i -lt $asked.Count - 1; $i++) {
    if ($found[$i] -notmatch '^[0-9a-f]+ commit \d+$') { return '' }
  }
  $oldParent = if ($found[$found.Count - 1] -match '^([0-9a-f]+) commit \d+$') { $Matches[1] } else { '' }
  if ((Invoke-Git @('diff', '--quiet', $old, $Merge, '--', '.', ':(exclude).dispatch')).Code -ne 0) { return '' }
  if ($oldParent -eq $MergeParent) {
    if ($top) { return $top }
    return $old
  }
  if (-not $top) { return $Merge }
  $work = Work-Folder
  if ((Invoke-GitAt $work @('checkout', '-q', '-f', '--detach', $Merge)).Code -ne 0) { return '' }
  $pick = Invoke-GitAt $work @('cherry-pick', $top)
  if ($pick.Code -ne 0) {
    Invoke-GitAt $work @('cherry-pick', '--abort') | Out-Null
    return ''
  }
  Head-Hash $work
}

# The merge with Work folder on (ticket merge-in-the-work-folder, steps 1 to 7 of the section Merge of
# docs/work-folders.md): the work folder on the tip of the base, the merge as a finished commit
# (Build-Merge), the checks and the gate there on an open merge of the base tip and the branch tip with
# its files (Gate-InWorkFolder), unless a gate passed on the same code (Gated-Target), and then the base moves in one step: git merge --ff-only in the project
# folder when it is on the base, else git update-ref with the old tip as the condition. A base that
# moved gets a new merge, up to 3 times. An uncommitted file of the project folder that the move would
# overwrite stops it, and the gated merge stays for the next Merge. The project folder never has an
# open merge. Gives the reason of a refusal, or '' after the merge; $script:Merged holds the commit then.
function Merge-InWorkFolder($Info, [string]$Gate, [int]$Limit) {
  $branch = $Info.branch
  $base = $Info.base
  Enter-WorkFolder 'merge' | Out-Null
  $gated = Join-Path $Reports "merge\$Id.gated"
  $branchTip = Ref-Hash $Project "refs/heads/$branch"
  $built = $null
  $from = ''
  $target = ''
  $moves = 0
  while ($true) {
    $now = Ref-Hash $Project "refs/heads/$base"
    if ($now -ne $from) {
      if ($from) {
        $moves++
        if ($moves -ge 3) { return "the base $base moved three times during the merge; click Merge again" }
      }
      $built = Build-Merge $now $Info
      if ($built.Why) { return "git could not merge ${branch}: $($built.Why)" }
      if ($built.Conflicts.Count -gt 0) { return "the merge of $branch into $base does not apply; nothing was merged. Conflicts in:`n$($built.Conflicts -join "`n")" }
      if (-not $built.Commit) {
        # Nothing to merge: the base holds the branch.
        Invoke-Git @('branch', '-D', $branch) | Out-Null
        $script:Merged = 'nothing to merge'
        Write-BranchFile $branch $base "done nothing-to-merge $((Get-Date).ToString('s'))"
        Remove-Item -LiteralPath $gated -Force -ErrorAction SilentlyContinue
        return ''
      }
      $from = $now
      $target = Gated-Target $built.Commit $branchTip $now
      if (-not $target) {
        $why = Gate-InWorkFolder $built.Commit $Gate $Limit
        if ($why) { return $why }
        $target = if ($script:GateTop) { $script:GateTop } else { $built.Commit }
        $top = if ($script:GateTop) { $script:GateTop } else { '-' }
        [IO.File]::WriteAllText($gated, "$($built.Commit)`t$top`t$branchTip`t$((Get-Date).ToString('o'))`n")
        # The gate can run for hours: the base is read again before the move.
        continue
      }
    }
    if ((Current-Branch) -eq $base) {
      $blocking = @(Switch-Blockers $target)
      if ($blocking.Count -gt 0) { return "these uncommitted files of the project folder would be overwritten by the merge; commit, stash or undo them, then click Merge again: $($blocking -join ', ')" }
      $move = Invoke-Git @('merge', '--ff-only', '-q', $target)
    } else {
      $move = Invoke-Git @('update-ref', "refs/heads/$base", $target, $now)
    }
    if ($move.Code -eq 0) { break }
    $after = Ref-Hash $Project "refs/heads/$base"
    if ($after -eq $now) { return "git could not move ${base}: $($move.Text)" }
  }
  $script:Merged = (Invoke-Git @('rev-parse', '--short', $target)).Text
  Invoke-Git @('branch', '-D', $branch) | Out-Null
  Write-BranchFile $branch $base "done $script:Merged $((Get-Date).ToString('s'))"
  Remove-Item -LiteralPath $gated -Force -ErrorAction SilentlyContinue
  ''
}

# Pushes the base after its merge (Push after the merge, ticket simpler-process): only the base to its
# upstream branch (branch.<base>.remote and branch.<base>.merge), never with force, without a prompt
# for a password, with the time limit of 300 s. Gives the value of the push line of the branch file.
function Push-Base([string]$Base) {
  # The two keys in one call (ticket the-worker-script-asks-git-about-20): each line `<key> <value>`; as
  # with --get, the last line of a key counts. A branch name can hold a character of a regular expression.
  $key = $Base -replace '([.^$*+?()\[\]{}|\\])', '\$1'
  $remote = ''
  $target = ''
  foreach ($line in @((Invoke-Git @('config', '--get-regexp', "^branch\.$key\.(remote|merge)$")).Lines)) {
    $at = "$line".IndexOf(' ')
    if ($at -lt 0) { continue }
    $value = $line.Substring($at + 1).Trim()
    if ($line.Substring(0, $at) -eq "branch.$Base.remote") { $remote = $value }
    elseif ($line.Substring(0, $at) -eq "branch.$Base.merge") { $target = $value }
  }
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
# / or a dot and no space; a trailing ,:;.) goes. The Rust rule plan_files is the same. $Ticket, when
# given, reads the plan of that task (the rule of the same files of Enter-WorkFolder).
function Plan-Files([string]$Ticket = $Id) {
  $file = Join-Path $Reports "plans\$Ticket.md"
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
  # The one look at the uncommitted changes (user decision 1 of ticket the-worker-script-asks-git-about-20)
  # lists each file of an untracked folder, where the look of the task card lists the folder.
  $dirty = @(Dirty-Lines | Where-Object { -not (Under-Tasks (Line-Path $_)) })
  if ($dirty.Count -eq 0) { return @() }
  $files = @()
  if ($Plan) { $files += @(Plan-Files) }
  $info = Read-BranchFile $Id
  if ($info -and $info.branch -and $info.base -and -not $info.merge -and (Ref-Hash $Project "refs/heads/$($info.branch)")) {
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
# It reads the base commit once (ticket bringing-main-into-a-task-branch-can); a commit that lands during the step comes in at the next sync.
# The two calls of one Approve or reply, before and after the checkout, see the same base and the same
# branch tip, so the second gives the answer of the first (ticket the-worker-script-asks-git-about-20:
# $script:Synced keeps the pair and the answer; after a sync the pair holds the new tip). A branch at
# the hash of its base holds the base, with no question to git.
$script:Synced = @{ Base = ''; Tip = ''; Why = '' }
function Sync-Base([switch]$Before) {
  $info = Read-BranchFile $Id
  if ($null -eq $info -or -not $info.branch -or -not $info.base -or $info.merge) { return '' }
  $on = (Current-Branch) -eq $info.branch
  if ($on -eq [bool]$Before) { return '' }
  $baseId = Ref-Hash $Project "refs/heads/$($info.base)"
  if (-not $baseId) { return "the branch $($info.base) does not exist" }
  $tip = Ref-Hash $Project "refs/heads/$($info.branch)"
  if ($script:Synced.Base -eq $baseId -and $script:Synced.Tip -eq $tip) { return $script:Synced.Why }
  $script:SyncMade = $tip
  $why = if ($baseId -eq $tip) { '' } else { "$(Sync-Commit $info $baseId $tip $on)" }
  $script:Synced = @{ Base = $baseId; Tip = $(if ($why) { $tip } else { $script:SyncMade }); Why = $why }
  $why
}
# Whether the task branch lacks a commit of its base, with no write (ticket
# the-worker-script-asks-git-each-fact): a branch at the hash of its base, or a pair that $script:Synced
# holds, needs no question to git, else one merge-base. An ancestor fills $script:Synced, so Sync-Base
# returns at once; a commit that is no ancestor goes into $script:SyncAsked, so Sync-Commit does not ask
# again for the same pair. Both are keyed on the two hashes, and the answer for two commits never
# changes. A missing branch file, base or branch counts as needed: Sync-Base then gives its reason.
$script:SyncAsked = @{ Base = ''; Tip = '' }
function Sync-Needed {
  $info = Read-BranchFile $Id
  if ($null -eq $info -or -not $info.branch -or -not $info.base -or $info.merge) { return $true }
  $baseId = Ref-Hash $Project "refs/heads/$($info.base)"
  $tip = Ref-Hash $Project "refs/heads/$($info.branch)"
  if (-not $baseId -or -not $tip) { return $true }
  if ($baseId -eq $tip) { return $false }
  if ($script:Synced.Base -eq $baseId -and $script:Synced.Tip -eq $tip) { return [bool]$script:Synced.Why }
  if ((Invoke-Git @('merge-base', '--is-ancestor', $baseId, $info.branch)).Code -eq 0) {
    $script:Synced = @{ Base = $baseId; Tip = $tip; Why = '' }
    return $false
  }
  $script:SyncAsked = @{ Base = $baseId; Tip = $tip }
  $true
}
# The sync itself: the merge commit of the branch tip $Tip and the base commit $BaseId, and the move of
# the branch; $script:SyncMade is the new tip after a success. Gives the reason, or ''.
function Sync-Commit($info, [string]$baseId, [string]$tip, [bool]$on) {
  $asked = $script:SyncAsked.Base -eq $baseId -and $script:SyncAsked.Tip -eq $tip
  if (-not $asked -and (Invoke-Git @('merge-base', '--is-ancestor', $baseId, $info.branch)).Code -eq 0) { return '' }
  $merge = Invoke-Git @('merge-tree', '--write-tree', '--name-only', '--no-messages', $info.branch, $baseId)
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
    if ($built -and (Invoke-Git @('rev-parse', '--verify', '--quiet', "${baseId}:.dispatch")).Code -eq 0) {
      $built = (Invoke-Git @('read-tree', '--prefix=.dispatch/', "${baseId}:.dispatch")).Code -eq 0
    }
    $tree = if ($built) { Invoke-Git @('write-tree') } else { $null }
  } finally {
    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $index -Force -ErrorAction SilentlyContinue
  }
  if ($null -eq $tree -or $tree.Code -ne 0) { return 'git could not build the merged tree' }
  $commit = Invoke-Git @('commit-tree', "$($tree.Lines | Select-Object -Last 1)".Trim(), '-p', $tip, '-p', $baseId, '-m', "Merge $($info.base) into $($info.branch) (${Id}: the newer commits of the base)")
  if ($commit.Code -ne 0) { return "git could not make the merge commit: $($commit.Text)" }
  $made = "$($commit.Lines | Select-Object -Last 1)".Trim()
  $forward = if ($on) { Invoke-Git @('merge', '--ff-only', '-q', $made) } else { Invoke-Git @('update-ref', "refs/heads/$($info.branch)", $made, $tip) }
  if ($forward.Code -ne 0) { return "git could not move the branch: $($forward.Text)" }
  $script:SyncMade = $made
  ''
}

# Builds the merge of the task branch into the commit $Tip as a finished commit, with no checkout and
# no index of a folder (ticket merge-in-the-work-folder, steps 2 and 6 of the section Merge of
# docs/work-folders.md): git merge-tree, the folder .dispatch as $Tip has it (rule 4: a card file that
# a worker committed goes), and a commit with $Tip and the branch tip as parents, as merge --no-ff
# makes it. Gives Commit (the sha; '' when $Tip holds the branch), Conflicts (the paths outside
# .dispatch that changed on both sides) and Why (the error of git, or '').
function Build-Merge([string]$Tip, $Info) {
  $built = [pscustomobject]@{ Commit = ''; Conflicts = @(); Why = '' }
  $head = Ref-Hash $Project "refs/heads/$($Info.branch)"
  # A branch at the tip holds nothing new; else git tells whether the tip holds it.
  if ($head -eq $Tip -or (Invoke-Git @('merge-base', '--is-ancestor', $head, $Tip)).Code -eq 0) { return $built }
  $merge = Invoke-Git @('-c', 'core.quotePath=false', 'merge-tree', '--write-tree', '--name-only', '--no-messages', $Tip, $head)
  $lines = @($merge.Lines | Where-Object { $_ })
  if (($merge.Code -ne 0 -and $merge.Code -ne 1) -or $lines.Count -eq 0) {
    $built.Why = "git merge-tree failed: $($merge.Text)"
    return $built
  }
  $built.Conflicts = @($lines | Select-Object -Skip 1 | Where-Object { -not $_.StartsWith('.dispatch/') } | Select-Object -Unique)
  if ($built.Conflicts.Count -gt 0) { return $built }
  # A separate index builds the tree; the index of the project folder stays as it is.
  $index = Join-Path ([IO.Path]::GetTempPath()) "dispatch-merge-$PID.index"
  $env:GIT_INDEX_FILE = $index
  try {
    $done = (Invoke-Git @('read-tree', $lines[0])).Code -eq 0
    if ($done) { $done = (Invoke-Git @('rm', '--cached', '-r', '-q', '-f', '--ignore-unmatch', '--', '.dispatch')).Code -eq 0 }
    if ($done -and (Invoke-Git @('rev-parse', '--verify', '--quiet', "${Tip}:.dispatch")).Code -eq 0) {
      $done = (Invoke-Git @('read-tree', '--prefix=.dispatch/', "${Tip}:.dispatch")).Code -eq 0
    }
    $tree = if ($done) { Invoke-Git @('write-tree') } else { $null }
  } finally {
    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $index -Force -ErrorAction SilentlyContinue
  }
  if ($null -eq $tree -or $tree.Code -ne 0) {
    $built.Why = 'git could not build the merged tree'
    return $built
  }
  $commit = Invoke-Git @('commit-tree', "$($tree.Lines | Select-Object -Last 1)".Trim(), '-p', $Tip, '-p', $head, '-m', "Merge $($Info.branch) into $($Info.base) ($Id)")
  if ($commit.Code -ne 0) {
    $built.Why = "git could not make the merge commit: $($commit.Text)"
    return $built
  }
  $built.Commit = "$($commit.Lines | Select-Object -Last 1)".Trim()
  $built
}

# The files under .dispatch that the commit $Rev tracks (ticket the-worker-script-asks-git-about-20): a
# table path → blob hash from one `ls-tree -r <rev> -- .dispatch` (`<mode> <type> <hash><TAB><path>`);
# $null when the command fails. Tracks-Tasks: whether a table holds a file.
function Tree-Tasks([string]$Rev) {
  $result = Invoke-Git @('-c', 'core.quotePath=false', 'ls-tree', '-r', $Rev, '--', '.dispatch')
  if ($result.Code -ne 0) { return $null }
  $table = @{}
  foreach ($line in $result.Lines) {
    $tab = $line.IndexOf("`t")
    if ($tab -lt 0) { continue }
    $meta = @($line.Substring(0, $tab) -split ' ')
    if ($meta.Count -lt 3) { continue }
    $table[$line.Substring($tab + 1)] = $meta[2]
  }
  $table
}
function Tracks-Tasks($Table) { $null -ne $Table -and $Table.Count -gt 0 }
# Whether each of the commits $Revs tracks a file under .dispatch, in one `cat-file --batch-check` of the
# trees <rev>:.dispatch through stdin (a commit with the folder has a tree there; git stores no empty
# tree in a commit): a table rev → $true or $false. Tracks-Tasks of a table of Tree-Tasks says the same.
function Tasks-Tracked([string[]]$Revs) {
  $ErrorActionPreference = 'Continue'
  $lines = @(($Revs | ForEach-Object { "${_}:.dispatch" }) | & git -C $Project cat-file --batch-check 2>&1 | ForEach-Object { "$_" })
  $ErrorActionPreference = 'Stop'
  $table = @{}
  for ($i = 0; $i -lt $Revs.Count; $i++) {
    $table[$Revs[$i]] = $i -lt $lines.Count -and $lines[$i] -match '^[0-9a-f]+ tree \d+$'
  }
  $table
}

# Makes a task branch hold the task files the way the checkout holds them (ticket
# local-only-deletes-task-files), so no checkout, sync or merge deletes or overwrites a file of
# .dispatch on the disk. "Task files local only" on: a branch that tracks a file of .dispatch, while
# HEAD tracks none, gets a commit that takes the folder out of git. Off: a branch that tracks none,
# while HEAD tracks some, gets the folder as HEAD has it. Git writes a tracked file of the target over
# an ignored file, and deletes a tracked file that the target lacks. It moves the ref only, through an
# index of its own; nothing is checked out. Gives the reason of a failure, or ''. $branchTracks and
# $headTracks say whether the branch tip and HEAD track a file of .dispatch (Tracks-Tasks of a table of
# Tree-Tasks, or Tasks-Tracked); the caller reads them.
function Align-Tasks([string]$Branch, [bool]$branchTracks, [bool]$headTracks) {
  $ref = "refs/heads/$Branch"
  $tip = Ref-Hash $Project $ref
  if (-not $tip) { return '' }
  $local = Tasks-Local
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
# that runs longer than the limit is ended with its process tree; the result is then 124. $RunIn,
# when given, is the folder of the command in place of the project folder (the work folder).
function Run-Measured([string]$Command, [string]$Log, [int]$Limit, [string]$RunIn) {
  # A command of the project can write any file: the look at the uncommitted changes ends here (Dirty-Lines).
  $script:Dirty = $null
  $batch = [IO.Path]::ChangeExtension($Log, '.cmd')
  [IO.File]::WriteAllText($batch, "@echo off`r`n$Command`r`n")
  $info = New-Object System.Diagnostics.ProcessStartInfo
  $info.FileName = $env:ComSpec
  $info.Arguments = "/d /s /c `"`"$batch`" > `"$Log`" 2>&1`""
  $info.WorkingDirectory = if ($RunIn) { $RunIn } else { $Project }
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
# file of the task (the full run). $RunIn, when given, is the folder of the checks (the work folder);
# not $Folder: $LogOf of the verb check reads its own $folder, and PowerShell names ignore case.
function Run-Checks([string]$Place, [scriptblock]$LogOf, [int]$Limit, [switch]$StopAtFailure, [string]$List, [string]$Checked, [string]$RunIn) {
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
    $code = Run-Measured $command $log $Limit $RunIn
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

# The prompt of a new work run up to the end of its checks (ticket moving-a-project): approve, and a
# reply or resume that takes over a work run whose session started in the project folder. The text
# only; the caller removes the pick files first (Clear-Picks) when Pick the checks after the work run is on.
function Work-Prompt {
  # The work run is a new session (ticket work-fresh-session): the prompt names the ticket, the
  # brief, the plan and the report file, and the files are its whole input.
  $plan = Join-Path $Reports "plans\$Id.md"
  $brief = Join-Path $Reports "briefs\$Id.md"
  $report = Join-Path $Reports "$Id.md"
  $prompt = "Plan approved: $plan. This is the work run of ticket $Id. Read the brief $brief and the plan first: they are the whole context of this run, and the sections User decisions and Checks chosen of the plan hold the choices of the user. Execute the plan. A deviation goes into the report, not into the code. Commit only the files that this run changed, each by name in git add and git commit; never run git add -A, git add . or git commit -a, and leave every other change in the checkout as it is. Write the report to $report."
  # Pick the checks after the work run: the run is told that the checks come after it. Else the
  # checks that the user chose at Approve (ticket task-checks): one line per check, the name, a tab and the command. A quote of a name becomes an apostrophe, as in a reply.
  $chosen = Join-Path $Reports "$Id.checks"
  if (Option-On 'pickChecks') {
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
  $prompt
}

# The sentence of the work folder for the prompt of a new work run (ticket work-run-in-the-work-folder).
# With more than one work folder (ticket up-to-4-work-folders-more-than-one-task) it names the number
# of the folder, and a build folder that the project rules share between runs gets that number (user
# decision 3).
function Folder-Sentence {
  $info = Read-BranchFile $Id
  $count = Folder-Count
  if ($count -eq 1 -and $script:FolderNo -eq 1) {
    return " Work folder is on: this run works in the folder $(Work-Folder), a checkout of the branch $($info.branch) of this task; the project folder $Project stays on $($info.base). Commit in the work folder; switch no branch, merge nothing, push nothing. Write the report and every task file by their full paths in $Reports; the work folder has no copy of them. When this run stops with a question or fails, first commit its own changed files by name on this branch with the message $Id WIP."
  }
  " Work folder is on: this run works in the work folder $script:FolderNo of $count, the folder $(Work-Folder), a checkout of the branch $($info.branch) of this task; the project folder $Project stays on $($info.base). Commit in the work folder; switch no branch, merge nothing, push nothing. Write the report and every task file by their full paths in $Reports; the work folder has no copy of them. When this run stops with a question or fails, first commit its own changed files by name on this branch with the message $Id WIP. Up to $count work runs of this project run at the same time, one per work folder: a build or test output folder that the project rules share between runs keeps its name in work folder 1 and gets the suffix -<n> in work folder <n>, so this run uses the suffix -$script:FolderNo when its number is above 1 (the variable DISPATCH_WORK_FOLDER is $script:FolderNo), and two runs never write into one such folder."
}

# True when $Worker started in another folder than a work folder of this project (ticket
# moving-a-project; with more than one work folder, ticket up-to-4-work-folders-more-than-one-task). A
# worker without cwd (an older Claude Code) counts as a worker of a work folder.
function Old-Session($Worker) {
  $cwd = "$($Worker.cwd)"
  if (-not $cwd) { return $false }
  (Folder-Number $cwd) -eq 0
}

# Resumes the session of $Worker for $Run with $Text: the start of reply and of resume. $State, when
# given, is written before the branch switch; without it a tracked branch rewrites the current state.
# The own changes of a stopped run are found while the state still says stopped: the state file time
# is the end of that run. A failed claude writes $StateOnFailure back when one is given.
function Resume-Run([string]$Run, $Worker, [string]$Text, [string]$State, [string]$StateOnFailure) {
  $flags = Run-Flags $Run
  # Work folder (ticket work-run-in-the-work-folder): a work run goes on in the work folder on the
  # branch of its task; the project folder is not switched, and its changes hold nothing.
  # A session that started in the project folder (a task of the old path, ticket moving-a-project)
  # may not be found from the work folder and remembers the paths of the project folder: a new
  # session in the work folder takes it over (user decision 1).
  if ($Run -eq 'work' -and (Folders-On)) {
    $note = Enter-WorkFolder 'work' $Worker
    if ($State) { Set-State $State }
    if (Old-Session $Worker) {
      $info = Read-BranchFile $Id
      $prompt = (Work-Prompt) + " This work run started in the project folder before Work folder was on, and its session cannot go on in the work folder, so this new session takes it over. Its work so far is committed on the branch $($info.branch). Read its report $(Join-Path $Reports "$Id.md") too: it holds its questions and what it did. The user's words to that session: $Text"
      $prompt += (Folder-Sentence) + $note
      $printed = Run-Claude (@($prompt, '--bg', '--name', (Run-Name $Run)) + $flags) $StateOnFailure '' (Work-Folder)
    } else {
      $printed = Run-Claude (@(($Text + $note), '--resume', $Worker.sessionId, '--bg', '--name', (Run-Name $Run)) + $flags) $StateOnFailure '' (Work-Folder)
    }
    Save-Session $Run $printed
    Write-Record 'work' (Work-Job)
    return
  }
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
    # Work folder (ticket work-run-in-the-work-folder): the work run uses its own folder, so an
    # uncommitted change of the user in the project folder holds no Approve.
    if ((Test-Path -LiteralPath (Join-Path $Project '.git')) -and -not (Folders-On)) {
      # Only a change in a file of this task holds it (ticket leftover-files-block-tasks); the other
      # changes stay where they are. The task files in .dispatch do not count: Dispatch commits them
      # itself, and triage can write a brief at any time. The staged files of a running merge are no
      # changes of the user (ticket approve-during-a-merge-is-refused-by-the): the check waits until
      # the merge has committed or was undone.
      Wait-MergeLock
      $held = @(Task-Leftovers -Plan)
      if ($held.Count -gt 0) { Refuse "these changes in files of this task are not committed; commit, stash or undo them first:`n$($held -join "`n")" }
    }
    Approve-Check
    $flags = Run-Flags 'work'
    # Pick the checks after the work run: the files of an earlier pick go before the prompt reads them.
    if (Option-On 'pickChecks') { Clear-Picks }
    $prompt = Work-Prompt
    # Task branches: the work run works on the branch of the task; a failed start checks out the branch before.
    # Work folder: it works in the work folder on that branch, and the project folder stays on the base.
    $back = ''
    $folder = ''
    if (Folders-On) {
      $note = Enter-WorkFolder 'work'
      $folder = Work-Folder
      $prompt += Folder-Sentence
      $prompt += $note
    } elseif (Branches-On) {
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
    $printed = Run-Claude (@($prompt, '--bg', '--name', (Run-Name 'work')) + $flags) 'planning' $back $folder
    Set-State 'working'
    Save-Session 'work' $printed
    if ($folder) { Write-Record 'work' (Work-Job) }
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
    $tip = Ref-Hash $Project "refs/heads/$($info.branch)"
    if (-not $tip) { Refuse "the branch $($info.branch) does not exist; the task had no work run" }
    $current = Current-Branch
    # Work folder (ticket merge-in-the-work-folder): the merge runs in the work folder, so the project
    # folder can be on any branch and its task files stay as they are.
    $folders = Folders-On
    # A task of the old path (ticket moving-a-project): the merge would move the base and leave the
    # project folder on a merged branch.
    if ($folders -and $current -eq $info.branch) {
      Refuse "the project folder is on $($info.branch), the branch of this task, from before Work folder was on; Dispatch switches it back to $($info.base) when its run has ended and no file of the task has uncommitted changes (the task card names what holds it); then click Merge again"
    }
    if (-not $folders) {
      if ($current -ne $info.base) { Refuse "the checkout is on $current, not on the base $($info.base); Dispatch switches back when the work run has ended" }
      if (Merge-Open $Project) { Refuse 'the checkout has an unfinished merge; finish it or run git merge --abort first' }
    }
    # Only a change in a file that the branch changes holds the merge (ticket leftover-files-block-tasks);
    # the gate then runs with the other changes in the folder. With Work folder on, only while the
    # project folder is on the base: then the move would meet the change, and no gate runs in vain.
    $held = if (-not $folders -or $current -eq $info.base) { @(Task-Leftovers) } else { @() }
    if ($held.Count -gt 0) { Refuse "the checkout has uncommitted changes in files that the branch changes; commit, stash or undo them first:`n$($held -join "`n")" }
    if (-not $folders) {
      # The changed task files (the tickets file among them) go into one commit first, so git merge
      # never meets a changed file of .dispatch. With "Task files local only" on, git tracks none.
      $local = Tasks-Local
      $tasks = if ($local) { @() } else { @(Dirty-Lines | Where-Object { Under-Tasks (Line-Path $_) }) }
      if ($tasks.Count -gt 0) {
        Invoke-Git @('add', '--all', '--', '.dispatch') | Out-Null
        $saved = Invoke-Git @('commit', '-q', '-m', "Dispatch: task files before the merge of $Id", '--', '.dispatch')
        if ($saved.Code -ne 0) { Refuse "git could not commit the task files before the merge: $($saved.Text)" }
      }
      # The branch holds the task files as the base does, so the merge deletes none of them.
      $tracked = Tasks-Tracked @($tip, 'HEAD')
      $aligned = "$(Align-Tasks $info.branch $tracked[$tip] $tracked['HEAD'])"
      if ($aligned) { Refuse $aligned }
    }
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
    $tip = Ref-Hash $Project "refs/heads/$($info.branch)"
    $head = Head-Hash $Project
    if ($folders) {
      # The sixth field folder: the merge runs in the work folder, and the fourth field is the tip of
      # the base; Dispatch cancels no merge in the project folder for it.
      $head = Ref-Hash $Project "refs/heads/$($info.base)"
      [IO.File]::WriteAllText($marker, "$PID`t$Id`t$tip`t$head`t$((Get-Date).ToString('o'))`tfolder`n")
    } else {
      [IO.File]::WriteAllText($marker, "$PID`t$Id`t$tip`t$head`t$((Get-Date).ToString('o'))`n")
    }
    try {
      $refusal = if ($folders) { "$(Merge-InWorkFolder $info $gate $limit)" } else { "$(Merge-Branch $info $gate $limit)" }
    } finally {
      if ($folders) {
        # A merge left in the work folder stays there; its next user cleans it.
        $record = Read-Record
        if ($null -ne $record -and $record.pid -eq "$PID") { Remove-Item -LiteralPath (Work-Record) -Force -ErrorAction SilentlyContinue }
      } elseif (-not $script:Merged -and (Merge-Open $Project)) {
        # Every way out inside PowerShell (a refusal, an error, Ctrl+C) leaves no open merge.
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
      # Work folder (ticket work-run-in-the-work-folder): the checks run in the work folder on the task
      # branch, and the record names this run until it ends.
      if (Folders-On) {
        Enter-WorkFolder 'check' | Out-Null
        Run-Checks 'run' { param($n) Join-Path $folder "$n.log" } $limit -RunIn (Work-Folder) | Out-Null
      } else {
        Run-Checks 'run' { param($n) Join-Path $folder "$n.log" } $limit | Out-Null
      }
    } finally {
      Exit-Lock (Lock-Path)
      $record = Read-Record
      if ($null -ne $record -and $record.pid -eq "$PID") { Remove-Item -LiteralPath (Work-Record) -Force -ErrorAction SilentlyContinue }
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
