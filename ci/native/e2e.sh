#!/usr/bin/env bash
# End-to-end checks of a Linux native lighten binary against the user guide's contract: "What each
# rule does on disk", Scripting and Undo.
#
#   ci/native/e2e.sh <binary> [results-dir]
#
# Each case builds a home and a target root under <results-dir>/e2e/<case>, runs `plan --json` and
# `apply --json --yes`, and checks the disk: links, contents and permissions, the archive, no
# copy left in staging. A case that applies then plans again (only no-op or leave-unchanged steps),
# applies again and checks that nothing on disk changed. A case whose plan is blocked or needs a
# choice checks that apply is refused and changes nothing.
#
# Recovery cases stop an apply partway: a step that fails, and the TUI apply killed with SIGKILL
# during the copy and after it (kill.exp, with --debug-step-delay-ms). Each then checks that the
# source is untouched, that a new check shows the true state, and that applying again converges.
# The parity case compares the TUI's Workspace rows (tui.exp, rendered by render.py) with
# `plan --json` for one fixture holding most states.
#
# KNOWN_FAILING below lists cases that find a contract break: they run, print XFAIL and do not fail
# the run, and fail it once they pass, so the entry is removed with the fix.
#
# Needs bash, GNU find, jq, python3 with pyte, expect and a non-root user (root reads anything, so
# the unreadable cases would not test anything). Exit status is non-zero when a case fails.
set -u

binary=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
here=$(cd "$(dirname "$0")" && pwd)
results=${2:-$(mktemp -d "${TMPDIR:-/tmp}/lighten-e2e.XXXXXX")}/e2e
rm -rf "$results"
mkdir -p "$results"
results=$(cd "$results" && pwd)

for tool in jq python3 expect; do
  command -v $tool > /dev/null || { echo "e2e needs $tool" >&2; exit 2; }
done
[ "$(id -u)" -ne 0 ] || { echo "e2e must run as a user other than root" >&2; exit 2; }

# case -> the issue it shows. Keep each message enough to file or find the issue.
declare -A KNOWN_FAILING=()

failed=()
matrix=$results/matrix.txt
: > "$matrix"

# --- case bookkeeping

begin() { # <case> <what it covers> [directory to build it in, default results-dir]
  name=$1; about=$2; ok=1; notes=(); step=0; started=$(date +%s%N)
  C=${3:-$results}/$name; H=$C/home; L=$C/local
  mkdir -p "$H" "$L"
}

check() { # <description> <command...>: records a failed expectation
  "${@:2}" || { ok=0; notes+=("$1"); }
}

end() {
  local ms=$(( ($(date +%s%N) - started) / 1000000 )) known=${KNOWN_FAILING[$name]:-} result
  if [ $ok -eq 1 ] && [ -z "$known" ]; then result=PASS
  elif [ $ok -eq 1 ]; then result=XPASS; failed+=("$name (passes now: remove it from KNOWN_FAILING)")
  elif [ -n "$known" ]; then result=XFAIL
  else result=FAIL; failed+=("$name")
  fi
  echo "$result $name: $about (${ms}ms)"
  [ $ok -eq 1 ] || printf '  not: %s\n' "${notes[@]}"
  [ "$result" != XFAIL ] || echo "  known: $known"
  printf '%s\t%s\t%s\t%s\n' "$result" "$name" "$about" "$(IFS=';'; echo "${notes[*]:-}")" >> "$matrix"
}

skip() { # <case> <why>
  echo "SKIP $1: $2"
  printf 'SKIP\t%s\t%s\t\n' "$1" "$2" >> "$matrix"
}

# --- running lighten

run() { # <command> [args...]: lighten --json against $C/config.json; output in $out, exit code in $status
  step=$((step + 1))
  out=$C/$step-$1.json
  "$binary" -c "$C/config.json" "$@" --json > "$out" 2> "$out.err" < /dev/null
  status=$?
}

j() { jq -e "$@" "$out" > /dev/null; }
exits() { [ "$status" -eq "$1" ]; }
matches() { [[ $1 =~ $2 ]]; }

