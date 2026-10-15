import { useCallback, useEffect, useRef, useState } from "react";
import { createSession, eventStream, getGraph, getSession, resetScenario } from "./api";
import Graph from "./Graph";
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
    <nav className="card tree">
      <h3>Scenarios</h3>
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
    <section className={`card scenario ${scenario.status}`}>
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
    </section>
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
  return (
    <div className="banner" role="status">
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
  return (
    <section className="card">
      <h3>Activity</h3>
      {events.length === 0 ? (
        <p className="muted">Nothing yet — clone the repo to begin.</p>
      ) : (
        <ul className="events">
          {[...events].reverse().map((e, i) => (
            <li key={`${e.at}-${i}`} className={e["pass?"] === false ? "fail" : e["pass?"] ? "pass" : ""}>
              {e.type === "fetch" ? (
                <span>⇣ repository fetched</span>
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
      )}
    </section>
  );
}

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
  const esRef = useRef<EventSource | null>(null);

  const current: Scenario | undefined =
    session?.scenarios.find((s) => s.id === selectedId) ??
    session?.scenarios.find((s) => s.status !== "complete") ??
    session?.scenarios[session.scenarios.length - 1];

  const refresh = useCallback(async (token: string, scenarioId: string) => {
    const [s, g] = await Promise.all([getSession(token), getGraph(token, scenarioId)]);
    if (s) setSession(s);
    setGraph(g);
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
    <div className="layout">
      <header className="masthead">
        <h1>l2git</h1>
        <span className="tagline">real git, no fear</span>
        <span className="token" title="Bookmark this page — the token is your session.">
          session <code>{session.token}</code>
        </span>
      </header>
      {celebration && (
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
      <main>
        <div className="side">
          <ScenarioTree
            scenarios={session.scenarios}
            selectedId={current.id}
            onSelect={setSelectedId}
          />
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
          <EventLog events={session.events} />
        </div>
        <div className="stage card">
          <h3>
            the remote, live <span className="pulse" title="updates as you push" />
          </h3>
          {graph ? <Graph data={graph} /> : <p className="muted">loading graph…</p>}
        </div>
      </main>
    </div>
  );
}
