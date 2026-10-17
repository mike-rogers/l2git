import { useCallback, useEffect, useRef, useState } from "react";
import { createSession, eventStream, getGraph, getSession, resetScenario } from "./api";
import Graph from "./Graph";
// Timeline's scrubber and the XRay inspector are deliberately not rendered
// yet — they're getting their own design pass — but buildTimeline/allGhosts
// still feed the live graph its ghost (unreachable-commit) candidates.
import { allGhosts, buildTimeline } from "./Timeline";
import type { Graph as GraphData, Level, Scenario, Session, SessionEvent } from "./types";

const LEVELS: { level: Level; label: string }[] = [
  { level: "basic", label: "Basic" },
  { level: "intermediate", label: "Intermediate" },
  { level: "advanced", label: "Advanced" },
];

function statusGlyph(status: Scenario["status"]): string {
  if (status === "complete") return "✓";
  if (status === "in-progress") return "●";
  return "○";
}

function ScenarioTree({
  scenarios,
  selectedId,
  onSelect,
}: {
  scenarios: Scenario[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  return (
    <nav className="tree">
      {LEVELS.map(({ level, label }) => {
        const group = scenarios.filter((s) => s.level === level);
        return (
          <div key={level} className="tree-group">
            <div className="tree-root">{label}</div>
            {group.length === 0 ? (
              <div className="tree-leaf muted soon">coming soon</div>
            ) : (
              group.map((s) => (
                <button
                  key={s.id}
                  className={`tree-leaf ${s.status}${s.id === selectedId ? " selected" : ""}`}
                  onClick={() => onSelect(s.id)}
                >
                  <span className={`glyph ${s.status}`}>{statusGlyph(s.status)}</span>
                  {s.title}
                </button>
              ))
            )}
          </div>
        );
      })}
    </nav>
  );
}

/** Token lives in the URL (?token=…) so a bookmark restores the session. */
function tokenFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get("token");
}

function putTokenInUrl(token: string) {
  const url = new URL(window.location.href);
  url.searchParams.set("token", token);
  window.history.replaceState(null, "", url);
}

function CloneUrl({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);
  const cmd = `git clone ${url}`;
  return (
    <div className="clone-url">
      <code>{cmd}</code>
      <button
        onClick={() => {
          navigator.clipboard.writeText(cmd).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          });
        }}
      >
        {copied ? "copied!" : "copy"}
      </button>
    </div>
  );
}

function HintLadder({ hints }: { hints: string[] }) {
  const [shown, setShown] = useState(0);
  if (hints.length === 0) return null;
  return (
    <div className="hints">
      {hints.slice(0, shown).map((h, i) => (
        <div className="hint" key={i}>
          <span className="hint-n">hint {i + 1}</span>
          {h}
        </div>
      ))}
      {shown < hints.length && (
        <button className="hint-btn" onClick={() => setShown(shown + 1)}>
          {shown === 0 ? "Need a hint?" : "Another hint?"}
          <span className="hint-count"> ({hints.length - shown} available)</span>
        </button>
      )}
    </div>
  );
}

function ScenarioCard({ scenario, onReset }: { scenario: Scenario; onReset: () => void }) {
  const steps = scenario.steps;
  const multi = steps.length > 1;
  const current = steps.find((s) => s.status === "current") ?? null;
  return (
    <article className={`scenario ${scenario.status}`}>
      <header>
        <h2>{scenario.title}</h2>
        <span className={`status-pill ${scenario.status}`}>
          {scenario.status === "complete" ? "✓ complete" : scenario.status.replace("-", " ")}
        </span>
      </header>
      <p>{scenario.instructions}</p>
      {multi && (
        <ol className="steps">
          {steps.map((st, i) => (
            <li key={st.id} className={`step ${st.status}`}>
              <span className="step-glyph">{st.status === "complete" ? "✓" : i + 1}</span>
              <span>{st.title ?? `Step ${i + 1}`}</span>
            </li>
          ))}
        </ol>
      )}
      {current?.instructions && <p className="step-instructions">{current.instructions}</p>}
      {current && <HintLadder key={`${scenario.id}:${current.id}`} hints={current.hints} />}
      <CloneUrl url={scenario["clone-url"]} />
      {scenario.status !== "not-started" && (
        <button
          className="reset-btn"
          onClick={() => {
            if (
              window.confirm(
                "Reset this scenario to its starting state? Your pushed work on it is discarded — delete your local clone and clone fresh afterwards.",
              )
            )
              onReset();
          }}
        >
          ↺ reset scenario
        </button>
      )}
    </article>
  );
}

