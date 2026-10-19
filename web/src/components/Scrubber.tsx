import { useMemo } from "react";
import type { TimelinePoint } from "../types";

/** The video-style chapter bar over the remote's history: markers at their
 * real moments in time, forward/back stepping, and a frozen live point. */
export default function Scrubber({
  points,
  idx,
  onSelect,
}: {
  points: TimelinePoint[] | null;
  idx: number;
  onSelect: (i: number) => void;
}) {
  // Chapter positions: proportional to real elapsed time, like a video
  // scrubber — two pushes a second apart sit together; the quiet half
  // minute between them is visible as empty track. The "now" point is
  // frozen a fixed beat after the latest event rather than at wall-clock
  // now, or an idle hour would crush the whole story against the left edge.
  const positions = useMemo(() => {
    const pts = points ?? [];
    const real = pts.filter((p) => p.at).map((p) => Date.parse(p.at as string));
    const liveT = real.length > 0 ? real[real.length - 1] + 30_000 : Date.now();
    const times = pts.map((p) => (p.at ? Date.parse(p.at) : liveT));
    const t0 = times[0] ?? 0;
    const span = Math.max(1, (times[times.length - 1] ?? 0) - t0);
    return times.map((t) => ((t - t0) / span) * 100);
  }, [points]);

  // Same-second events land on the same spot; stagger a cluster upward so
  // every marker stays visible and clickable. Temporal truth, no occlusion.
  const stackLevels = useMemo(() => {
    const levels: number[] = [];
    let prev = -Infinity;
    let level = 0;
    positions.forEach((p, i) => {
      level = p - prev < 2.2 ? level + 1 : 0;
      levels[i] = level;
      prev = p;
    });
    return levels;
  }, [positions]);

  const vp = points?.[idx];

  return (
    <footer className="scrubber">
      {!points || !vp ? (
        <p className="muted">reading the timeline…</p>
      ) : (
        <>
          <div className="scrub-buttons">
            <button
              onClick={() => onSelect(Math.max(0, idx - 1))}
              disabled={idx === 0}
              aria-label="previous event"
            >
              ◀
            </button>
            <button
              onClick={() => onSelect(Math.min(points.length - 1, idx + 1))}
              disabled={idx === points.length - 1}
              aria-label="next event"
            >
              ▶
            </button>
          </div>
          <div className="scrub-track">
            <div className="scrub-rail" />
            <div className="scrub-fill" style={{ width: `${positions[idx] ?? 100}%` }} />
            {points.map((p, i) => (
              <button
                key={`${p.at ?? "now"}-${i}`}
                className={`scrub-marker${i === idx ? " selected" : ""}${p.at ? "" : " live"}`}
                style={{ left: `${positions[i]}%`, top: `calc(50% - ${stackLevels[i] * 15}px)` }}
                onClick={() => onSelect(i)}
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
            <span className="muted">{vp.at ? new Date(vp.at).toLocaleTimeString() : "live"}</span>
          </div>
        </>
      )}
    </footer>
  );
}
