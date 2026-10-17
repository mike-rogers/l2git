import { useEffect, useState } from "react";
import { getObject } from "./api";
import type { GitObject } from "./types";

/** Deterministic color per hash, so the same object shows the same color
 * wherever it appears — which is how "these two trees share this blob"
 * becomes visible before anyone compares hex strings. */
function hashHue(sha: string): number {
  return parseInt(sha.slice(0, 8), 16) % 360;
}

function HashChip({ sha, onClick }: { sha: string; onClick?: (sha: string) => void }) {
  const hue = hashHue(sha);
  const style = {
    color: `hsl(${hue} 55% 45%)`,
    borderColor: `hsl(${hue} 55% 45% / 0.5)`,
    background: `hsl(${hue} 55% 45% / 0.1)`,
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

function ObjectView({ obj, onNavigate }: { obj: GitObject; onNavigate: (sha: string) => void }) {
  switch (obj.type) {
    case "commit":
      return (
        <>
          <div className="xray-fields">
            <span className="xray-k">tree</span>
            <span>
              <HashChip sha={obj.tree} onClick={onNavigate} />
            </span>
            {obj.parents.map((p, i) => (
              <span className="xray-k" key={`k${p}`}>
                {obj.parents.length > 1 ? `parent ${i + 1}` : "parent"}
              </span>
            ))}
            {obj.parents.map((p) => (
              <span key={p}>
                <HashChip sha={p} onClick={onNavigate} />
              </span>
            ))}
            <span className="xray-k">author</span>
            <span>
              {obj.author.name} <span className="muted">&lt;{obj.author.email}&gt;</span>
            </span>
          </div>
          <pre className="xray-pre">{obj.message}</pre>
          <p className="xray-moral">
            A commit is four lines and a message. The snapshot is the tree; the history is the
            parent{obj.parents.length === 1 ? "" : "s"}. Change any of it and the hash changes.
          </p>
        </>
      );
    case "tree":
      return (
        <>
          <table className="xray-tree">
            <tbody>
              {obj.entries.map((e) => (
                <tr key={e.sha + e.name}>
                  <td className="muted mono">{e.mode}</td>
                  <td className="muted">{e.otype}</td>
                  <td>
                    <HashChip sha={e.sha} onClick={onNavigate} />
                  </td>
                  <td className="mono">{e.name}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="xray-moral">
            A tree maps names to hashes. Same hash, same content, stored once — an unchanged file
            keeps its hash (and its color here) from commit to commit.
          </p>
        </>
      );
    case "blob":
      return (
        <>
          <div className="xray-fields">
            <span className="xray-k">size</span>
            <span>{obj.size} bytes</span>
          </div>
          {obj.binary ? (
            <p className="muted">(binary content)</p>
          ) : (
            <pre className="xray-pre">{obj.content}</pre>
          )}
          <p className="xray-moral">
            A blob is just the file's bytes — no name, no history. Its name lives in the trees that
            point at it.
          </p>
        </>
      );
    default:
      return <p className="muted">unrecognized object type</p>;
  }
}

export default function XRay({
  token,
  scenario,
  rootSha,
  onClose,
}: {
  token: string;
  scenario: string;
  rootSha: string;
  onClose: () => void;
}) {
  // A navigation trail, newest last; the root is the clicked commit.
  const [trail, setTrail] = useState<string[]>([rootSha]);
  const [obj, setObj] = useState<GitObject | null>(null);
  const sha = trail[trail.length - 1];

  useEffect(() => setTrail([rootSha]), [rootSha]);
  useEffect(() => {
    let live = true;
    getObject(token, scenario, sha)
      .then((o) => live && setObj(o))
      .catch(() => live && setObj(null));
    return () => {
      live = false;
    };
  }, [token, scenario, sha]);

  return (
    <div className="xray">
      <header className="xray-head">
        <h4>
          x-ray{obj && <span className={`otype-pill ${obj.type}`}>{obj.type}</span>}
          {obj && <HashChip sha={obj.sha} />}
        </h4>
        {trail.length > 1 && (
          <button className="xray-nav" onClick={() => setTrail(trail.slice(0, -1))}>
            ← back
          </button>
        )}
        <button className="xray-nav" onClick={onClose} aria-label="close x-ray">
          ×
        </button>
      </header>
      {obj ? (
        <ObjectView obj={obj} onNavigate={(next) => setTrail([...trail, next])} />
      ) : (
        <p className="muted">loading object…</p>
      )}
    </div>
  );
}
