import type { Commit, Graph as GraphData } from "./types";

const ROW_H = 44;
const LANE_W = 26;
const NODE_R = 6;
const PAD_TOP = ROW_H / 2;

const LANE_COLORS = [
  "var(--lane-0)",
  "var(--lane-1)",
  "var(--lane-2)",
  "var(--lane-3)",
  "var(--lane-4)",
];

interface Placed {
  commit: Commit;
  row: number;
  lane: number;
}

/** Greedy lane assignment over a newest-first topo order: a commit takes the
 * lane its first-seen child reserved for it, otherwise the next free lane.
 * First parents inherit the child's lane, so a branch reads as a column. */
function layout(commits: Commit[]): { placed: Placed[]; lanes: number } {
  const reserved = new Map<string, number>();
  let lanes = 0;
  const nextLane = () => lanes++;

  const placed = commits.map((commit, row) => {
    const lane = reserved.get(commit.id) ?? nextLane();
    reserved.delete(commit.id);
    commit.parents.forEach((p, i) => {
      if (!reserved.has(p)) reserved.set(p, i === 0 ? lane : nextLane());
    });
    return { commit, row, lane };
  });
  return { placed, lanes: Math.max(lanes, 1) };
}

function relTime(unixSeconds: number): string {
  const s = Math.max(0, Date.now() / 1000 - unixSeconds);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

export default function Graph({
  data,
  onInspect,
  inspected,
}: {
  data: GraphData;
  /** Click a commit to x-ray it. */
  onInspect?: (sha: string) => void;
  inspected?: string | null;
}) {
  const { placed, lanes } = layout(data.commits);
  const byId = new Map(placed.map((p) => [p.commit.id, p]));
  const railsW = lanes * LANE_W + LANE_W / 2;
  const height = placed.length * ROW_H;

  const refsByTarget = new Map<string, string[]>();
  for (const r of data.refs) {
    const list = refsByTarget.get(r.target) ?? [];
    list.push(r.name);
    refsByTarget.set(r.target, list);
  }

  const cx = (lane: number) => LANE_W / 2 + lane * LANE_W;
  const cy = (row: number) => PAD_TOP + row * ROW_H;

  if (placed.length === 0) {
    return <div className="graph-empty">The remote is empty — nothing pushed yet.</div>;
  }

  return (
    <div className="graph" style={{ minHeight: height }}>
      <svg className="graph-rails" width={railsW} height={height} aria-hidden>
        {placed.flatMap(({ commit, row, lane }) =>
          commit.parents.map((pid) => {
            const parent = byId.get(pid);
            if (!parent) return null;
            const x1 = cx(lane);
            const y1 = cy(row);
            const x2 = cx(parent.lane);
            const y2 = cy(parent.row);
            const d =
              x1 === x2
                ? `M ${x1} ${y1} L ${x2} ${y2}`
                : `M ${x1} ${y1} C ${x1} ${(y1 + y2) / 2}, ${x2} ${(y1 + y2) / 2}, ${x2} ${y2}`;
            return (
              <path
                key={`${commit.id}-${pid}`}
                d={d}
                fill="none"
                stroke={LANE_COLORS[lane % LANE_COLORS.length]}
                strokeWidth={2}
                opacity={0.75}
              />
            );
          }),
        )}
        {placed.map(({ commit, row, lane }) => (
          <circle
            key={commit.id}
            cx={cx(lane)}
            cy={cy(row)}
            r={NODE_R}
            fill={commit.seeded || commit.ghost ? "var(--bg)" : LANE_COLORS[lane % LANE_COLORS.length]}
            stroke={LANE_COLORS[lane % LANE_COLORS.length]}
            strokeWidth={2.5}
            strokeDasharray={commit.ghost ? "3 3" : undefined}
            opacity={commit.ghost ? 0.55 : 1}
          />
        ))}
      </svg>
      <div className="graph-rows" style={{ marginLeft: railsW + 10 }}>
        {placed.map(({ commit }) => (
          <div
            className={`graph-row${commit.ghost ? " ghost" : ""}${
              onInspect ? " inspectable" : ""
            }${inspected === commit.id ? " inspected" : ""}`}
            key={commit.id}
            title={onInspect ? `${commit.id} — click to x-ray` : commit.id}
            onClick={onInspect ? () => onInspect(commit.id) : undefined}
          >
            <code className="hash">{commit.short}</code>
            <span className="message">{commit.message}</span>
            {(refsByTarget.get(commit.id) ?? []).map((name) => (
              <span key={name} className={`ref-pill${name === data.head ? " head" : ""}`}>
                {name}
                {name === data.head ? " · HEAD" : ""}
              </span>
            ))}
            {commit.seeded && !commit.ghost && <span className="seeded-pill">was here</span>}
            {commit.ghost && (
              <span className="ghost-pill" title="in the object store, but no branch points at it">
                unreachable
              </span>
            )}
            <span className="meta">
              {commit.author} · {relTime(commit.time)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
