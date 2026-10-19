import type { CSSProperties, ReactNode } from "react";

/** The IDE tool-window chrome: a titled head with a collapse toggle and
 * optional extra actions, and a scrolling body shown while open. The three
 * edge panels share this; what differs (stubs, rails, resize handles)
 * stays with the caller. */
export default function ToolPanel({
  className,
  title,
  open,
  onToggle,
  closeGlyph,
  openGlyph,
  toggleLabel,
  actions,
  extra,
  style,
  children,
}: {
  className: string;
  title: ReactNode;
  open: boolean;
  onToggle: () => void;
  /** Shown on the toggle while open / while closed (closed state only
   * renders for panels that stay mounted, like the bottom one). */
  closeGlyph: string;
  openGlyph?: string;
  toggleLabel: string;
  /** Extra head buttons, placed before the toggle. */
  actions?: ReactNode;
  /** Rendered before the head — e.g. the bottom panel's resize handle. */
  extra?: ReactNode;
  style?: CSSProperties;
  children: ReactNode;
}) {
  return (
    <section className={`panel ${className}${open ? "" : " closed"}`} style={style}>
      {extra}
      <div className="panel-head">
        <span>{title}</span>
        <span className="panel-head-actions">
          {actions}
          <button
            className="panel-toggle"
            onClick={onToggle}
            title={toggleLabel}
            aria-label={toggleLabel}
          >
            {open ? closeGlyph : (openGlyph ?? closeGlyph)}
          </button>
        </span>
      </div>
      {open && <div className="panel-body">{children}</div>}
    </section>
  );
}
