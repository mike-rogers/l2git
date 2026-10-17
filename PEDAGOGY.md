# Pedagogy

This is the record of a design discussion (2026-10-16) about how l2git
teaches, not just what it tests. It's a plan-of-record, not a work queue —
the work items that fell out of it are filed as GitHub issues and linked
below. Nothing here is implemented unless an issue says so.

## The hypothesis

From [Git for Engineers, part I][gfe1] (2014): *learning the fundamentals
of a tool is important to our understanding and correct use of the tool.*
The article framed it explicitly as an experiment. l2git is the test
apparatus.

The motivational corollary comes from [Dodging merge conflicts in
Git][dodging]: fear of merges drives teams to "introduce large swathes of
process to avoid conflicts … to the detriment of throughput." Process is
insurance; fluency makes the insurance unnecessary. That's the product's
"why bother" story — every rule a team added to avoid merges is a tax, and
this curriculum is the refund.

[gfe1]: https://mike-rogers.github.io/2014/09/16/git-for-engineers
[gfe2]: https://mike-rogers.github.io/2014/09/26/git-for-engineers-ii
[dodging]: https://beardedprogrammer.com/dodging-merge-conflicts-in-git/
[forkin]: https://beardedprogrammer.com/forkin/

## Audience

Programmers who have cloned a repo and pushed a commit before. Not rank
beginners. Consequences:

- Vocabulary like *commit*, *branch*, *remote* needs no ceremony.
- Internals content can go deep fast — "git is a content-addressed
  key/value store" lands immediately with people who own hash maps and
  checksums.
- `add-readme` is a warm-up / toolchain check, not a lesson. Every course
  needs a hello-world.

## Sequencing: experience first, theory immediately after

The 2014 articles are *model-first* (object store → blobs/trees/commits →
commands). l2git as built is *situation-first* (get into trouble, get out,
feel the floor hold). Same conviction, opposite orderings. We resolve it
with a third sequence that beats both:

> **The scenario creates the question; the model answers it — right
> after.**

Up-front theory bounces off people who have nowhere to put it. But someone
who just watched `git push` get refused is *starving* for the model that
explains it. The moment right after the ★ is peak receptivity: "here's why
that worked" lands as revelation, not lecture.

(In Kolb's terms: scenarios give concrete experience + active
experimentation; the instruments below add reflective observation and
abstract conceptualization. But we don't need the jargon — the beat is
*do the thing, then immediately learn why it worked*.)

## The instruments

Four mechanisms, designed to compose:

### 1. Timeline scrubber — the reflection instrument

A per-scenario, event-based timeline of what happened to the *remote*: a
point when the learner cloned, a point when Pat pushed, a point when the
learner's push was rejected, a point when the merged/rebased push landed.
Scrubbing shows the remote's commit graph at each point.

Why it's load-bearing: the hardest thing about distributed git is that the
remote changes *when you aren't looking*, due to *someone else*, and you
only discover it as a cryptic rejection. The scrubber makes the invisible
interleaving visible as a narrative. The rejected-push point shows an
**unchanged graph** — which *is* the lesson about what rejection means.

Honest edge, reframed as a feature: the timeline is the remote's
biography, not the learner's. The server never saw the local rebase or the
staging choices — it only knows what arrived over the wire. Teaching that
boundary ("here is exactly what the remote knows about you") is itself one
of the concepts.

Feasibility note: commits are immutable and `InMemoryRepository` never
GCs, so historical graph states are just **ref snapshots per event** — a
tiny branch→hash map stamped onto events we already record. Even
force-pushed-over commits stay renderable (ghosted). **The UI can wait
indefinitely; the data can't** — any event recorded without a ref snapshot
is unreplayable forever. Stamping snapshots is the one time-sensitive item
from this whole discussion.

### 2. X-ray view — the articles' diagrams, made real

Click a commit, see its tree; click into blobs, with real hashes — the
learner's *own* objects. "You changed one file; notice your two commits
point at the **same blob** for `welcome.txt` — same content, same hash,
stored once." Content-addressing taught as an observation, not an
assertion.

