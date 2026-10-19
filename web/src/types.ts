export interface Commit {
  id: string;
  short: string;
  message: string;
  author: string;
  time: number; // unix seconds
  seeded: boolean;
  /** In the object store, but no branch reaches it (rejected push,
   *  pre-rewrite original). Rendered translucent/dashed. */
  ghost: boolean;
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

export interface Step {
  id: string;
  title: string | null;
  instructions: string | null;
  hints: string[];
  status: "complete" | "current" | "upcoming";
}

export interface Scenario {
  id: string;
  title: string;
  level: Level;
  instructions: string;
  "clone-url": string;
  status: "not-started" | "in-progress" | "complete";
  steps: Step[];
  "current-step": number;
}

export interface PushCommand {
  ref: string;
  old: string;
  new: string;
  result: string; // "OK" | "REJECTED_OTHER_REASON" | ...
  message?: string;
}

export interface SessionEvent {
  type: "fetch" | "evaluation" | "teammate" | "reset" | "step" | "push";
  scenario: string;
  at: string;
  "pass?"?: boolean;
  message?: string;
  /** branch -> tip hash, the remote's state the moment this happened. */
  refs?: Record<string, string>;
  /** push events only: every ref update the client asked for. */
  commands?: PushCommand[];
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

export interface ObjectEntry {
  sha: string;
  otype: "commit" | "tree" | "blob";
  /** No branch reaches this object at the selected moment. */
  orphan: boolean;
}

export type GitObject =
  | {
      type: "commit";
      sha: string;
      tree: string;
      parents: string[];
      author: { name: string; email: string; when: string };
      committer: { name: string; email: string; when: string };
      message: string;
    }
  | {
      type: "tree";
      sha: string;
      entries: { name: string; mode: string; otype: "tree" | "blob"; sha: string }[];
    }
  | { type: "blob"; sha: string; size: number; binary: boolean; content: string | null }
  | { type: "other"; sha: string };

export interface Session {
  token: string;
  "created-at": string;
  scenarios: Scenario[];
  events: SessionEvent[];
}