function SuccessBanner({
  celebration,
  scenarios,
  onNext,
  onDismiss,
}: {
  celebration: { scenario: string; message: string };
  scenarios: Scenario[];
  onNext: (id: string) => void;
  onDismiss: () => void;
}) {
  const done = scenarios.find((s) => s.id === celebration.scenario);
  const next = scenarios.find((s) => s.status !== "complete");
  // The banner sits below the lesson text (where the post-completion
  // debrief will live), so make sure it's actually seen when it lands.
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    ref.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, []);
  return (
    <div className="banner" role="status" ref={ref}>
      <span className="banner-check">✓</span>
      <div className="banner-text">
        <strong>{done?.title ?? celebration.scenario} — complete!</strong>
        <span className="banner-msg">{celebration.message}</span>
      </div>
      {next ? (
        <button className="banner-next" onClick={() => onNext(next.id)}>
          On to the next scenario →
        </button>
      ) : (
        <span className="banner-done">That was the last one — more scenarios coming soon.</span>
      )}
      <button className="banner-x" onClick={onDismiss} aria-label="dismiss">
        ×
      </button>
    </div>
  );
}

function EventLog({ events }: { events: SessionEvent[] }) {
  if (events.length === 0)
    return <p className="muted panel-empty">Nothing yet — clone the repo to begin.</p>;
  return (
    <ul className="events">
      {[...events].reverse().map((e, i) => (
        <li key={`${e.at}-${i}`} className={e["pass?"] === false ? "fail" : e["pass?"] ? "pass" : ""}>
          {e.type === "fetch" ? (
            <span>⇣ repository fetched</span>
          ) : e.type === "push" ? (
            <span>
              ⇡ push {e.commands?.some((c) => c.result !== "OK") ? "rejected" : "received"}
            </span>
          ) : e.type === "teammate" ? (
            <span className="teammate">⇡ {e.message}</span>
          ) : e.type === "reset" ? (
            <span className="muted">↺ {e.message}</span>
          ) : e.type === "step" ? (
            <span className="step-ev">✓ {e.message}</span>
          ) : (
            <span>
              {e["pass?"] ? "✓" : "✗"} {e.message}
            </span>
          )}
          <time>{new Date(e.at).toLocaleTimeString()}</time>
        </li>
      ))}
    </ul>
  );
}

