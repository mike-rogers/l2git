import type { SessionEvent } from "../types";

export default function EventLog({ events }: { events: SessionEvent[] }) {
  if (events.length === 0)
    return <p className="muted panel-empty">Nothing yet — clone the repo to begin.</p>;
  return (
    <ul className="events">
      {[...events].reverse().map((e, i) => (
        <li key={`${e.at}-${i}`} className={e["pass?"] === false ? "fail" : e["pass?"] ? "pass" : ""}>
          {e.type === "fetch" ? (
            <span>⇣ repository fetched</span>
          ) : e.type === "push" ? (
            <span>
              ⇡ push {e.commands?.some((c) => c.result !== "OK") ? "rejected" : "received"}
            </span>
          ) : e.type === "teammate" ? (
            <span className="teammate">⇡ {e.message}</span>
          ) : e.type === "reset" ? (
            <span className="muted">↺ {e.message}</span>
          ) : e.type === "step" ? (
            <span className="step-ev">✓ {e.message}</span>
          ) : (
            <span>
              {e["pass?"] ? "✓" : "✗"} {e.message}
            </span>
          )}
          <time>{new Date(e.at).toLocaleTimeString()}</time>
        </li>
      ))}
    </ul>
  );
}
