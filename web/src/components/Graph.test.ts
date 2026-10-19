import { describe, expect, it } from "vitest";
import { layout } from "./Graph";
import type { Commit } from "../types";

/** Minimal commit; the layout only reads id and parents. */
const c = (id: string, parents: string[]): Commit => ({
  id,
  short: id.slice(0, 7),
  message: "m",
  author: "a",
  time: 0,
  seeded: false,
  ghost: false,
  parents,
});

const lanesOf = (commits: Commit[]) =>
  Object.fromEntries(layout(commits).placed.map((p) => [p.commit.id, p.lane]));

describe("layout", () => {
  it("keeps a linear chain in one lane", () => {
    const { placed, lanes } = layout([c("c3", ["c2"]), c("c2", ["c1"]), c("c1", [])]);
    expect(lanes).toBe(1);
    expect(placed.map((p) => p.lane)).toEqual([0, 0, 0]);
    expect(placed.map((p) => p.row)).toEqual([0, 1, 2]);
  });

  it("gives a side branch its own lane while the base keeps lane 0", () => {
    // newest-first topo order: tip of main, tip of branch, shared base
    const lanes = lanesOf([c("main2", ["base"]), c("side1", ["base"]), c("base", [])]);
    expect(lanes.main2).toBe(0);
    expect(lanes.side1).toBe(1);
    expect(lanes.base).toBe(0); // reserved by its first-seen child's first-parent slot
  });

  it("routes a merge's second parent to a new lane", () => {
    const lanes = lanesOf([
      c("merge", ["a", "b"]),
      c("b", ["base"]),
      c("a", ["base"]),
      c("base", []),
    ]);
    expect(lanes.merge).toBe(0);
    expect(lanes.a).toBe(0); // first parent inherits the child's lane
    expect(lanes.b).toBe(1);
    expect(lanes.base).toBe(0);
  });

  it("survives a ghost sibling without stealing the trunk's lane", () => {
    // the teammate-pull ending: rebased tip, Pat, orphaned original, seed
    const lanes = lanesOf([
      c("tip", ["pat"]),
      c("pat", ["seed"]),
      c("ghost", ["seed"]),
      c("seed", []),
    ]);
    expect(lanes.tip).toBe(0);
    expect(lanes.pat).toBe(0);
    expect(lanes.ghost).toBe(1);
    expect(lanes.seed).toBe(0);
  });

  it("reports at least one lane for an empty graph", () => {
    expect(layout([]).lanes).toBe(1);
  });
});
