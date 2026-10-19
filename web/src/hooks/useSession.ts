import { useCallback, useEffect, useState } from "react";
import { createSession, eventStream, getGraph, getSession, resetScenario } from "../api";
import type { Graph, Scenario, Session, SessionEvent } from "../types";

/** Token lives in the URL (?token=…) so a bookmark restores the session. */
function tokenFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get("token");
}

function putTokenInUrl(token: string) {
  const url = new URL(window.location.href);
  url.searchParams.set("token", token);
  window.history.replaceState(null, "", url);
}

export interface Celebration {
  scenario: string;
  message: string;
}

/** The data layer: session boot (URL token or a fresh one), the live SSE
 * refresh loop, the scenario-selection policy, celebration state, and
 * reset. Layout state stays in App; everything that talks to the server
 * lives here. */
export function useSession() {
  const [session, setSession] = useState<Session | null>(null);
  const [graph, setGraph] = useState<Graph | null>(null);
  const [error, setError] = useState<string | null>(null);
  // null = auto mode: follow the first incomplete scenario (the original
  // tool's "progress to the next scenario on success"); a click pins one.
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [celebration, setCelebration] = useState<Celebration | null>(null);

  const current: Scenario | undefined =
    session?.scenarios.find((s) => s.id === selectedId) ??
    session?.scenarios.find((s) => s.status !== "complete") ??
    session?.scenarios[session.scenarios.length - 1];

  const refresh = useCallback(async (token: string, scenarioId: string) => {
    // The server owns ghost/timeline state, so these are independent.
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

  const reset = useCallback(() => {
    if (!session || !current) return;
    resetScenario(session.token, current.id)
      .then((s) => {
        setSession(s);
        return refresh(session.token, current.id);
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.token, current?.id, refresh]);

  const goToScenario = useCallback((id: string) => {
    setSelectedId(id);
    setCelebration(null);
  }, []);

  return {
    session,
    graph,
    error,
    current,
    celebration,
    selectScenario: setSelectedId,
    goToScenario,
    clearCelebration: () => setCelebration(null),
    reset,
  };
}
