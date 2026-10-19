import type { ObjectEntry } from "../types";

const OTYPE_ICON: Record<ObjectEntry["otype"], string> = {
  commit: "◉",
  tree: "▦",
  blob: "≡",
};

/** The object database at one moment: a flat, content-addressed keyspace,
 * alphabetical by hash, with a pinned toggle that lights up the objects no
 * branch reaches (a rebase's leavings — never its blobs). */
export default function ObjectList({
  objects,
  selectedSha,
  onSelect,
  highlightOrphans,
  onHighlightOrphans,
}: {
  objects: ObjectEntry[] | null;
  selectedSha: string | null;
  onSelect: (sha: string | null) => void;
  highlightOrphans: boolean;
  onHighlightOrphans: (on: boolean) => void;
}) {
  return (
    <aside className="obj-list">
      <div className="obj-list-head">objects{objects ? ` · ${objects.length}` : ""}</div>
      {objects === null ? (
        <p className="muted panel-empty">reading the database…</p>
      ) : (
        <ul>
          {objects.map((o) => (
            <li key={o.sha}>
              <button
                className={`obj-row${selectedSha === o.sha ? " selected" : ""}${
                  highlightOrphans && o.orphan ? " orphan-hl" : ""
                }`}
                onClick={() => onSelect(o.sha === selectedSha ? null : o.sha)}
                title={o.orphan ? `${o.sha} — no branch reaches this` : o.sha}
              >
                <span className={`obj-icon ${o.otype}`}>{OTYPE_ICON[o.otype]}</span>
                <code>{o.sha.slice(0, 7)}</code>
                <span className="obj-type">{o.otype}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <label className="obj-list-foot" title="Objects no branch reaches at this moment">
        <input
          type="checkbox"
          checked={highlightOrphans}
          onChange={(e) => onHighlightOrphans(e.target.checked)}
        />
        highlight orphans
        {highlightOrphans && objects && (
          <span className="muted"> · {objects.filter((o) => o.orphan).length}</span>
        )}
      </label>
    </aside>
  );
}