type RightTab = "activity" | null;

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [graph, setGraph] = useState<GraphData | null>(null);
  const [error, setError] = useState<string | null>(null);
  // null = auto mode: follow the first incomplete scenario (the original
  // tool's "progress to the next scenario on success"); a click pins one.
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [celebration, setCelebration] = useState<{ scenario: string; message: string } | null>(
    null,
  );
  // IDE-style tool windows: collapsible edge panels around the lesson.
  const [leftOpen, setLeftOpen] = useState(true);
  const [rightTab, setRightTab] = useState<RightTab>("activity");
  const [bottomOpen, setBottomOpen] = useState(true);
  // Bottom panel height: fixed (~3.5 graph rows) by default, drag to taste.
  const [bottomH, setBottomH] = useState(196);
  const esRef = useRef<EventSource | null>(null);

  const startBottomResize = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      const startY = e.clientY;
      const startH = bottomH;
      const move = (ev: MouseEvent) =>
        setBottomH(
          Math.min(Math.max(startH + (startY - ev.clientY), 110), window.innerHeight * 0.7),
        );
      const up = () => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    },
    [bottomH],
  );

  const current: Scenario | undefined =
    session?.scenarios.find((s) => s.id === selectedId) ??
    session?.scenarios.find((s) => s.status !== "complete") ??
    session?.scenarios[session.scenarios.length - 1];

  const refresh = useCallback(async (token: string, scenarioId: string) => {
    // Session first: the graph wants the ghost list, which lives in events.
    const s = await getSession(token);
    if (s) setSession(s);
    const ghosts = allGhosts(buildTimeline(s?.events ?? [], scenarioId));
    setGraph(await getGraph(token, scenarioId, { extra: ghosts }));
  }, []);

  // Boot: restore session from URL token, or mint a fresh one.
  useEffect(() => {
    (async () => {
      try {
        const existing = tokenFromUrl();
        let s = existing ? await getSession(existing) : null;
        if (!s) {
          s = await createSession();
          putTokenInUrl(s.token);
        }
        setSession(s);
      } catch (e) {
        setError(String(e));
      }
    })();
  }, []);

  // Live updates: any event means the remote (probably) changed.
  useEffect(() => {
    if (!session || !current) return;
    refresh(session.token, current.id).catch(() => {});
    const es = eventStream(session.token);
    esRef.current = es;
    es.onmessage = (ev) => {
      try {
        const data = JSON.parse(ev.data) as SessionEvent;
        if (data.type === "evaluation" && data["pass?"]) {
          setCelebration({ scenario: data.scenario, message: data.message ?? "" });
          // Pin the completed scenario so the card doesn't auto-advance
          // out from under the celebration; the banner's button moves on.
          setSelectedId(data.scenario);
        }
      } catch {
        /* pings and malformed frames are fine to ignore */
      }
      refresh(session.token, current.id).catch(() => {});
    };
    return () => es.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.token, current?.id]);

  if (error) return <div className="boot-error">Can’t reach the l2git server: {error}</div>;
  if (!session || !current) return <div className="boot">warming up…</div>;

  return (
    <div className="ide">
      <header className="masthead">
        <h1>l2git</h1>
        <span className="tagline">git good, newb</span>
        <span className="token" title="Bookmark this page — the token is your session.">
          session <code>{session.token}</code>
        </span>
      </header>
      <div className="ide-body">
        {leftOpen ? (
          <aside className="panel panel-left">
            <div className="panel-head">
              <span>Scenarios</span>
              <button
                className="panel-toggle"
                onClick={() => setLeftOpen(false)}
                title="Collapse scenarios panel"
                aria-label="Collapse scenarios panel"
              >
                ⟨
              </button>
            </div>
            <div className="panel-body">
              <ScenarioTree
                scenarios={session.scenarios}
                selectedId={current.id}
                onSelect={setSelectedId}
              />
            </div>
          </aside>
        ) : (
          <button
            className="panel-stub stub-left"
            onClick={() => setLeftOpen(true)}
            title="Open scenarios panel"
          >
            <span className="stub-label">Scenarios ⟩</span>
          </button>
        )}
        <main className="lesson">
          <ScenarioCard
            scenario={current}
            onReset={() => {
              resetScenario(session.token, current.id)
                .then((s) => {
                  setSession(s);
                  return refresh(session.token, current.id);
                })
                .catch(() => {});
            }}
          />
          {celebration && celebration.scenario === current.id && (
            // Below the lesson text, above where the post-completion
            // debrief/theory will unlock — the hinge between "what you
            // did" and "why it worked".
            <SuccessBanner
              celebration={celebration}
              scenarios={session.scenarios}
              onNext={(id) => {
                setSelectedId(id);
                setCelebration(null);
              }}
              onDismiss={() => setCelebration(null)}
            />
          )}
        </main>
        {rightTab === "activity" && (
          <aside className="panel panel-right">
            <div className="panel-head">
              <span>Activity</span>
              <button
                className="panel-toggle"
                onClick={() => setRightTab(null)}
                title="Collapse activity panel"
                aria-label="Collapse activity panel"
              >
                ⟩
              </button>
            </div>
            <div className="panel-body">
              <EventLog events={session.events} />
            </div>
          </aside>
        )}
        <nav className="rail" aria-label="tool windows">
          <button
            className={rightTab === "activity" ? "active" : ""}
            onClick={() => setRightTab(rightTab === "activity" ? null : "activity")}
            title="Activity log"
          >
            Activity
          </button>
        </nav>
      </div>
      <section
        className={`panel panel-bottom${bottomOpen ? "" : " closed"}`}
        style={bottomOpen ? { height: bottomH } : undefined}
      >
        {bottomOpen && (
          <div
            className="resize-handle"
            onMouseDown={startBottomResize}
            title="Drag to resize"
            aria-hidden
          />
        )}
        <div className="panel-head">
          <span>
            the remote, live <span className="pulse" title="updates as you push" />
          </span>
          <button
            className="panel-toggle"
            onClick={() => setBottomOpen(!bottomOpen)}
            title={bottomOpen ? "Collapse remote panel" : "Open remote panel"}
            aria-label={bottomOpen ? "Collapse remote panel" : "Open remote panel"}
          >
            {bottomOpen ? "⌄" : "⌃"}
          </button>
        </div>
        {bottomOpen && (
          <div className="panel-body">
            {graph ? <Graph data={graph} /> : <p className="muted">loading graph…</p>}
          </div>
        )}
      </section>
    </div>
  );
}
