import { useCallback, useState } from "react";
import EventLog from "./components/EventLog";
import Graph from "./components/Graph";
import Inspector from "./components/Inspector";
import ScenarioCard from "./components/ScenarioCard";
import ScenarioTree from "./components/ScenarioTree";
import SuccessBanner from "./components/SuccessBanner";
import ToolPanel from "./components/ToolPanel";
import { useSession } from "./hooks/useSession";

type RightTab = "activity" | null;

/** Composition only: the IDE frame (masthead, three edge tool windows, the
 * lesson center stage, the inspector popover) and its layout state. All
 * server state lives in useSession. */
export default function App() {
  const {
    session,
    graph,
    error,
    current,
    celebration,
    selectScenario,
    goToScenario,
    clearCelebration,
    reset,
  } = useSession();

  // IDE-style tool windows: collapsible edge panels around the lesson.
  const [leftOpen, setLeftOpen] = useState(true);
  const [rightTab, setRightTab] = useState<RightTab>("activity");
  const [bottomOpen, setBottomOpen] = useState(true);
  // Bottom panel height: fixed (~3.5 graph rows) by default, drag to taste.
  const [bottomH, setBottomH] = useState(196);
  // The under-the-hood popover: scrubber + object database.
  const [inspecting, setInspecting] = useState(false);

  const startBottomResize = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      const startY = e.clientY;
      const startH = bottomH;
      const move = (ev: MouseEvent) =>
        setBottomH(
          Math.min(Math.max(startH + (startY - ev.clientY), 110), window.innerHeight * 0.7),
        );
      const up = () => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    },
    [bottomH],
  );

  if (error) return <div className="boot-error">Can’t reach the l2git server: {error}</div>;
  if (!session || !current) return <div className="boot">warming up…</div>;

  return (
    <div className="ide">
      <header className="masthead">
        <h1>l2git</h1>
        <span className="tagline">git good, newb</span>
        <span className="token" title="Bookmark this page — the token is your session.">
          session <code>{session.token}</code>
        </span>
      </header>
      <div className="ide-body">
        {leftOpen ? (
          <ToolPanel
            className="panel-left"
            title="Scenarios"
            open
            onToggle={() => setLeftOpen(false)}
            closeGlyph="⟨"
            toggleLabel="Collapse scenarios panel"
          >
            <ScenarioTree
              scenarios={session.scenarios}
              selectedId={current.id}
              onSelect={selectScenario}
            />
          </ToolPanel>
        ) : (
          <button
            className="panel-stub stub-left"
            onClick={() => setLeftOpen(true)}
            title="Open scenarios panel"
          >
            <span className="stub-label">Scenarios ⟩</span>
          </button>
        )}
        <main className="lesson">
          <ScenarioCard scenario={current} onReset={reset} />
          {celebration && celebration.scenario === current.id && (
            // Below the lesson text, above where the post-completion
            // debrief/theory will unlock — the hinge between "what you
            // did" and "why it worked".
            <SuccessBanner
              celebration={celebration}
              scenarios={session.scenarios}
              onNext={goToScenario}
              onDismiss={clearCelebration}
            />
          )}
        </main>
        {rightTab === "activity" && (
          <ToolPanel
            className="panel-right"
            title="Activity"
            open
            onToggle={() => setRightTab(null)}
            closeGlyph="⟩"
            toggleLabel="Collapse activity panel"
          >
            <EventLog events={session.events} />
          </ToolPanel>
        )}
        <nav className="rail" aria-label="tool windows">
          <button
            className={rightTab === "activity" ? "active" : ""}
            onClick={() => setRightTab(rightTab === "activity" ? null : "activity")}
            title="Activity log"
          >
            Activity
          </button>
        </nav>
      </div>
      <ToolPanel
        className="panel-bottom"
        title={
          <>
            <code>origin</code>&rsquo;s commit graph{" "}
            <span className="pulse" title="updates as you push" />
          </>
        }
        open={bottomOpen}
        onToggle={() => setBottomOpen(!bottomOpen)}
        closeGlyph="⌄"
        openGlyph="⌃"
        toggleLabel={bottomOpen ? "Collapse remote panel" : "Open remote panel"}
        style={bottomOpen ? { height: bottomH } : undefined}
        actions={
          <button
            className="panel-toggle dive"
            onClick={() => setInspecting(true)}
            title="Under the hood: the object database, over time"
            aria-label="Open the object database inspector"
          >
            ⌬
          </button>
        }
        extra={
          bottomOpen && (
            <div
              className="resize-handle"
              onMouseDown={startBottomResize}
              title="Drag to resize"
              aria-hidden
            />
          )
        }
      >
        {graph ? <Graph data={graph} /> : <p className="muted">loading graph…</p>}
      </ToolPanel>
      {inspecting && (
        <Inspector
          token={session.token}
          scenarioId={current.id}
          eventCount={session.events.length}
          onClose={() => setInspecting(false)}
        />
      )}
    </div>
  );
}
