import { useEffect, useMemo, useState } from "react";
import { getObjects, type ObjectEntry } from "./api";
import { allGhosts, buildTimeline } from "./Timeline";
import type { SessionEvent } from "./types";

/** The under-the-hood popover: a video-style scrubber over the remote's
 * history (chapter markers at their real moments in time), and the object
 * database's full contents at whichever moment is selected. The main area
 * is deliberately empty for now — the object detail/relationship view is
 * its own design pass. */

interface ViewPoint {
  icon: string;
  label: string;
  at: string | null; // null = the live "now" point
  refs?: Record<string, string>;
  ghosts: string[];
}

const OTYPE_ICON: Record<ObjectEntry["otype"], string> = {
  commit: "◉",
  tree: "▦",
  blob: "≡",
};

export default function Inspector({
  token,
  scenarioId,
  events,
  onClose,
}: {
  token: string;
  scenarioId: string;
  events: SessionEvent[];
  onClose: () => void;
}) {
  const viewPoints: ViewPoint[] = useMemo(() => {
    const pts = buildTimeline(events, scenarioId);
    return [
      ...pts.map((p) => ({ icon: p.icon, label: p.label, at: p.at, refs: p.refs, ghosts: p.ghosts })),
      { icon: "●", label: "now", at: null, ghosts: allGhosts(pts) },
    ];
  }, [events, scenarioId]);

  // Default to "now", the last point.
  const [idx, setIdx] = useState(viewPoints.length - 1);
  const [wasAtEnd, setWasAtEnd] = useState(true);
  const [objects, setObjects] = useState<ObjectEntry[] | null>(null);
  const [selectedSha, setSelectedSha] = useState<string | null>(null);

  // New events grow the timeline; stay pinned to "now" if that's where
  // the user was, otherwise hold their place.
  useEffect(() => {
    if (wasAtEnd || idx >= viewPoints.length) setIdx(viewPoints.length - 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewPoints.length]);

  const select = (i: number) => {
    setIdx(i);
    setWasAtEnd(i === viewPoints.length - 1);
  };

  const vp = viewPoints[Math.min(idx, viewPoints.length - 1)];

  useEffect(() => {
    let live = true;
    setObjects(null);
    getObjects(token, scenarioId, { refs: vp.refs, extra: vp.ghosts })
      .then((o) => live && setObjects(o))
      .catch(() => live && setObjects([]));
    return () => {
      live = false;
    };
  }, [token, scenarioId, vp]);

  // Chapter positions: proportional to real elapsed time, like a video
  // scrubber — two pushes a second apart sit together; the quiet half
  // minute between them is visible as empty track. The "now" point is
  // frozen a fixed beat after the latest event rather than at wall-clock
  // now, or an idle hour would crush the whole story against the left edge.
  const positions = useMemo(() => {
    const real = viewPoints.filter((p) => p.at).map((p) => Date.parse(p.at as string));
    const liveT = real.length > 0 ? real[real.length - 1] + 30_000 : Date.now();
    const times = viewPoints.map((p) => (p.at ? Date.parse(p.at) : liveT));
    const t0 = times[0];
    const span = Math.max(1, times[times.length - 1] - t0);
    return times.map((t) => ((t - t0) / span) * 100);
  }, [viewPoints]);

  return (
    <div className="inspector-overlay" onClick={onClose}>
      <div
        className="inspector"
        role="dialog"
        aria-label="the object database"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="inspector-head">
          <h3>
            <code>origin</code>, under the hood
          </h3>
          <button className="panel-toggle" onClick={onClose} aria-label="close inspector">
            ×
          </button>
        </header>

        <div className="inspector-body">
          <aside className="obj-list">
            <div className="obj-list-head">
              objects{objects ? ` · ${objects.length}` : ""}
            </div>
            {objects === null ? (
              <p className="muted panel-empty">reading the database…</p>
            ) : (
              <ul>
                {objects.map((o) => (
                  <li key={o.sha}>
                    <button
                      className={`obj-row${selectedSha === o.sha ? " selected" : ""}`}
                      onClick={() => setSelectedSha(o.sha === selectedSha ? null : o.sha)}
                      title={o.sha}
                    >
                      <span className={`obj-icon ${o.otype}`}>{OTYPE_ICON[o.otype]}</span>
                      <code>{o.sha.slice(0, 7)}</code>
                      <span className="obj-type">{o.otype}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </aside>
          <section className="obj-main">
            <p className="muted">
              {selectedSha
                ? `object ${selectedSha.slice(0, 7)} — detail view coming soon`
                : "every object the database holds at this moment, keyed by hash — pick a moment below, an object on the left"}
            </p>
          </section>
        </div>

        <footer className="scrubber">
          <div className="scrub-buttons">
            <button onClick={() => select(Math.max(0, idx - 1))} disabled={idx === 0} aria-label="previous event">
              ◀
            </button>
            <button
              onClick={() => select(Math.min(viewPoints.length - 1, idx + 1))}
              disabled={idx === viewPoints.length - 1}
              aria-label="next event"
            >
              ▶
            </button>
          </div>
          <div className="scrub-track">
            <div className="scrub-rail" />
            <div className="scrub-fill" style={{ width: `${positions[idx] ?? 100}%` }} />
            {viewPoints.map((p, i) => (
              <button
                key={`${p.at ?? "now"}-${i}`}
                className={`scrub-marker${i === idx ? " selected" : ""}${p.at ? "" : " live"}`}
                style={{ left: `${positions[i]}%` }}
                onClick={() => select(i)}
                title={`${p.label}${p.at ? ` · ${new Date(p.at).toLocaleTimeString()}` : ""}`}
              >
                {p.icon}
              </button>
            ))}
          </div>
          <div className="scrub-info">
            <strong>
              {vp.icon} {vp.label}
            </strong>
            <span className="muted">
              {vp.at ? new Date(vp.at).toLocaleTimeString() : "live"}
            </span>
          </div>
        </footer>
      </div>
    </div>
  );
}
