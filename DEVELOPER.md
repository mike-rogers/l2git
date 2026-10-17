# Developing l2git

Everything here works on macOS, Linux, and Windows. The backend is Clojure
(JVM) and the frontend is TypeScript/React built with Vite. Nothing touches
disk at runtime — scenario repositories live in memory — so there's no
database or state to set up.

## Fastest path: a container

If you have any Docker-compatible runtime (Docker Desktop, Docker in WSL2,
podman, colima), you need nothing else — not Java, not Clojure, not Node:

```sh
docker compose up --build        # or: docker build -t l2git . && docker run --rm -p 8070:8070 l2git
```

Then open <http://localhost:8070> and skip straight to
[the exploratory-testing loop](#the-exploratory-testing-loop). The image
builds the frontend and warms the backend's dependency cache, so the first
build takes a few minutes and later ones are quick. Clone URLs are derived
from how you reached the server, so a different port mapping
(`-p 9000:8070`) or crossing the WSL boundary both just work.

The container path is for *using and testing* l2git. For working on its
code, a local toolchain (below) gives you fast restarts and hot reload.

Two gotchas for Homebrew + colima setups (Docker Desktop and WSL's
docker.io package are unaffected): brew's `docker-compose` must be linked
before `docker compose` (as a subcommand) works —
`mkdir -p ~/.docker/cli-plugins && ln -sfn $(brew --prefix)/opt/docker-compose/bin/docker-compose ~/.docker/cli-plugins/docker-compose`
— and if an old Docker Desktop left `"credsStore": "desktop"` in
`~/.docker/config.json`, builds fail with `docker-credential-desktop:
executable file not found`; delete that line.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | Any distribution. [Temurin](https://adoptium.net/) is a safe pick. |
| Clojure CLI | latest | Install per platform below. |
| Node.js | 18+ | Only needed if you're touching the frontend. |
| git | any recent | You'll be using it as the "learner" constantly. |

**macOS** — `brew install temurin clojure/tools/clojure node`
(if Homebrew's `openjdk` formula instead of the Temurin cask, note it's
keg-only: put `$(brew --prefix openjdk)/bin` on your `PATH`).

**Linux** — JDK and Node from your package manager; Clojure CLI via the
[official installer script](https://clojure.org/guides/install_clojure).

**Windows** — `winget install EclipseAdoptium.Temurin.21.JDK OpenJS.NodeJS.LTS`
(or use [scoop](https://scoop.sh/)). For the Clojure CLI, follow
[clojure.org's Windows instructions](https://clojure.org/guides/install_clojure#_windows_instructions);
the scoop route (`scoop install clj-deps` or the community `deps.clj`
launcher) tends to be the least painful. Everything below that says
`clojure -M:run` works the same in PowerShell once the CLI is on your PATH.

Verify: `java -version` reports 17+, `clojure --version` prints something,
`node --version` reports 18+.

## Run it

```sh
clojure -M:run
```

First run downloads dependencies, then: `l2git listening on http://localhost:8070`.
The server serves the pre-built frontend from `web/dist` — if that folder
doesn't exist yet (fresh clone), build it once:

```sh
cd web
npm install
npm run build
```

Then open <http://localhost:8070> — a session is created automatically and
its token goes into the URL. **Bookmark that URL**; the token *is* the
session. Sessions live in server memory, so a restart forgets them — just
reload the page to mint a fresh one.

## The exploratory-testing loop

This is the whole job, and it's fun:

1. Open the page, pick a scenario in the tree.
2. Copy the clone command, clone in a terminal (any folder).
3. Do the scenario — or deliberately *don't*. Push wrong things, push to
   weird refs, pull at strange times, force-push, delete branches, try to
   reach the ✓ without doing the lesson.
4. Watch three places at once: your terminal's `remote:` lines, the live
   graph in the browser, and the Activity feed.
5. The **reset scenario** button on the card restores the origin to its
   starting state (bit-identical — same hashes). Your local clone still has
   your old mess; delete it and re-clone.

If you complete a scenario *without actually learning/doing the thing it
teaches*, that's a bug — the best kind. File it on
[issue #7](../../issues/7) (the exploit log) or as its own issue, with the
exact command sequence. Three exploits have been found this way so far;
they're all documented in #7 and make good inspiration.

Useful endpoints for poking (also how the UI works — watch the Network tab):

```sh
curl -s -X POST localhost:8070/api/session                      # new session
curl -s localhost:8070/api/session/<token>                      # state + event log
curl -s localhost:8070/api/session/<token>/graph/<scenario-id>  # the DAG the UI draws
#   ?refs=main=<sha>   render as of a past ref snapshot (events carry them)
#   ?extra=<sha>,...   include unreachable tips too, marked "ghost"
curl -s localhost:8070/api/session/<token>/object/<scenario-id>/<sha>  # x-ray any object
curl -s -N localhost:8070/api/session/<token>/events            # live SSE stream
```

One global git note: if your git config signs commits (`commit.gpgsign true`)
and your key wants a passphrase, scripted/experimental commits get annoying;
`git -c commit.gpgsign=false commit …` disables it per-command.

## Frontend development

For UI work, run Vite's dev server alongside the backend:

```sh
cd web
npm run dev        # http://localhost:5173, hot reload
```

It proxies `/api` and `/r` (the git transport) to `:8070`, so sessions,
SSE, and even `git clone http://localhost:5173/r/...` work against the dev
server. When you're done, `npm run build` regenerates `web/dist` for the
backend to serve.

## Backend development

Code layout:

| namespace | role |
|-----------|------|
| `l2git.core` | Ring routing, session API, static files, SSE, Jetty bootstrap |
| `l2git.git-http` | smart-HTTP endpoints (`info/refs`, upload-pack, receive-pack), puppeteering triggers, evaluation hook |
| `l2git.repo-gen` | in-memory repo construction (JGit `ObjectInserter`, `InMemoryRepository`) |
| `l2git.scenarios` | the scenario DSL: loads EDN from `resources/scenarios/`, compiles to runtime hooks, step engine |
| `l2git.session` | token-keyed session state, step progress, event log, lazy repo materialization |

There's no hot-reload wired up for server code or scenario EDN — restart
`clojure -M:run` after changes (it's a few seconds), or run a REPL
(`clojure -M:repl`, then `(require 'l2git.core)` and
`(l2git.core/start!)`) and `(require '… :reload)` namespaces as you edit.
Note the scenario catalog is loaded when `l2git.scenarios` loads, so EDN
edits need that namespace reloaded (or a server restart) to take effect.

## Authoring scenarios

Scenarios are data: EDN files in `resources/scenarios/`, listed in
`index.edn` (which controls catalog order). A scenario declares:

- `:setup` — seed commits, with names (`:seed`, `:pat-1`) whose hashes
  become the session's "known" map, timestamps relative to session creation
  (`[:hours-before 2]`), optional `:parent`/`:branch` for shaping the graph.
- `:steps` — ordered goals, each with `:title`, `:instructions`, a `:hints`
  ladder (nudge → command → explanation), `:checks` (each a condition plus
  its failure message), and a `:pass` message. Steps advance monotonically
  and cascade. (`:evaluate {:checks … :pass …}` is single-step sugar.)
- Optional puppeteering: `:before-push` (fires mid-push, only when the push
  targets its `:branch` — how Pat collides with you), `:after-fetch`, and
  `:after-evaluate` (react to outcomes — how Pat restores clobbered work).

Conditions are tagged vectors: `[:reachable :pat-1 "main"]`,
`[:tip-is :seed "main"]`, `[:branch-merged "feature/x" "main"]`,
`[:file-nonblank "main" "standup.md"]`, `[:and …]`/`[:or …]`/`[:not …]`, and
more — **the docstring of `l2git.scenarios` is the authoritative DSL
reference.** The existing EDN files are the best templates; `teammate-pull.edn`
shows puppeteering, `rebase-before-merge.edn` shows multi-branch setup and
verifying a rebase by hash absence.

Design rules learned the hard way:

- Prefer *reachability* checks over direct-parent ones, unless the lesson is
  about rewriting — learners fix mistakes with extra commits.
- Seeded commits are known hashes; learner commits can only be checked
  semantically (files, topology), never by hash.
- For every trigger, ask what *unintended* interaction could fire it (fetch,
  empty push, side-branch push, ref delete…) — see #7's pattern.
- Evaluation runs loudly on push and quietly on fetch (complete-on-pass
  only), so a lesson whose goal state arrives by *pulling* still completes.
