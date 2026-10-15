import type { Graph, Session } from "./types";

export async function createSession(): Promise<Session> {
  const res = await fetch("/api/session", { method: "POST" });
  if (!res.ok) throw new Error(`createSession: ${res.status}`);
  return res.json();
}

export async function getSession(token: string): Promise<Session | null> {
  const res = await fetch(`/api/session/${token}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`getSession: ${res.status}`);
  return res.json();
}

export async function getGraph(token: string, scenario: string): Promise<Graph> {
  const res = await fetch(`/api/session/${token}/graph/${scenario}`);
  if (!res.ok) throw new Error(`getGraph: ${res.status}`);
  return res.json();
}

export async function resetScenario(token: string, scenario: string): Promise<Session> {
  const res = await fetch(`/api/session/${token}/reset/${scenario}`, { method: "POST" });
  if (!res.ok) throw new Error(`resetScenario: ${res.status}`);
  return res.json();
}

export function eventStream(token: string): EventSource {
  return new EventSource(`/api/session/${token}/events`);
}
