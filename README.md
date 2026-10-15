# l2git

Learn git by using **real git** against a real remote. The server generates
scenario repositories in memory — per session, per scenario, with fresh
timestamps — serves them over git's smart-HTTP protocol, and evaluates what
you push, talking back to your terminal as `remote:` lines.

## Design principles

- **Real git, your client.** Learners use their own CLI or GUI; the server is
  a genuine git remote over HTTP. Token-in-URL means no credential prompts.
- **No repos on disk.** Repos are pure functions of
  `(scenario, token, session-created-at)`, built with JGit's in-memory DFS
  backend and materialized lazily on first contact. Clone the first scenario
  in 2035 and the commits are dated 2035.
- **Two-mode evaluation.** Seeded commits have hashes computed at session
  creation (stored in session metadata), so history-rewriting lessons check
  presence/absence/reachability of known hashes; learner-authored commits are
  checked semantically (tree contents, topology, messages). Prefer
  *reachability* assertions over *direct-parent* ones unless the lesson is
  about rewriting.
- **Own the request cycle.** The smart-HTTP endpoints are hand-rolled Ring
  handlers driving JGit's `UploadPack`/`ReceivePack` (no GitServlet). That
  buys fetch observation, lazy materialization, in-process evaluation, and
  server-side "teammate" puppeteering.
- **Fear-removal is the product.** Scenario-first lessons in the voice of
  beardedprogrammer.com: concrete graphs, recoverable mistakes, reflog as
  the safety net.

## Running

Requires Java 17+ and the Clojure CLI. On this machine, OpenJDK is the
unlinked Homebrew formula, so:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
clojure -M:run        # listens on http://localhost:8070
```

Then, as a learner:

```sh
curl -s -X POST http://localhost:8070/api/session
# -> {"token":"...", "clone-url":"http://localhost:8070/r/<token>/add-readme.git", ...}
git clone http://localhost:8070/r/<token>/add-readme.git
# ...create README.md, commit, push — evaluation arrives as remote: lines
curl -s http://localhost:8070/api/session/<token>   # status + event log
```

## Layout

| namespace        | role |
|------------------|------|
| `l2git.core`     | Ring routing, session API, Jetty bootstrap |
| `l2git.git-http` | smart-HTTP endpoints (`info/refs`, upload-pack, receive-pack), evaluation hook |
| `l2git.repo-gen` | in-memory repo construction (blobs/trees/commits via `ObjectInserter`) |
| `l2git.scenarios`| the scenario DSL: loads EDN from `resources/scenarios/`, compiles to runtime hooks |
| `l2git.session`  | token-keyed session state, event log, lazy repo materialization |

## Authoring scenarios

Scenarios are EDN files in `resources/scenarios/` (ordered by `index.edn`).
A scenario declares `:setup` (seed commits, with timestamps relative to
session creation like `[:hours-before 2]`), `:evaluate` (ordered checks,
each a condition plus its failure message, and a pass message), and two
optional puppeteering hooks: `:after-fetch` (e.g. a teammate pushes once
the learner has cloned; `:once` keys make it idempotent) and
`:after-evaluate` (react to outcomes, e.g. restore force-push-clobbered
work). Conditions are tagged vectors (`[:reachable :pat-1 "main"]`,
`[:file-nonblank "main" "standup.md"]`, `[:and …]`/`[:or …]`/`[:not …]`);
file sets are literal maps or expressions (`[:merge [:files-at "main"] {…}]`).
The docstring of `l2git.scenarios` is the DSL reference. Evaluation runs
loudly on push (feedback as `remote:` lines) and quietly on fetch
(complete-on-pass only), so lessons that finish by pulling still complete.

Scenarios are resettable: discard the session's entry and the repo
regenerates bit-identically (same seed hashes) from
`(scenario, token, session-created-at)`. Local-clone recovery coaching
should be level-appropriate (not yet implemented): basic scenarios say
"delete your clone and re-clone"; intermediate/advanced say
`git fetch` + `git reset --hard origin/main` — correct because the
regenerated origin is deterministic, and itself a reset lesson.

## Roadmap

- [x] Spine: generate → clone → push → evaluate → feedback in terminal
- [x] SSE progress events to the browser (replaces original's webhooks)
- [x] Web UI (TypeScript): instructions, live graph, scenario tree
  (Basic | Intermediate | Advanced), activity feed, reset
- [x] EDN scenario format with templated timestamps, condition checks,
  and puppeteering hooks
- [ ] Scenario curriculum: tagging → rebase, squash, amend-author →
  bad merge, lost commit + reflog rescue, force-push regret, bisect →
  `rebase --onto` after squash-merge, forks & upstream (two remotes/session)
- [x] Multi-step scenarios (ordered steps with cascading progress) + hint ladders
- [ ] Prerequisites/gating between scenarios (pinned product decision)
- [ ] Session persistence (regenerate repos from metadata) + hosted deploy
- [ ] Advanced: git-lfs scenario (needs an LFS batch-API endpoint beside the
  git transport) — backlog
