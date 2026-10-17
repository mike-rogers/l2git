import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { getObject } from "./api";
import type { GitObject } from "./types";

/** The "look how neat this is" view: the selected object's pointer graph.
 * A commit points at its tree; trees point at blobs and subtrees; blobs
 * are the bytes themselves. Edges are colored by the hash they point AT,
 * and nodes are deduplicated by hash — two names for the same content
 * converge on one card, because that's what content addressing means. */

function hashHue(sha: string): number {
  return parseInt(sha.slice(0, 8), 16) % 360;
}
const hueColor = (sha: string) => `hsl(${hashHue(sha)} 55% 45%)`;

function Chip({ sha, onClick }: { sha: string; onClick?: (sha: string) => void }) {
  const style = {
    color: hueColor(sha),
    borderColor: `hsl(${hashHue(sha)} 55% 45% / 0.5)`,
    background: `hsl(${hashHue(sha)} 55% 45% / 0.1)`,
  };
  return onClick ? (
    <button className="hash-chip" style={style} onClick={() => onClick(sha)} title={sha}>
      {sha.slice(0, 7)}
    </button>
  ) : (
    <span className="hash-chip" style={style} title={sha}>
      {sha.slice(0, 7)}
    </span>
  );
}

interface Node {
  obj: GitObject;
  depth: number;
}

interface Edge {
  id: string; // keys the source row element
  to: string;
}

const FETCH_CAP = 48;

export default function ObjectDetail({
  token,
  scenario,
  sha,
  onSelect,
}: {
  token: string;
  scenario: string;
  sha: string;
  onSelect: (sha: string) => void;
}) {
  const [nodes, setNodes] = useState<Map<string, Node> | null>(null);

  useEffect(() => {
    let live = true;
    setNodes(null);
    (async () => {
      const acc = new Map<string, Node>();
      const queue: { sha: string; depth: number }[] = [{ sha, depth: 0 }];
      while (queue.length > 0 && acc.size < FETCH_CAP) {
        const { sha: s, depth } = queue.shift()!;
        if (acc.has(s)) continue; // BFS: first sight is the shallowest
        const obj = await getObject(token, scenario, s);
        if (!obj) continue;
        acc.set(s, { obj, depth });
        if (obj.type === "commit") queue.push({ sha: obj.tree, depth: depth + 1 });
        if (obj.type === "tree")
          for (const e of obj.entries) queue.push({ sha: e.sha, depth: depth + 1 });
      }
      if (live) setNodes(acc);
    })().catch(() => live && setNodes(new Map()));
    return () => {
      live = false;
    };
  }, [token, scenario, sha]);

  // Pointer edges: commit -> its tree, tree entry -> its object.
  const edges: Edge[] = [];
  if (nodes)
    for (const [s, { obj }] of nodes) {
      if (obj.type === "commit" && nodes.has(obj.tree))
        edges.push({ id: `${s}:tree`, to: obj.tree });
      if (obj.type === "tree")
        for (const e of obj.entries)
          if (nodes.has(e.sha)) edges.push({ id: `${s}:${e.name}`, to: e.sha });
    }

  const wrapRef = useRef<HTMLDivElement | null>(null);
  const srcRefs = useRef(new Map<string, HTMLElement | null>());
  const dstRefs = useRef(new Map<string, HTMLElement | null>());
  const [paths, setPaths] = useState<{ key: string; d: string; color: string }[]>([]);

  const measure = () => {
    const wrap = wrapRef.current;
    if (!wrap || !nodes) return;
    const wr = wrap.getBoundingClientRect();
    const out: { key: string; d: string; color: string }[] = [];
    for (const edge of edges) {
      const s = srcRefs.current.get(edge.id);
      const t = dstRefs.current.get(edge.to);
      if (!s || !t) continue;
      const sr = s.getBoundingClientRect();
      const tr = t.getBoundingClientRect();
      const x1 = sr.right - wr.left + wrap.scrollLeft;
      const y1 = sr.top + sr.height / 2 - wr.top + wrap.scrollTop;
      const x2 = tr.left - wr.left + wrap.scrollLeft;
      const y2 = tr.top + tr.height / 2 - wr.top + wrap.scrollTop;
      const mx = (x1 + x2) / 2;
      out.push({
        key: edge.id,
        color: hueColor(edge.to),
        d: `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`,
      });
    }
    setPaths(out);
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(measure, [nodes]);
  useEffect(() => {
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes]);

  if (nodes === null) return <p className="muted">reading objects…</p>;
  if (nodes.size === 0) return <p className="muted">that object isn’t readable right now.</p>;

  const depths = [...new Set([...nodes.values()].map((n) => n.depth))].sort((a, b) => a - b);

  const card = (s: string, { obj }: Node) => (
    <div
      key={s}
      className={`onode ${obj.type}`}
      style={{ borderColor: `hsl(${hashHue(s)} 55% 45% / 0.55)` }}
      ref={(el) => void dstRefs.current.set(s, el)}
    >
      <div className="onode-head">
        <span className="onode-type">{obj.type}</span>
        <Chip sha={s} />
      </div>
      {obj.type === "commit" && (
        <>
          <div className="onode-msg">{obj.message.split("\n")[0]}</div>
          <div className="onode-meta">{obj.author.name}</div>
          <div className="onode-row" ref={(el) => void srcRefs.current.set(`${s}:tree`, el)}>
            <span className="onode-k">tree</span>
            <Chip sha={obj.tree} onClick={onSelect} />
          </div>
          {obj.parents.map((p, i) => (
            <div className="onode-row" key={p}>
              <span className="onode-k">{obj.parents.length > 1 ? `parent ${i + 1}` : "parent"}</span>
              <Chip sha={p} onClick={onSelect} />
            </div>
          ))}
        </>
      )}
      {obj.type === "tree" &&
        obj.entries.map((e) => (
          <div
            className="onode-row"
            key={e.name}
            ref={(el) => void srcRefs.current.set(`${s}:${e.name}`, el)}
          >
            <span className="onode-k mono">{e.name}</span>
            <Chip sha={e.sha} onClick={onSelect} />
          </div>
        ))}
      {obj.type === "blob" && (
        <>
          <div className="onode-meta">{obj.size} bytes</div>
          {obj.binary ? (
            <p className="muted">(binary)</p>
          ) : (
            <pre className="onode-blob">
              {(obj.content ?? "").slice(0, 400)}
              {(obj.content ?? "").length > 400 ? "⋯" : ""}
            </pre>
          )}
        </>
      )}
    </div>
  );

  return (
    <div className="objgraph" ref={wrapRef}>
      <svg
        className="objgraph-edges"
        width={wrapRef.current?.scrollWidth ?? 0}
        height={wrapRef.current?.scrollHeight ?? 0}
        aria-hidden
      >
        {paths.map((p) => (
          <path key={p.key} d={p.d} fill="none" stroke={p.color} strokeWidth={1.8} opacity={0.65} />
        ))}
      </svg>
      {depths.map((d) => (
        <div className="objgraph-col" key={d}>
          {[...nodes.entries()].filter(([, n]) => n.depth === d).map(([s, n]) => card(s, n))}
        </div>
      ))}
    </div>
  );
}
