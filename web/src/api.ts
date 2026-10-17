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

export interface GraphOpts {
  /** Render as of this past ref snapshot instead of the live refs. */
  refs?: Record<string, string>;
  /** Also walk from these hashes; commits only they reach come back ghosted. */
  extra?: string[];
}

export async function getGraph(token: string, scenario: string, opts?: GraphOpts): Promise<Graph> {
  const params = new URLSearchParams();
  if (opts?.refs && Object.keys(opts.refs).length > 0)
    params.set(
      "refs",
      Object.entries(opts.refs)
        .map(([name, sha]) => `${name}=${sha}`)
        .join(","),
    );
  if (opts?.extra && opts.extra.length > 0) params.set("extra", opts.extra.join(","));
  const qs = params.toString();
  const res = await fetch(`/api/session/${token}/graph/${scenario}${qs ? `?${qs}` : ""}`);
  if (!res.ok) throw new Error(`getGraph: ${res.status}`);
  return res.json();
}

export async function getObject(token: string, scenario: string, sha: string): Promise<GitObject | null> {
  const res = await fetch(`/api/session/${token}/object/${scenario}/${sha}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`getObject: ${res.status}`);
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
