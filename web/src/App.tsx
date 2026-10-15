import { useCallback, useEffect, useRef, useState } from "react";
import { createSession, eventStream, getGraph, getSession } from "./api";
import Graph from "./Graph";
import type { Graph as GraphData, Scenario, Session, SessionEvent } from "./types";

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

function ScenarioCard({ scenario }: { scenario: Scenario }) {
  return (
    <section className={`card scenario ${scenario.status}`}>
      <header>
        <h2>{scenario.title}</h2>
        <span className={`status-pill ${scenario.status}`}>
          {scenario.status === "complete" ? "✓ complete" : scenario.status.replace("-", " ")}
        </span>
      </header>
      <p>{scenario.instructions}</p>
      <CloneUrl url={scenario["clone-url"]} />
    </section>
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
  const esRef = useRef<EventSource | null>(null);

  // Current scenario: first not complete, else the last one.
  const current: Scenario | undefined =
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
    es.onmessage = () => refresh(session.token, current.id).catch(() => {});
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
      <main>
        <div className="side">
          <ScenarioCard scenario={current} />
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