Composes with the scrubber: time is one axis, depth (commit→tree→blob) is
the other. Scrub from commit A to commit B and watch the tree hash change
while one blob hash holds still.

The killer frame for rewrites: after a rebase or amend, show the old
commits **ghosted** on the graph — still in the object store, just
unreachable. That single visual is the entire fear-removal thesis grounded
in the object model: *git never deletes; it only moves labels.* The reflog
stops being magic.

**Caveat: the x-ray shows pushed objects only.** No mainstream GUI client
(GitKraken, Sourcetree, Fork, Tower, GitHub Desktop, GitLens) exposes
anything below the commit — no tree or blob hashes, no raw-object view;
the gap is filled only by one-off educational visualizers (gitviz,
Goslings, PyGitViz, git-plumber…), which is evidence the need is real and
unmet. Two consequences:

- A GUI user cannot verify x-ray claims *in their client* — so l2git is
  the first GUI they'll have that shows internals, and the verification
  anchor is the **commit SHA**, which every client does show. If the
  commit hash in their GUI matches the x-ray's, everything beneath (tree,
  blobs) is mathematically agreed, because the commit hash contains them.
  That's not a workaround; teaching it *is* teaching content-addressing as
  a trust argument. CLI users get an optional debrief rung — "run
  `git cat-file -p <their actual hash>` in your clone; you'll see the same
  tree" — as a bonus path, never the required one.
- The x-ray can't show local, unpushed state (a GUI user can't inspect
  that level at all), so x-ray explanations must anchor on what crossed
  the wire. Conveniently, that's the epistemological boundary we already
  teach: *the server only knows what you push.* The scrubber should render
  the learner's local work as an explicit, labeled gap, not pretend it
  isn't happening.

