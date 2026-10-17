import type { SessionEvent } from "./types";

/** One scrubbable moment in the remote's history. The timeline is the
 * remote's biography, not the learner's: it only has points where the
 * remote learned something (a fetch, a teammate's move, a push attempt).
 * The learner's local work happens in the gaps — which is itself part of
 * the lesson, so the gap after a fetch gets named in the annotation. */
export interface TimelinePoint {
  icon: string;
  label: string;
  /** Extra context shown when the point is selected. */
  note?: string;
  at: string;
  refs: Record<string, string>;
  /** Commits in the object store that no branch reached at this moment —
   * rejected pushes accumulate here, and stay here. */
  ghosts: string[];
}

/** Build the scrubber's points for one scenario from the session's event
 * log. Points before the latest reset are dropped: a reset discards the
 * repo, so their snapshots name objects that no longer exist. */
export function buildTimeline(events: SessionEvent[], scenarioId: string): TimelinePoint[] {
  const mine = events.filter((e) => e.scenario === scenarioId);
  const lastReset = mine.map((e) => e.type).lastIndexOf("reset");
  const visible = lastReset >= 0 ? mine.slice(lastReset + 1) : mine;

  const points: TimelinePoint[] = [];
  // Ghost *candidates*: hashes that might be unreachable now or at some
  // point — rejected pushes' attempted commits, plus every branch tip any
  // snapshot ever saw (so a force-push-clobbered commit still renders,
  // dashed). The server marks reachable ones non-ghost, so over-offering
  // is harmless.
  const ghosts: string[] = [];
  const offer = (sha: string) => {
    if (!ghosts.includes(sha)) ghosts.push(sha);
  };
  let fetched = false;

  for (const e of visible) {
    if (!e.refs) continue;
    Object.values(e.refs).forEach(offer);
    switch (e.type) {
      case "fetch":
        points.push({
          icon: "⇣",
          label: fetched ? "you fetched" : "you cloned",
          note: "From here the remote can't see you: your commits, your stage, your working directory all live on your machine until you push.",
          at: e.at,
          refs: e.refs,
          ghosts: [...ghosts],
        });
        fetched = true;
        break;
      case "teammate":
        points.push({
          icon: "⚡",
          label: e.message ?? "a teammate acted",
          at: e.at,
          refs: e.refs,
          ghosts: [...ghosts],
        });
        break;
      case "push": {
        const cmds = e.commands ?? [];
        const rejected = cmds.filter((c) => c.result !== "OK");
        rejected.forEach((r) => offer(r.new));
        points.push(
          rejected.length > 0
            ? {
                icon: "✗",
                label: "your push was rejected",
                note: "Notice the branches didn't move — that's what a rejection means. But your commit did arrive: it's in the object store with no label pointing at it, shown dashed below.",
                at: e.at,
                refs: e.refs,
                ghosts: [...ghosts],
              }
            : {
                icon: "⇡",
                label: "you pushed",
                at: e.at,
                refs: e.refs,
                ghosts: [...ghosts],
              },
        );
        break;
      }
      default:
        break; // step/evaluation events are coaching, not remote history
    }
  }
  return points;
}

/** Every ghost the scenario has accumulated — shown on the live graph too,
 * so a rebased-away original stays visible (dashed) forever. */
export function allGhosts(points: TimelinePoint[]): string[] {
  return points.length > 0 ? points[points.length - 1].ghosts : [];
}

export default function Timeline({
  points,
  selected,
  onSelect,
}: {
  points: TimelinePoint[];
  /** Index into points, or null for "live". */
  selected: number | null;
  onSelect: (i: number | null) => void;
}) {
  if (points.length === 0) return null;
  const sel = selected !== null ? points[selected] : null;
  return (
    <div className="timeline">
      <div className="tl-track">
        {points.map((p, i) => (
          <button
            key={`${p.at}-${i}`}
            className={`tl-dot${selected === i ? " selected" : ""}`}
            title={`${p.label} · ${new Date(p.at).toLocaleTimeString()}`}
            onClick={() => onSelect(selected === i ? null : i)}
          >
            {p.icon}
          </button>
        ))}
        <button
          className={`tl-dot live${selected === null ? " selected" : ""}`}
          title="now"
          onClick={() => onSelect(null)}
        >
          ●
        </button>
      </div>
      {sel && (
        <div className="tl-note">
          <strong>
            {sel.icon} {sel.label}
          </strong>
          <span className="tl-when">{new Date(sel.at).toLocaleTimeString()}</span>
          {sel.note && <p>{sel.note}</p>}
          <button className="tl-live-btn" onClick={() => onSelect(null)}>
            back to live →
          </button>
        </div>
      )}
    </div>
  );
}
