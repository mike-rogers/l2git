import type { GitObject, Graph, Session } from "./types";

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

export function eventStream(token: string): EventSource {
  return new EventSource(`/api/session/${token}/events`);
}

const scenarioUrl = (token: string, scenario: string, rest: string) =>
  `/api/session/${token}/scenario/${scenario}${rest}`;

/** `at` names a timeline point's idx; omitted means live. The server owns
 * refs/ghost reconstruction — the client never reassembles state. */
const atQs = (at?: number) => (at === undefined ? "" : `?at=${at}`);

export async function getGraph(token: string, scenario: string, at?: number): Promise<Graph> {
  const res = await fetch(scenarioUrl(token, scenario, `/graph${atQs(at)}`));
  if (!res.ok) throw new Error(`getGraph: ${res.status}`);
  return res.json();
}

export interface TimelinePoint {
  icon: string;
  label: string;
  note?: string;
  at: string | null; // null only on the trailing live point
  /** What ?at= refers to; absent on the live point. */
  idx?: number;
  live?: boolean;
}

export async function getTimeline(token: string, scenario: string): Promise<TimelinePoint[]> {
  const res = await fetch(scenarioUrl(token, scenario, "/timeline"));
  if (!res.ok) throw new Error(`getTimeline: ${res.status}`);
  return (await res.json()).points;
}

export interface ObjectEntry {
  sha: string;
  otype: "commit" | "tree" | "blob";
  /** No branch reaches this object at the selected moment. */
  orphan: boolean;
}

export async function getObjects(token: string, scenario: string, at?: number): Promise<ObjectEntry[]> {
  const res = await fetch(scenarioUrl(token, scenario, `/objects${atQs(at)}`));
  if (!res.ok) throw new Error(`getObjects: ${res.status}`);
  return (await res.json()).objects;
}

export async function getObject(token: string, scenario: string, sha: string): Promise<GitObject | null> {
  const res = await fetch(scenarioUrl(token, scenario, `/objects/${sha}`));
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`getObject: ${res.status}`);
  return res.json();
}

export async function resetScenario(token: string, scenario: string): Promise<Session> {
  const res = await fetch(scenarioUrl(token, scenario, "/reset"), { method: "POST" });
  if (!res.ok) throw new Error(`resetScenario: ${res.status}`);
  return res.json();
}