One sharpening of "pushed", discovered while planning: a **rejected** push
has still *transmitted* its objects — receive-pack unpacks the pack before
the ref update is refused, so the learner's commit is sitting in the
remote's object store with no label pointing at it. Rejection is about
*refs*, not objects. That makes the rejected-push beat x-rayable (see the
worked example below), and it means event records should capture the
attempted new-ids of rejected commands (noted on #8).

### 3. Debriefs — the fourth beat after the hint ladder

Hints go nudge → command → explanation, all *before* success. A `:debrief`
is the voice of *after*: shown post-★ (linked from the banner), explaining
what just happened in object-model terms, ideally with the learner's own
hashes spliced in — an *annotated replay* over the scrubber, not a wall of
text.

This is also where explanation-only content lives — true, important, and
not verifiable by push: "the stage is never empty," the `.git/index` file,
the zeroed-index war story from [part II][gfe2].

### 4. Concept codex — the durable reference

The scenario tree is the spine (progression). Concepts are a cross-cutting
index (reference) — textbooks have both chapters and an index for a
reason. Each concept is a stable, named page ("The Object Store," "The
Index," "Refs Are Labels") that scenarios *link into* rather than
*contain*. You meet "The Index" inside a scenario's debrief; it
simultaneously appears in a Concepts panel, permanently, with a backlink
to where you met it.

This is the games-industry codex/bestiary pattern: entries unlock through
encounters, then remain browsable forever. It answers "which scenario was
the index one?" without requiring the learner to remember scenario titles.
Concept pages can embed a canned mini-timeline/x-ray as a reusable
demonstration, independent of live session state.

Written in the articles' voice: first-person, self-deprecating,
footnote-heavy, tool-agnostic. The shared-vocabulary goal from the
articles is a product principle — learners should leave able to talk git
with people on any client.

## Worked example: teammate-pull, scrubbed and x-rayed

The scenario with the most legs for the first scrubber + x-ray build.
Why: its setup already contains the same-blob demo (Pat's commit leaves
`welcome.txt` untouched **by design**), its event sequence is the richest
narrative in the catalog (clone, ambush, rejection, integration, and
optionally a force-push/restore detour), and it's a basic scenario — the
x-ray debuts where most learners actually are. The timeline is the
learner's *actual* session, so no two replays are identical; the debrief
is personal by construction.

The beats, as timeline points:

**T0 — seeded.** One commit (`seed`) on `main`. X-ray: commit → tree →
`welcome.txt` blob, hashes visible. The quietest possible introduction to
"a commit is a pointer to a tree is a pointer to blobs."

**T1 — you cloned.** Graph unchanged. Annotation: "the remote now knows
you exist — and that's *all* it will learn until you push." Immediately
after this point the scrubber renders a **labeled gap**: "somewhere in
here, you worked. The remote can't see it." The epistemology caveat made
visible instead of awkward.

**T2 — Pat pushed (the ambush).** `pat-1` appears on `main`. **X-ray
moment #1:** Pat's tree has two entries — `pat-notes.md` (new blob) and
`welcome.txt` pointing at **the same blob hash as seed's**. Pat didn't
touch it; identical content, identical hash, stored once. The UI should
visually link the shared blob node between both trees. This is the
articles' content-addressing lesson as an observation.

**T3 — your push was rejected.** The refs didn't move — the graph looks
exactly like T2, and that *is* the lesson ("a rejection means the label
didn't move"). But **x-ray moment #2:** the learner's commit is in the
object store anyway — the pack was unpacked before the ref update was
refused. Render it ghosted: "your commit *arrived*; what was refused was
moving the `main` label to it. Rejection is about refs, not objects —
nothing you make in git really gets lost." The fear-removal thesis,
planted at the first rejection the learner ever experiences.

**T4 — integrated and pushed.** Two endings, both correct (the checks are
strategy-agnostic):

- *Merge:* a merge commit with two parents joins the lines; its tree
  holds all three files; `welcome.txt` still rides the same blob through
  every commit on screen.
- *Rebase:* a **new** commit lands on top of `pat-1` — different hash,
  different parent, same `standup.md` blob — and the T3 ghost **stays
  ghosted forever**: the pre-rebase original, orphaned. X-ray moment #3:
  compare the ghost and its rebased twin side by side. "Rebase doesn't
  move commits; it re-creates them and abandons the originals — and the
  originals are still right there."

If the learner force-pushed over Pat along the way, the timeline honestly
includes it: Pat's restore event, the clobbered commit ghosting out. The
messy path produces a *richer* debrief, which is exactly the right
incentive structure.

What this build needs (all tracked): ref snapshots per event (#8) for the
time axis; attempted new-ids of rejected commands recorded on push events
(#8, noted) so T3's ghost is identifiable; a read-only object-inspection
endpoint (commit/tree/blob pretty-printed, JGit has it all) and the
scrubber/x-ray UI (#9).

## Source material map

| Source | Feeds |
|--------|-------|
| [Git for Engineers I][gfe1] — object model, content addressing, checkout-as-tree-walk | x-ray view; "The Object Store" / "Commits/Trees/Blobs" codex entries |
| [Git for Engineers II][gfe2] — three buckets, "the stage is never empty," the index file, zeroed-index disaster | staging scenarios; "The Index" codex entry; a revert-the-catastrophe scenario (the blast radius as situation, the index mechanics as debrief) |
| [Dodging merge conflicts][dodging] — process-as-tax, `rebase --onto`, reflog rescue | product framing; merge-fear scenario family; advanced rebase scenarios |
| [Forkin'][forkin] — upstream/fork hygiene | forks/upstream scenarios (needs multi-repo sessions, see #6) |

Staging is more teachable than it first looks: staging *choices* are
visible in pushed trees. "Change both files, commit **only** `notes.md`"
is checkable server-side — that's `add`'s selectivity, the actual point of
the stage. Later, `add -p`. The parts that aren't push-visible go in
debriefs.

## Decided vs. open

Decided (this discussion):
- Audience floor: has cloned and pushed before.
- Sequencing: experience first, theory immediately after.
- Concepts get their own index (codex), separate from the scenario spine.
- Ref snapshots on events should start **soon** — data collection can't be
  retroactive, even though every UI built on it can wait.

Open:
- Concept unlocks: strict (invisible until earned) or soft (listed but
  grayed)? Leaning **soft** — adults resent locked doors.
- Does the scrubber live on the scenario card or in the debrief view?
- Do debriefs reuse the articles' actual text as seed content, or fresh
  writing in the same voice?
- X-ray: always-on depth for everyone, or opt-in for the curious?
