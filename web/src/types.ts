export interface Commit {
  id: string;
  short: string;
  message: string;
  author: string;
  time: number; // unix seconds
  seeded: boolean;
  parents: string[];
}

export interface GitRef {
  name: string;
  target: string;
}

export interface Graph {
  commits: Commit[]; // topo order, newest first
  refs: GitRef[];
  head: string | null;
}

export type Level = "basic" | "intermediate" | "advanced";

export interface Scenario {
  id: string;
  title: string;
  level: Level;
  instructions: string;
  "clone-url": string;
  status: "not-started" | "in-progress" | "complete";
}

export interface SessionEvent {
  type: "fetch" | "evaluation" | "teammate" | "reset";
  scenario: string;
  at: string;
  "pass?"?: boolean;
  message?: string;
}

export interface Session {
  token: string;
  "created-at": string;
  scenarios: Scenario[];
  events: SessionEvent[];
}
