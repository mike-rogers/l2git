import { useState } from "react";
import type { Scenario } from "../types";

function CloneUrl({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);
  const cmd = `git clone ${url}`;
  return (
    <div className="clone-url">
      <code>{cmd}</code>
      <button
        onClick={() => {
          navigator.clipboard.writeText(cmd).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          });
        }}
      >
        {copied ? "copied!" : "copy"}
      </button>
    </div>
  );
}

function HintLadder({ hints }: { hints: string[] }) {
  const [shown, setShown] = useState(0);
  if (hints.length === 0) return null;
  return (
    <div className="hints">
      {hints.slice(0, shown).map((h, i) => (
        <div className="hint" key={i}>
          <span className="hint-n">hint {i + 1}</span>
          {h}
        </div>
      ))}
      {shown < hints.length && (
        <button className="hint-btn" onClick={() => setShown(shown + 1)}>
          {shown === 0 ? "Need a hint?" : "Another hint?"}
          <span className="hint-count"> ({hints.length - shown} available)</span>
        </button>
      )}
    </div>
  );
}

export default function ScenarioCard({
  scenario,
  onReset,
}: {
  scenario: Scenario;
  onReset: () => void;
}) {
  const steps = scenario.steps;
  const multi = steps.length > 1;
  const current = steps.find((s) => s.status === "current") ?? null;
  return (
    <article className={`scenario ${scenario.status}`}>
      <header>
        <h2>{scenario.title}</h2>
        <span className={`status-pill ${scenario.status}`}>
          {scenario.status === "complete" ? "✓ complete" : scenario.status.replace("-", " ")}
        </span>
      </header>
      <p>{scenario.instructions}</p>
      {multi && (
        <ol className="steps">
          {steps.map((st, i) => (
            <li key={st.id} className={`step ${st.status}`}>
              <span className="step-glyph">{st.status === "complete" ? "✓" : i + 1}</span>
              <span>{st.title ?? `Step ${i + 1}`}</span>
            </li>
          ))}
        </ol>
      )}
      {current?.instructions && <p className="step-instructions">{current.instructions}</p>}
      {current && <HintLadder key={`${scenario.id}:${current.id}`} hints={current.hints} />}
      <CloneUrl url={scenario["clone-url"]} />
      {scenario.status !== "not-started" && (
        <button
          className="reset-btn"
          onClick={() => {
            if (
              window.confirm(
                "Reset this scenario to its starting state? Your pushed work on it is discarded — delete your local clone and clone fresh afterwards.",
              )
            )
              onReset();
          }}
        >
          ↺ reset scenario
        </button>
      )}
    </article>
  );
}
