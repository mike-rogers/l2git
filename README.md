# l2git

**Learn git by using real git.** l2git is a web-based workshop that teaches
git through progressively harder situations — not a simulator, not a cartoon
of git, but your own git client talking to a real remote that happens to be
watching, coaching, and occasionally sabotaging you on purpose.

You open a lesson in the browser, clone the repository it gives you, and do
the work in your own terminal or GUI client. When you push, the server
evaluates what actually arrived — the objects, the history, the files — and
answers right there in your terminal:

```
remote: ✓ Make your move — Pat pushed while you were working, and git
remote:   refused your push. That's a collision, not a catastrophe.
remote: → next · step 2/2 · Integrate and push again
```

Meanwhile the browser shows the remote's commit graph **live**: push from
your terminal and watch the node appear, the branch label slide, the
scenario tick complete.

## Why

Most people never get an intuition for git's internals. They learn a handful
of incantations, and the day a rebase goes sideways or HEAD detaches, they're
helpless — and modern tooling makes it worse, not better: when an IDE button
or an AI agent does your committing, you get even less practice than the
subversion refugees of yesteryear did. The gap isn't knowledge of commands;
it's **confidence in bad situations**.

So that's what l2git teaches. The curriculum is built from trouble: a
teammate pushes while you work, a force-push eats someone's commit, a branch
needs rebasing before it can land. You get into the situation for real, with
real git, and you get out of it — and the fear goes away, because you've
seen the floor and it held.

## How it works

- **Real git, your client.** The server speaks git's smart-HTTP protocol, so
  any client works — CLI, IDE, GUI. Session tokens ride in the clone URL; no
  accounts, no credentials. Bookmark the page and your session is yours.
- **Scenarios are situations, not quizzes.** Each lesson seeds a repository
  with history (fresh timestamps, generated per session) and defines what a
  good outcome looks like. Multi-step lessons advance as you work; each step
  has a hint ladder — a nudge, then the command, then the full explanation —
  revealed only if you ask.
- **The remote is alive.** Scenarios can react to you: a teammate lands a
  commit mid-push and collides with yours; force-push over his work and
  he'll notice and push it back. Rejections are narrated, mistakes are
  recoverable, and every scenario has a reset button.
- **Evaluation looks at what you actually pushed.** History and topology are
  checked for real — including whether you *actually rebased* (the old
  commits must be gone) versus merged and hoped nobody would notice.

## Current curriculum

| Level | Scenario | What it teaches |
|-------|----------|-----------------|
| Basic | Your first push | clone → commit → push, the whole loop |
| Basic | A teammate pushed first | diverged history: the rejected push, pull/rebase, why force-push eats work |
| Basic | Work on a branch | branch, build, land: push a feature branch, then fast-forward it into main |
| Intermediate | Rebase before you merge | rebase a stale branch onto moved main, force-push *your own* branch safely, land it linear |

More is planned — tagging, reflog rescue, force-push regret, bisect,
`rebase --onto`, fork/upstream workflows — tracked in the
[issues](../../issues) and the curriculum checklist in
[#6](../../issues/6). How the tool *teaches* — the sequencing philosophy,
the planned timeline/x-ray/debrief/codex instruments — lives in
[PEDAGOGY.md](PEDAGOGY.md).

## Status

Early and moving fast. Sessions are in-memory (a server restart forgets
them), there's no hosted instance yet, and the curriculum is four scenarios
deep. If you want to run it, build on it, or try to break it — especially
try to break it — see [DEVELOPER.md](DEVELOPER.md).
