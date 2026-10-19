import type { Level, Scenario } from "../types";

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

export default function ScenarioTree({
  scenarios,
  selectedId,
  onSelect,
}: {
  scenarios: Scenario[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  return (
    <nav className="tree">
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
