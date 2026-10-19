import { useEffect, useRef, useState } from "react";
import { getObjects, getTimeline } from "../api";
import { useInspectorMemory } from "../hooks/useInspectorMemory";
import type { ObjectEntry, TimelinePoint } from "../types";
import ObjectDetail from "./ObjectDetail";
import ObjectList from "./ObjectList";
import Scrubber from "./Scrubber";

/** The under-the-hood popover, composed of three views: the scrubber over
 * the remote's history, the object database at the selected moment, and
 * the pointer graph of the selected object. Timeline semantics live on the
 * server — this component holds the selection state and the fetches. */
export default function Inspector({
  token,
  scenarioId,
  eventCount,
  onClose,
}: {
  token: string;
  scenarioId: string;
  /** Bumps when the session log grows; triggers a timeline refetch. */
  eventCount: number;
  onClose: () => void;
}) {
  const [remembered, remember] = useInspectorMemory(`${token}:${scenarioId}`);

  const [points, setPoints] = useState<TimelinePoint[] | null>(null);
  const [idx, setIdx] = useState(0);
  const [objects, setObjects] = useState<ObjectEntry[] | null>(null);
  const [selectedSha, setSelectedSha] = useState<string | null>(remembered?.selectedSha ?? null);
  const [highlightOrphans, setHighlightOrphans] = useState(remembered?.highlightOrphans ?? false);
  // Pinned to "now": new events keep the view live; scrubbed back, it holds.
  const pinnedLive = useRef(remembered ? remembered.at === null : true);
  const initialized = useRef(false);

  useEffect(() => {
    let live = true;
    getTimeline(token, scenarioId)
      .then((pts) => {
        if (!live) return;
        setPoints(pts);
        setIdx((prev) => {
          if (!initialized.current) {
            initialized.current = true;
            if (remembered && remembered.at !== null) {
              const i = pts.findIndex((p) => p.at === remembered.at);
              if (i >= 0) {
                pinnedLive.current = false;
                return i;
              }
            }
            pinnedLive.current = true;
            return pts.length - 1;
          }
          return pinnedLive.current ? pts.length - 1 : Math.min(prev, pts.length - 1);
        });
      })
      .catch(() => live && setPoints([]));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, scenarioId, eventCount]);

  const vp = points?.[Math.min(idx, (points?.length ?? 1) - 1)];

  const select = (i: number) => {
    setIdx(i);
    pinnedLive.current = points !== null && i === points.length - 1;
  };

  useEffect(() => {
    if (vp) remember({ at: vp.at, selectedSha, highlightOrphans });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vp, selectedSha, highlightOrphans]);

  useEffect(() => {
    if (!vp) return;
    let live = true;
    setObjects(null);
    getObjects(token, scenarioId, vp.live ? undefined : vp.idx)
      .then((o) => {
        if (!live) return;
        setObjects(o);
        // An object selected at a later moment may not exist at this one.
        setSelectedSha((s) => (s && !o.some((e) => e.sha === s) ? null : s));
      })
      .catch(() => live && setObjects([]));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, scenarioId, vp?.at, vp?.idx, points === null]);

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
          <ObjectList
            objects={objects}
            selectedSha={selectedSha}
            onSelect={setSelectedSha}
            highlightOrphans={highlightOrphans}
            onHighlightOrphans={setHighlightOrphans}
          />
          <section className={`obj-main${selectedSha ? "" : " empty"}`}>
            {selectedSha ? (
              <ObjectDetail
                token={token}
                scenario={scenarioId}
                sha={selectedSha}
                onSelect={setSelectedSha}
              />
            ) : (
              <p className="muted">
                every object the database holds at this moment, keyed by hash — pick a moment
                below, an object on the left
              </p>
            )}
          </section>
        </div>

        <Scrubber points={points} idx={idx} onSelect={select} />
      </div>
    </div>
  );
}
