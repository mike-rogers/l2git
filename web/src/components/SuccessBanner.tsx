import { useEffect, useRef } from "react";
import type { Scenario } from "../types";
import type { Celebration } from "../hooks/useSession";

export default function SuccessBanner({
  celebration,
  scenarios,
  onNext,
  onDismiss,
}: {
  celebration: Celebration;
  scenarios: Scenario[];
  onNext: (id: string) => void;
  onDismiss: () => void;
}) {
  const done = scenarios.find((s) => s.id === celebration.scenario);
  const next = scenarios.find((s) => s.status !== "complete");
  // The banner sits below the lesson text (where the post-completion
  // debrief will live), so make sure it's actually seen when it lands.
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    ref.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, []);
  return (
    <div className="banner" role="status" ref={ref}>
      <span className="banner-check">✓</span>
      <div className="banner-text">
        <strong>{done?.title ?? celebration.scenario} — complete!</strong>
        <span className="banner-msg">{celebration.message}</span>
      </div>
      {next ? (
        <button className="banner-next" onClick={() => onNext(next.id)}>
          On to the next scenario →
        </button>
      ) : (
        <span className="banner-done">That was the last one — more scenarios coming soon.</span>
      )}
      <button className="banner-x" onClick={onDismiss} aria-label="dismiss">
        ×
      </button>
    </div>
  );
}