config() { # <relocation...> [-- <more lighten settings as JSON members>]
  local relocations=() extra=""
  while [ $# -gt 0 ]; do
    [ "$1" = -- ] && { extra=", $2"; break; }
    relocations+=("$1"); shift
  done
  printf '{"lighten": {"target-root": "%s", "relocations": [%s]%s}}\n' \
    "$L" "$(IFS=,; echo "${relocations[*]:-}")" "$extra" > "$C/config.json"
}

rel() { # <name> [key=value...]: a relocation from $H/<name> to $L/<name>
  local name=$1 members=""; shift
  for kv in "$@"; do members+=", \"${kv%%=*}\": \"${kv#*=}\""; done
  printf '{"source-path": "%s/%s", "target-path": "%s/%s"%s}' "$H" "$name" "$L" "$name" "$members"
}

# --- reading the disk

# Every path under the case with its type, permissions and link target, and every file's checksum.
# An unreadable directory is listed without its contents.
snapshot() {
  (cd "$C" && find home local \( -type l -printf '%M %p -> %l\n' \) -o -printf '%M %p\n' 2> /dev/null | LC_ALL=C sort &&
    find home local -type f -exec sha256sum {} + 2> /dev/null | LC_ALL=C sort)
}

# A tree's contents relative to its root, through a link at the root.
tree() {
  (cd "$1" && find . \( -type l -printf '%M %p -> %l\n' \) -o -printf '%M %p\n' | LC_ALL=C sort &&
    find . -type f -exec sha256sum {} + | LC_ALL=C sort)
}

links_to() { [ -L "$1" ] && [ "$(readlink "$1")" = "$2" ] && [ -d "$1" ]; }
is_dir() { [ -d "$1" ] && [ ! -L "$1" ]; }

# Staging keeps only its per-target lock files; the source's parent keeps no set-aside tree.
clean() {
  [ -z "$(find "$C" -name 'operation-*' ! -name '*.lock' -print -quit)" ] &&
    [ -z "$(find "$C" -name '.lighten-replaced-*' -print -quit)" ]
}

# --- shared expectations

types() { # <jq path to an actions array> <types...>: the action types, in order
  local want; want=$(printf '%s\n' "${@:2}" | jq -R . | jq -sc .)
  [ "$(jq -c "[$1[].type]" "$out")" = "$want" ]
}

applies() { # apply succeeds and every step completes
  run apply --yes
  check "apply exits 0 (exit $status: $(head -c 300 "$out.err"))" exits 0
  check "apply succeeded with every step completed" j '.succeeded and all(.relocations[].actions[]; .status == "completed")'
}

converges() { # planning again finds nothing to do; applying again changes nothing
  check "no staging copy or set-aside source left" clean
  run plan
  check "plan again: only no-op or leave-unchanged ($(jq -c '[.actions[].type]' "$out"))" \
    j 'all(.actions[]; .type == "no-op" or .type == "leave-unchanged") and (.blocked or .conflicts | not)'
  local before; before=$(snapshot)
  run apply --yes
  check "apply again exits 0" exits 0
  check "apply again changes nothing" [ "$before" = "$(snapshot)" ]
}

refused() { # apply exits 1 with the plan, changes nothing, and plan again still says so
  local before; before=$(snapshot)
  run apply --yes
  check "apply refused with exit 1" exits 1
  check "refused apply prints the plan" j '.relocations and (.succeeded | not)'
  check "refused apply changes nothing" [ "$before" = "$(snapshot)" ]
}

conflict() { # <relocation index> <resolutions...>: a choice is needed, offering these
  local want; want=$(printf '%s\n' "${@:2}" | jq -R . | jq -sc .)
  check "plan exits 0" exits 0
  check "conflicts is true" j '.conflicts'
  check "relocation $1 offers $want, got $(jq -c ".relocations[$1].conflict.resolutions" "$out")" \
    [ "$(jq -c ".relocations[$1].conflict.resolutions" "$out")" = "$want" ]
}

blocked() { # <relocation index> <text in its reason>
  check "plan exits 0" exits 0
  check "blocked is true" j '.blocked'
  check "relocation $1 is one blocked step mentioning '$2', got $(jq -c ".relocations[$1].actions" "$out")" \
    j --arg r "$2" ".relocations[$1].actions | length == 1 and .[0].type == \"blocked\" and (.[0].reason | contains(\$r))"
}

# A source tree with nested directories, a relative link, private permissions and a file with spaces.
fill() { # <dir>
  mkdir -p "$1/sub/deep" "$1/open"
  echo "a $1" > "$1/a.txt"
  echo nested > "$1/sub/deep/n.txt"
  echo spaced > "$1/sub/with space.txt"
  ln -s a.txt "$1/rel-link"
  chmod 700 "$1"; chmod 750 "$1/sub"; chmod 755 "$1/open"; chmod 640 "$1/a.txt"
}

# --- rules: what each rule does on disk

begin move "only the source exists: [Move] copies, keeps contents and permissions, links"
fill "$H/app"
want=$(tree "$H/app")
config "$(rel app)"
run plan
check "plan: migrate then replace" types '.relocations[0].actions' migrate-directory-for-publication replace-directory-with-symlink
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target has the source's contents, modes and links" [ "$want" = "$(tree "$L/app")" ]
converges
end

begin link "neither exists: [Link] creates an empty target and links the source"
config "$(rel new)"
run plan
check "plan: create target, link" types '.relocations[0].actions' ensure-directory create-directory ensure-directory create-symlink
applies
check "target is an empty directory" [ -z "$(ls -A "$L/new")" ]
check "source links to target" links_to "$H/new" "$L/new"
converges
end

begin in-sync "the source already links to the target: [In sync], nothing to do"
mkdir -p "$L/app"; echo t > "$L/app/t"; ln -s "$L/app" "$H/app"
config "$(rel app)"
run plan
check "plan: one no-op, converged" j '[.relocations[0].actions[].type] == ["no-op"] and .relocations[0].outcome == "converged"'
converges
end

begin only-target-prompt "only the target, Ask each time: [Choose] adopt-target; apply refused"
mkdir -p "$L/app"; echo t > "$L/app/t"
config "$(rel app)"
run plan
conflict 0 adopt-target
refused
end

begin only-target-adopt "only the target, adopt-target: [Link] keeps the target"
mkdir -p "$L/app"; echo t > "$L/app/t"
want=$(tree "$L/app")
config "$(rel app when-only-target-exists=adopt-target)"
run plan
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target unchanged" [ "$want" = "$(tree "$L/app")" ]
converges
end

both() { mkdir -p "$H/app" "$L/app"; echo src > "$H/app/s"; echo tgt > "$L/app/t"; }

begin both-prompt "both exist, Ask each time: [Choose] with four choices; apply refused"
both
config "$(rel app)"
run plan
conflict 0 adopt-and-discard-source adopt-and-archive-source leave-unchanged discard-both
refused
end

# The guide: "nothing until you choose to delete or archive the source". Details still offers all four.
begin both-adopt-prompt "both exist, adopt and Ask each time: [Choose]; apply refused"
both
config "$(rel app when-source-and-target-directories-exist=adopt)"
run plan
check "plan needs a choice for the source" j '.conflicts and (.relocations[0].conflict.resolutions | index("adopt-and-discard-source") and index("adopt-and-archive-source"))'
refused
end

begin both-adopt-discard "both exist, adopt and discard-source: [Keep target] deletes the source"
both
want=$(tree "$L/app")
config "$(rel app when-source-and-target-directories-exist=adopt when-adopting-target=discard-source)"
run plan
check "plan has a destructive step" j 'any(.relocations[0].actions[]; .destructive)'
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target unchanged" [ "$want" = "$(tree "$L/app")" ]
converges
end

begin both-adopt-archive "both exist, adopt and archive-source: [Archive] to .lighten-archive beside the source"
both
src=$(tree "$H/app"); want=$(tree "$L/app")
config "$(rel app when-source-and-target-directories-exist=adopt when-adopting-target=archive-source)"
run plan
check "plan: archive then link" j '[.relocations[0].actions[].type] | index("archive-directory") and index("create-symlink")'
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target unchanged" [ "$want" = "$(tree "$L/app")" ]
check "archive holds the source" [ "$src" = "$(tree "$H/.lighten-archive/app" 2> /dev/null)" ]
converges
end

begin archive-name-taken "archive-source into an archive-root whose name is taken: adds a short code"
both
mkdir -p "$C/old/app"; echo earlier > "$C/old/app/e"
src=$(tree "$H/app"); earlier=$(tree "$C/old/app")
config "$(rel app when-source-and-target-directories-exist=adopt when-adopting-target=archive-source archive-root="$C/old")"
applies
archived=$(find "$C/old" -mindepth 1 -maxdepth 1 -name 'app-*')
check "archived as app-<8 hex>, got '${archived##*/}'" matches "${archived##*/}" '^app-[0-9a-f]{8}$'
check "archive holds the source" [ "$src" = "$(tree "$archived" 2> /dev/null)" ]
check "the earlier archive is untouched" [ "$earlier" = "$(tree "$C/old/app")" ]
converges
end

begin both-leave "both exist, leave-unchanged: [Left as is], nothing changes"
both
before=$(snapshot)
config "$(rel app when-source-and-target-directories-exist=leave-unchanged)"
run plan
check "plan: unchanged" j '.relocations[0].outcome == "unchanged" and [.relocations[0].actions[].type] == ["leave-unchanged"]'
applies
check "nothing changed" [ "$before" = "$(snapshot)" ]
converges
end

begin both-discard "both exist, discard: [Delete] empties both and links"
both
config "$(rel app when-source-and-target-directories-exist=discard)"
applies
check "target is an empty directory" [ -z "$(ls -A "$L/app")" ]
check "source links to target" links_to "$H/app" "$L/app"
converges
end

# --- states Lighten blocks

begin wrong-link "the source links somewhere else: [Blocked], says where it points"
mkdir -p "$C/elsewhere" "$L/app"; ln -s "$C/elsewhere" "$H/app"
config "$(rel app)"
run plan
blocked 0 "$C/elsewhere"
refused
end

begin broken-link-elsewhere "a dangling source link to somewhere else, target exists: [Blocked]"
mkdir -p "$L/app"; ln -s "$C/unmounted/app" "$H/app"
config "$(rel app)"
run plan
blocked 0 "$C/unmounted/app"
check "the reason says what it links to does not exist now" j '.relocations[0].actions[0].reason | contains("does not exist now")'
refused
check "the link still points where it did" [ "$(readlink "$H/app")" = "$C/unmounted/app" ]
end

begin broken-link "a dangling source link to the target, no target: [Blocked]"
ln -s "$L/app" "$H/app"
config "$(rel app)"
run plan
blocked 0 "no target directory"
refused
end

begin source-file "the source is a file: [Blocked]"
echo f > "$H/app"
config "$(rel app)"
run plan
blocked 0 "file"
refused
end

begin unreadable "the source can't be read: [Can't read], blocked"
mkdir -p "$H/app"; echo secret > "$H/app/s"; chmod 000 "$H/app"
config "$(rel app)"
run status
check "status: inaccessible" j '.relocations[0].state == "inaccessible"'
run plan
blocked 0 "inspected"
refused
chmod 700 "$H/app"
end

begin directory-in-way "a file where the target's parent directory must be: [Blocked], says which"
fill "$H/app"
echo f > "$L/deep"
config "{\"source-path\": \"$H/app\", \"target-path\": \"$L/deep/app\"}"
run plan
blocked 0 "$L/deep"
refused
end

begin overlap "overlapping relocations: only those blocked, the rest planned; apply refused"
fill "$H/a"; mkdir -p "$H/a/b"; fill "$H/c"
config "$(rel a)" "$(rel a/b)" "$(rel c)"
run plan
blocked 0 "$H/a/b"
blocked 1 "$H/a"
check "the independent relocation is planned" j '.relocations[2].actions | any(.type == "migrate-directory-for-publication")'
refused
end

begin ignored "an ignored source: not planned, left alone"
fill "$H/ign"; fill "$H/app"
want=$(tree "$H/ign")
config "$(rel app)" -- "\"ignored-source-paths\": [\"$H/ign\"]"
run plan
check "plan names only the relocation" j '[.relocations[].source] == ["'"$H/app"'"]'
applies
check "ignored source untouched, still a directory" is_dir "$H/ign"
check "ignored contents untouched" [ "$want" = "$(tree "$H/ign")" ]
converges
end

# Needs a second filesystem without root: /dev/shm is a tmpfs on Linux hosts and in containers.
shm=/dev/shm/lighten-e2e-$$
if [ -d /dev/shm ] && [ -w /dev/shm ] && mkdir -p "$shm" &&
    [ "$(stat -c %d /dev/shm)" != "$(stat -c %d "$results")" ]; then
  begin staging-other-fs "staging on another filesystem than the target: [Blocked], names staging-root"
  fill "$H/app"
  config "{\"source-path\": \"$H/app\", \"target-path\": \"$shm/app\"}" -- "\"staging-root\": \"$L/.staging\""
  run plan
  blocked 0 "staging-root"
  refused
  check "nothing created on the other filesystem" [ -z "$(ls -A "$shm")" ]
  end
else
  skip staging-other-fs "no /dev/shm on another filesystem than $results"
fi
rm -rf "$shm"

# --- special files

begin socket "a directory with a socket: moved, the socket skipped and named"
fill "$H/app"
python3 -c 'import socket, sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$H/app/sub/app.sock"
want=$(tree "$H/app" | grep -v 'app.sock')
config "$(rel app)"
applies
check "a step names the socket: $(jq -c '[.relocations[0].actions[].message]' "$out")" \
  j 'any(.relocations[0].actions[]; .message | contains("app.sock"))'
check "source links to target" links_to "$H/app" "$L/app"
check "target has everything but the socket" [ "$want" = "$(tree "$L/app")" ]
converges
end

begin named-pipe "a directory with a named pipe: stops cleanly, nothing moves"
fill "$H/app"
mkfifo "$H/app/sub/ipc"
want=$(tree "$H/app")
config "$(rel app)"
run apply --yes
check "apply exits 1" exits 1
check "the failed step names the pipe: $(jq -c '[.relocations[0].actions[] | {status, message}]' "$out")" \
  j 'any(.relocations[0].actions[]; .status == "failed" and (.message | contains("ipc")))'
check "source untouched" [ "$want" = "$(tree "$H/app")" ]
check "source is still a directory" is_dir "$H/app"
check "no target" [ ! -e "$L/app" ]
check "no staging copy left" clean
run plan
check "plan again: still a move" j '.relocations[0].actions | any(.type == "migrate-directory-for-publication")'
end

# --- recovery

# The copy is published, then replacing the source fails: like a crash at that point (decisions.md, "A crash
# between publishing and setting the source aside is left as is"), both directories stay whole and the next check
# says both exist. A Both exist rule finishes it.
begin step-fails "a step forced to fail (source's parent read-only): stops, check again, apply converges"
fill "$H/app"
want=$(tree "$H/app")
config "$(rel app)"
chmod 555 "$H"
run apply --yes
chmod 755 "$H"
check "apply exits 1" exits 1
check "the copy completed and the source replacement failed: $(jq -c '[.relocations[0].actions[] | {type, status, message}]' "$out")" \
  j '[.relocations[0].actions[].status] == ["completed", "failed"]'
check "source untouched" [ "$want" = "$(tree "$H/app")" ]
check "the copy was published whole" [ "$want" = "$(tree "$L/app" 2> /dev/null)" ]
check "no staging copy or set-aside source left" clean
run status
check "status: source is a directory" j '.relocations[0].state == "directory"'
run plan
conflict 0 adopt-and-discard-source adopt-and-archive-source leave-unchanged discard-both
config "$(rel app when-source-and-target-directories-exist=adopt when-adopting-target=discard-source)"
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target has the source's contents" [ "$want" = "$(tree "$L/app")" ]
converges
end

# A TUI apply killed with SIGKILL when <glob> appears, then checked and applied again.
killed() { # <glob> <delay-ms>
  line=$(TERM=xterm-256color expect "$here/kill.exp" "$C/kill.log" "$1" \
    "$binary" --debug-step-delay-ms "$2" -c "$C/config.json" apply)
  check "killed partway ($line)" [ $? -eq 0 ]
  echo "  $line; files in staging: $(find "$L" -path '*/operation-*/*' -type f | wc -l)"
  check "source untouched" [ "$want" = "$(tree "$H/app")" ]
  check "source is still a directory" is_dir "$H/app"
  run status
  check "status: source is a directory" j '.relocations[0].state == "directory"'
}

# Enough files that the copy and its check take a while to walk.
big() { mkdir -p "$H/app/many"; (cd "$H/app/many" && head -c 48M /dev/zero | split -b 12k -a 3 - f); }

begin killed-during-copy "TUI apply killed during the copy: source untouched, check shows it, apply converges"
fill "$H/app"; big
want=$(tree "$H/app")
config "$(rel app)"
killed "$L/.lighten-staging/operation-*/many/f*" 0
check "the copy was not published" [ ! -e "$L/app" ]
run plan
check "a new check plans the move again" types '.relocations[0].actions' migrate-directory-for-publication replace-directory-with-symlink
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target has the source's contents" [ "$want" = "$(tree "$L/app")" ]
converges
end

# Decided behaviour ("A crash between publishing and setting the source aside is left as is"): two
# whole directories, so the next check says both exist and a Both exist rule finishes it.
begin killed-after-copy "TUI apply killed after the copy is published: both exist, a rule converges"
fill "$H/app"
want=$(tree "$H/app")
config "$(rel app)"
killed "$L/app" 3000
check "the copy was published whole" [ "$want" = "$(tree "$L/app" 2> /dev/null)" ]
run plan
conflict 0 adopt-and-discard-source adopt-and-archive-source leave-unchanged discard-both
config "$(rel app when-source-and-target-directories-exist=adopt when-adopting-target=discard-source)"
applies
check "source links to target" links_to "$H/app" "$L/app"
check "target has the source's contents" [ "$want" = "$(tree "$L/app")" ]
converges
end

# decisions.md, "A source is replaced by its link in atomic steps": the set-aside tree is deleted after the link
# takes its place, and the next plan deletes what is left of it.
begin killed-during-replace "TUI apply killed while deleting the set-aside source: the link stands, apply converges"
fill "$H/app"; big
want=$(tree "$H/app")
config "$(rel app)"
line=$(TERM=xterm-256color expect "$here/kill.exp" "$C/kill.log" "$H/.lighten-replaced-*" \
  "$binary" -c "$C/config.json" apply)
check "killed partway ($line)" [ $? -eq 0 ]
echo "  $line; set-aside files left: $(find "$H" -path '*/.lighten-replaced-*' -type f | wc -l)"
check "target has the source's contents" [ "$want" = "$(tree "$L/app" 2> /dev/null)" ]
run status
check "status: the source links to the target" j '.relocations[0].state == "correct_symlink"'
run plan
check "a new check plans no change to the source or target: $(jq -c '[.actions[].type]' "$out")" \
  j '(.blocked or .conflicts | not) and all(.actions[]; .type == "no-op" or .type == "delete-directory")'
applies
check "source links to target" links_to "$H/app" "$L/app"
converges
end

begin move-new-parents "a move to a target whose parent directories do not exist yet"
fill "$H/app"
want=$(tree "$H/app")
config "{\"source-path\": \"$H/app\", \"target-path\": \"$L/x/y/app\"}"
applies
check "source links to target" links_to "$H/app" "$L/x/y/app"
check "target has the source's contents" [ "$want" = "$(tree "$L/x/y/app")" ]
converges
end

# --- links the user made: what Browse's take-over writes is in sync, and a chain is not

# Browse takes over a link with the target its text names against its parent. A relocation written that way is in
# sync, for an absolute and a relative link. One whose target is the end of a chain, or the link in the middle of it,
# is blocked: so Browse refuses a chain.
begin link-targets "a link's own target is in sync; a chain is blocked whichever end is the target"
mkdir -p "$L/abs" "$L/rel" "$L/end"
ln -s "$L/abs" "$H/abs"
ln -s ../local/rel "$H/rel"
ln -s "$L/end" "$L/hop"
ln -s "$L/hop" "$H/chain"
ln -s "$L/hop" "$H/chain-end"
config "$(rel abs)" "$(rel rel)" "{\"source-path\": \"$H/chain\", \"target-path\": \"$L/hop\"}" \
  "{\"source-path\": \"$H/chain-end\", \"target-path\": \"$L/end\"}"
before=$(snapshot)
run plan
check "plan exits 0" exits 0
check "absolute and relative links are in sync: $(jq -c '[.relocations[0,1].actions[].type]' "$out")" \
  j 'all(.relocations[0,1].actions[]; .type == "no-op")'
check "a link to a link as the target is blocked" j '.relocations[2].actions[0].type == "blocked"'
check "the end of the chain as the target is blocked" j '.relocations[3].actions[0].type == "blocked"'
refused
check "nothing on disk changed" [ "$before" = "$(snapshot)" ]
end

# Built under a short path, so the paths in notes fit the row and are not under the account's home.
short=$(mktemp -d /tmp/lighten-e2e.XXXXXX)
begin take-over "Browse's L takes over links made by hand, absolute, relative and a linked parent; plan then finds them in sync" "$short"
S=$C/storage
mkdir -p "$S/abs" "$S/rel" "$S/end" "$S/cache/uv" "$S/pip-elsewhere" "$S/uv-elsewhere" "$L/under" "$H/real"
ln -s "$S/abs" "$H/abs"
ln -s ../storage/rel "$H/rel"
ln -s "$L/under" "$H/under"
ln -s "$S/end" "$S/hop"
ln -s "$S/hop" "$H/chain"
ln -s "$S/gone" "$H/broken"
ln -s "$H/real" "$H/inside"
# A linked parent, with a relative and an absolute link inside it that must stay as they are.
ln -s "$S/cache" "$H/.cache"
ln -s ../pip-elsewhere "$S/cache/pip"
ln -s "$S/uv-elsewhere" "$S/cache/uv-link"
printf '{"directories": [%s]}\n' \
  "$(printf '{"path": "%s"},' abs rel under chain broken inside .cache/pip .cache/uv-link | sed 's/,$//')" > "$C/list.json"
printf '{"lighten": {"source-root": "%s", "target-root": "%s", "suggestion-list": "%s", "ignored-source-paths": ["%s/ignored"]}}\n' \
  "$H" "$L" "$C/list.json" "$H" > "$C/config.json"
disk() { (cd "$C" && find home local storage \( -type l -printf '%M %p -> %l\n' \) -o -printf '%M %p\n' | LC_ALL=C sort); }
before=$(disk)
line=$(TERM=xterm-256color expect "$here/takeover.exp" "$C/takeover.log" "$binary" -c "$C/config.json")
check "TUI ran ($line)" [ $? -eq 0 ]
python3 "$here/render.py" "$C/takeover.log" 120x40 > "$C/screens.txt"
awk '/^--- /{n++} {print > (dir "/screen-" n ".txt")}' dir="$C" "$C/screens.txt"
for want in "4 directories are links you made. Press L to take them over." "already a link" "which is a link" \
    "link is broken" "link points to another link" "link points inside your home"; do
  check "Browse shows '$want'" grep -qF "$want" "$C/screen-1.txt"
done
check "L says what it did" grep -qF "Took over 4. Left out 3 links with problems" "$C/screen-2.txt"
check "the Workspace has nothing to change" grep -qF "Saved. Nothing needs to change." "$C/screen-3.txt"
check "nothing on disk changed in the TUI" [ "$before" = "$(disk)" ]
got=$(jq -c '[.lighten.relocations[] | [.["source-path"], .["target-path"]]] | sort' "$C/config.json")
want=$(jq -nc --arg H "$H" --arg S "$S" '[[$H+"/.cache", $S+"/cache"], [$H+"/abs", $S+"/abs"], [$H+"/rel", $S+"/rel"], [$H+"/under", null]]')
check "the file has each link with where it points, and no target the roots derive ($got)" [ "$got" = "$want" ]
run plan
check "plan --json: all in sync ($(jq -c '[.relocations[].actions[].type]' "$out"))" \
  j '(.relocations | length) == 4 and all(.relocations[].actions[]; .type == "no-op") and (.blocked or .conflicts | not)'
converges
check "nothing on disk changed after plan and apply" [ "$before" = "$(disk)" ]
check "the links inside the linked parent stay" \
  [ "$(readlink "$S/cache/pip") $(readlink "$S/cache/uv-link")" = "../pip-elsewhere $S/uv-elsewhere" ]
end
mkdir -p "$results/take-over"
cp "$C"/*.* "$results/take-over/"
rm -rf "$short"

# --- the TUI and plan --json tell the same story

# Built under a short path, so no row is cut off at the list's width.
short=$(mktemp -d /tmp/lighten-e2e.XXXXXX)
begin parity "the Workspace rows match plan --json for one fixture with most states" "$short"
fill "$H/move"
mkdir -p "$L/sync" "$L/only" "$H/both" "$L/both" "$H/leave" "$L/leave" "$H/arch" "$L/arch" "$H/del" "$L/del" \
  "$H/keep" "$L/keep" "$C/elsewhere" "$H/unread"
ln -s "$L/sync" "$H/sync"
ln -s "$C/elsewhere" "$H/wrong"
echo f > "$H/file"
chmod 000 "$H/unread"
config "$(rel move)" "$(rel new)" "$(rel sync)" "$(rel only)" "$(rel both)" \
  "$(rel leave when-source-and-target-directories-exist=leave-unchanged)" \
  "$(rel arch when-source-and-target-directories-exist=adopt when-adopting-target=archive-source)" \
  "$(rel keep when-source-and-target-directories-exist=adopt when-adopting-target=discard-source)" \
  "$(rel del when-source-and-target-directories-exist=discard)" \
  "$(rel wrong)" "$(rel file)" "$(rel unread)"
run plan
plan=$out
run status
line=$(TERM=xterm-256color expect "$here/tui.exp" "$C/tui.log" "$binary" -c "$C/config.json" status)
check "TUI ran ($line)" [ $? -eq 0 ]
python3 "$here/render.py" "$C/tui.log" 140x50 > "$C/screen.txt"
parity=$(python3 - "$plan" "$out" "$C/screen.txt" <<'EOF'
import json, os, pwd, re, sys
plan, status, screen = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), open(sys.argv[3]).read()
# Rows show `~` for the home directory, which the binary takes from the account, not from $HOME.
home = pwd.getpwuid(os.getuid()).pw_dir
states = {r["sourcePath"]: r["state"] for r in status["relocations"]}

def badge(r):  # what plan --json says, in the Workspace's words
    types = [a["type"] for a in r["actions"]]
    if states[r["source"]] == "inaccessible": return "Can't read"
    if "blocked" in types: return "Blocked"
    if r.get("conflict") or r["outcome"] == "unresolved": return "Choose"
    for t, label in [("archive-directory", "Archive"), ("migrate-directory-for-publication", "Move"),
                     ("replace-directory-with-symlink", "Keep target"), ("delete-directory", "Delete"),
                     ("create-symlink", "Link"), ("replace-symlink", "Link")]:
        if t in types: return label
    if r["outcome"] == "unchanged": return "Left as is"
    return "In sync"

shown = {re.sub(r"^~", home, m.group(2)): m.group(1) for m in re.finditer(r"\[([A-Za-z' ]+)\] ([~/]\S*)", screen)}
diff = []
for r in plan["relocations"]:
    path = r["source"]
    want, got = badge(r), shown.get(path)
    # The Workspace hides relocations in sync until asked.
    if got != want and not (want == "In sync" and got is None):
        diff.append(f"{path}: json {want}, TUI {got}")
print("; ".join(diff) if diff else f"{len(plan['relocations'])} rows agree")
EOF
)
echo "  parity: $parity"
check "rows agree ($parity)" matches "$parity" 'rows agree$'
chmod 700 "$H/unread"
end
mkdir -p "$results/parity"
cp "$C"/*.* "$results/parity/"
rm -rf "$short"

# --- a user with no account entry

# The static musl binary cannot find a user who comes from LDAP or SSSD, so the JVM sets user.home
# to "?". A uid with no passwd entry does the same on any libc. Lighten then takes the home
# directory from HOME: ~ in the file, and Configuration on the first run (setup.exp).
stranger=12345
if ! sudo -n true 2> /dev/null || ! command -v setpriv > /dev/null; then
  skip no-account "needs passwordless sudo and setpriv to run as another uid"
elif getent passwd $stranger > /dev/null; then
  skip no-account "uid $stranger has a passwd entry"
else
  # Under /tmp, because the runner's home may not be open to other users.
  short=$(mktemp -d /tmp/lighten-e2e.XXXXXX)
  begin no-account "a uid with no passwd entry: ~ is HOME, and Configuration opens on the first run" "$short"
  mkdir -p "$H/app" "$H/.cache/JetBrains"
  cp "$binary" "$C/lighten"
  printf '{"lighten": {"target-root": "%s", "relocations": [{"source-path": "~/app"}]}}\n' "$L" > "$C/config.json"
  chmod -R a+rwX "$short"
  as_stranger="sudo -n setpriv --reuid=$stranger --regid=$stranger --clear-groups env HOME=$H TERM=xterm-256color"
  out=$C/plan.json
  $as_stranger "$C/lighten" -c "$C/config.json" plan --json > "$out" 2> "$out.err" < /dev/null
  status=$?
  check "plan exits 0 (exit $status: $(head -c 300 "$out.err"))" exits 0
  check "~ is HOME, got $(jq -c '.relocations[0] | [.source, .target]' "$out" 2> /dev/null)" \
    j --arg s "$H/app" --arg t "$L/app" '.relocations[0].source == $s and .relocations[0].target == $t'
  line=$(expect "$here/setup.exp" "$C/setup.log" "$H" "$L" $as_stranger "$C/lighten" -c "$C/new.json" init); tui=$?
  check "Configuration opens and browses the built-in list ($line)" [ $tui -eq 0 ]
  check "Configuration wrote no file" [ ! -e "$C/new.json" ]
  end
  mkdir -p "$results/no-account"
  cp "$C"/*.json "$C"/*.err "$C"/*.log "$results/no-account/" 2> /dev/null
  sudo -n rm -rf "$short"
fi

# --- summary

echo "e2e: $(cut -f1 "$matrix" | sort | uniq -c | awk '{printf "%s %s, ", $2, $1}' | sed 's/, $//')"
if [ ${#failed[@]} -gt 0 ]; then
  printf 'e2e failed: %s\n' "${failed[@]}" >&2
  exit 1
fi
