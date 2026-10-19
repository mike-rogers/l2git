/** Where the learner left the inspector, per session+scenario, so closing
 * and reopening the popover doesn't lose their place. Deliberately a
 * module-level map: cheap, survives unmount, gone on reload. */

export interface InspectorMemory {
  at: string | null;
  selectedSha: string | null;
  highlightOrphans: boolean;
}

const memory = new Map<string, InspectorMemory>();

export function useInspectorMemory(
  key: string,
): [InspectorMemory | undefined, (m: InspectorMemory) => void] {
  return [memory.get(key), (m) => memory.set(key, m)];
}
