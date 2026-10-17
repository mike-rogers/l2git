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
