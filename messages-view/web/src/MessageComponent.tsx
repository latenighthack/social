import React from "react";
import type { CSSProperties, ReactNode } from "react";
import {
  type Action,
  type Component,
  type Container,
  type Image,
  type Inline,
  type Text,
  Button_Style,
  Container_Grid_Style,
  Container_HorizontalAlignment,
  Container_VerticalAlignment,
  Image_Style,
  Text_Style,
} from "./gen/messages/v1/components_pb.js";
import { incomingTheme, outgoingTheme, type MessageTheme } from "./theme.js";

export type ActionHandler = (action: Action) => void;

export interface MessageComponentProps {
  component: Component;
  /** Full visual config. If omitted, `incoming` picks a built-in incoming/outgoing theme. */
  theme?: MessageTheme;
  /** Convenience when no explicit theme is given: true = incoming (neutral), false = outgoing (accent). */
  incoming?: boolean;
  /** Invoked when a Button, tappable inline, or a node with an attached Action is activated. */
  onAction?: ActionHandler;
  style?: CSSProperties;
}

type Axis = "row" | "column";

interface Ctx {
  theme: MessageTheme;
  onAction?: ActionHandler;
  inOverlay: boolean;
  axis: Axis;
}

/** Renders a single message's Component tree into React nodes. */
export function MessageComponent(props: MessageComponentProps): React.JSX.Element {
  const theme = props.theme ?? (props.incoming === false ? outgoingTheme : incomingTheme);
  const ctx: Ctx = { theme, onAction: props.onAction, inOverlay: false, axis: "column" };
  return (
    <div style={{ fontFamily: theme.fontFamily, ...props.style }}>
      {renderComponent(props.component, ctx, 0)}
    </div>
  );
}

function renderComponent(component: Component, ctx: Ctx, key: number): ReactNode {
  const c = component.contents;
  switch (c.case) {
    case "container":
      return renderContainer(component, c.value, ctx, key);
    case "text":
      return renderText(c.value, ctx, key);
    case "image":
      return renderImage(c.value, ctx, key);
    case "button":
      return renderButton(component, c.value, ctx, key);
    case "divider":
      return renderDivider(ctx, key);
    default:
      return null;
  }
}

function renderChildren(container: Container, childCtx: Ctx): ReactNode[] {
  return container.children.map((child, i) => renderComponent(child, childCtx, i));
}

function renderContainer(
  component: Component,
  container: Container,
  ctx: Ctx,
  key: number,
): ReactNode {
  const k = container.contents.case;
  switch (k) {
    case "verticalStack": {
      const align = container.contents.value.alignment;
      const childCtx: Ctx = { ...ctx, axis: "column" };
      return (
        <div key={key} style={{ display: "flex", flexDirection: "column", alignItems: hAlign(align) }}>
          {renderChildren(container, childCtx)}
        </div>
      );
    }
    case "horizontalStack": {
      const align = container.contents.value.alignment;
      const childCtx: Ctx = { ...ctx, axis: "row" };
      return (
        <div key={key} style={{ display: "flex", flexDirection: "row", alignItems: vAlign(align) }}>
          {renderChildren(container, childCtx)}
        </div>
      );
    }
    case "grid": {
      const grid = container.contents.value;
      const cols = grid.columns.length > 0
        ? grid.columns.map((col) => `${Math.max(col.weight, 1)}fr`).join(" ")
        : "1fr";
      const bordered = grid.style === Container_Grid_Style.BORDER;
      return (
        <div
          key={key}
          style={{
            display: "grid",
            gridTemplateColumns: cols,
            border: bordered ? `1px solid ${ctx.theme.dividerColor}` : undefined,
            gap: bordered ? 1 : 0,
            backgroundColor: bordered ? ctx.theme.dividerColor : undefined,
          }}
        >
          {container.children.map((child, i) => (
            <div
              key={i}
              style={{
                backgroundColor:
                  grid.style === Container_Grid_Style.STRIPED && Math.floor(i / Math.max(grid.columns.length, 1)) % 2 === 1
                    ? "rgba(255,255,255,0.04)"
                    : bordered
                      ? "#1b1a24"
                      : undefined,
                padding: bordered || grid.style === Container_Grid_Style.STRIPED ? 6 : 0,
              }}
            >
              {renderComponent(child, { ...ctx, axis: "column" }, i)}
            </div>
          ))}
        </div>
      );
    }
    case "bubble": {
      const childCtx: Ctx = { ...ctx, axis: "column" };
      return (
        <div
          key={key}
          style={{
            display: "inline-flex",
            flexDirection: "column",
            backgroundColor: ctx.theme.bubbleColor,
            borderRadius: ctx.theme.bubbleRadius,
            padding: "10px 16px",
            maxWidth: ctx.theme.bubbleMaxWidth,
            overflow: "hidden",
          }}
        >
          {renderChildren(container, childCtx)}
        </div>
      );
    }
    case "overlay": {
      const childCtx: Ctx = { ...ctx, inOverlay: true, axis: "column" };
      return (
        <div key={key} style={{ position: "relative", display: "inline-block" }}>
          <div
            style={{
              position: "absolute",
              inset: 0,
              backgroundColor: "rgba(0,0,0,0.38)",
              borderRadius: 12,
              pointerEvents: "none",
            }}
          />
          <div style={{ position: "relative", padding: "12px 16px" }}>
            {renderChildren(container, childCtx)}
          </div>
        </div>
      );
    }
    case "quote": {
      const childCtx: Ctx = { ...ctx, axis: "column" };
      return (
        <div key={key} style={{ borderLeft: `3px solid ${ctx.theme.dividerColor}`, paddingLeft: 12 }}>
          {renderChildren(container, childCtx)}
        </div>
      );
    }
    case "box":
    default: {
      const childCtx: Ctx = { ...ctx, axis: "column" };
      return (
        <div key={key} style={{ display: "flex", flexDirection: "column" }}>
          {renderChildren(container, childCtx)}
        </div>
      );
    }
  }
}

function textColorFor(style: Text_Style, ctx: Ctx): string {
  if (ctx.inOverlay) return ctx.theme.overlayTextColor;
  switch (style) {
    case Text_Style.TITLE:
      return ctx.theme.titleColor;
    case Text_Style.SUBTITLE:
      return ctx.theme.subtitleColor;
    case Text_Style.DESCRIPTION:
      return ctx.theme.descriptionColor;
    default:
      return ctx.theme.textColor;
  }
}

function textSizeFor(style: Text_Style, ctx: Ctx): number {
  switch (style) {
    case Text_Style.TITLE:
      return ctx.theme.titleTextSize;
    case Text_Style.SUBTITLE:
      return ctx.theme.subtitleTextSize;
    case Text_Style.DESCRIPTION:
      return ctx.theme.descriptionTextSize;
    default:
      return ctx.theme.defaultTextSize;
  }
}

function renderText(text: Text, ctx: Ctx, key: number): ReactNode {
  const clamp = text.style === Text_Style.TITLE ? 1 : text.style === Text_Style.SUBTITLE ? 2 : undefined;
  const style: CSSProperties = {
    margin: 0,
    color: textColorFor(text.style, ctx),
    fontSize: textSizeFor(text.style, ctx),
    fontWeight: text.style === Text_Style.TITLE ? 700 : 400,
    whiteSpace: "pre-wrap",
    wordBreak: "break-word",
  };
  if (clamp) {
    Object.assign(style, {
      display: "-webkit-box",
      WebkitLineClamp: clamp,
      WebkitBoxOrient: "vertical",
      overflow: "hidden",
      whiteSpace: "normal",
    });
  }
  return (
    <p key={key} style={style}>
      {text.inlines.length > 0 ? renderInlines(text.text, text.inlines, ctx) : text.text}
    </p>
  );
}

// Splits the string at every inline boundary and renders each run with the union of
// rules covering it, so overlapping inlines (e.g. bold + italic) compose correctly.
function renderInlines(source: string, inlines: Inline[], ctx: Ctx): ReactNode[] {
  const chars = Array.from(source);
  const boundaries = new Set<number>([0, chars.length]);
  for (const inline of inlines) {
    boundaries.add(clampIndex(inline.offset, chars.length));
    boundaries.add(clampIndex(inline.offset + inline.length, chars.length));
  }
  const cuts = Array.from(boundaries).sort((a, b) => a - b);
  const runs: ReactNode[] = [];
  for (let i = 0; i < cuts.length - 1; i++) {
    const start = cuts[i];
    const end = cuts[i + 1];
    if (end <= start) continue;
    const active = inlines.filter(
      (inline) => inline.offset <= start && inline.offset + inline.length >= end,
    );
    runs.push(renderRun(chars.slice(start, end).join(""), active, ctx, i));
  }
  return runs;
}

function renderRun(runText: string, active: Inline[], ctx: Ctx, key: number): ReactNode {
  const style: CSSProperties = {};
  let onClick: (() => void) | undefined;
  let iconUrl: string | undefined;
  let redacted = false;

  for (const inline of active) {
    const rule = inline.rule?.contents;
    switch (rule?.case) {
      case "bold":
        style.fontWeight = 700;
        break;
      case "italic":
        style.fontStyle = "italic";
        break;
      case "strikethrough":
        style.textDecoration = style.textDecoration
          ? `${style.textDecoration} line-through`
          : "line-through";
        break;
      case "tappable": {
        style.color = ctx.theme.linkColor;
        style.cursor = "pointer";
        style.textDecoration = "underline";
        const action = rule.value.action;
        if (action && ctx.onAction) onClick = () => ctx.onAction!(action);
        break;
      }
      case "userLink":
        style.color = ctx.theme.linkColor;
        style.fontWeight = 600;
        break;
      case "redaction":
        redacted = true;
        break;
      case "icon":
        iconUrl = rule.value.image?.url;
        break;
      default:
        break;
    }
  }

  if (iconUrl) {
    return (
      <img
        key={key}
        src={iconUrl}
        alt=""
        style={{ display: "inline-block", height: "1em", verticalAlign: "-0.15em" }}
      />
    );
  }

  if (redacted) {
    return (
      <span
        key={key}
        style={{
          ...style,
          backgroundColor: ctx.theme.redactionColor,
          color: "transparent",
          borderRadius: 3,
        }}
      >
        {runText}
      </span>
    );
  }

  return (
    <span key={key} style={style} onClick={onClick} role={onClick ? "button" : undefined}>
      {runText}
    </span>
  );
}

function renderImage(image: Image, ctx: Ctx, key: number): ReactNode {
  const ref = image.image;
  const url = ref?.url ?? "";
  const aspect = ref && ref.aspectRatio > 0 ? ref.aspectRatio : 1;
  const placeholder = ref ? argbToCss(ref.previewColor) : "transparent";

  const base: CSSProperties = { backgroundColor: placeholder, objectFit: "cover", display: "block" };
  switch (image.style) {
    case Image_Style.SMALL:
      return <img key={key} src={url} alt={ref?.alternateText ?? ""} style={{ ...base, width: 64, height: 64 / aspect, borderRadius: 8 }} />;
    case Image_Style.MEDIUM:
      return <img key={key} src={url} alt={ref?.alternateText ?? ""} style={{ ...base, width: 160, height: 160 / aspect, borderRadius: 10 }} />;
    case Image_Style.SQUARE:
      return <img key={key} src={url} alt={ref?.alternateText ?? ""} style={{ ...base, width: "100%", aspectRatio: "1 / 1" }} />;
    case Image_Style.CIRCULAR:
      return <img key={key} src={url} alt={ref?.alternateText ?? ""} style={{ ...base, width: 64, height: 64, borderRadius: "50%" }} />;
    default:
      return <img key={key} src={url} alt={ref?.alternateText ?? ""} style={{ ...base, width: "100%", aspectRatio: `${aspect} / 1`, borderRadius: 12 }} />;
  }
}

function renderButton(component: Component, button: { text: string; style: Button_Style }, ctx: Ctx, key: number): ReactNode {
  const grouped = button.style === Button_Style.GROUPED;
  const cta = button.style === Button_Style.CTA;
  const pill = button.style === Button_Style.PILL;
  const style: CSSProperties = {
    display: "block",
    width: "100%",
    minHeight: 36,
    border: "none",
    cursor: ctx.onAction ? "pointer" : "default",
    fontSize: ctx.theme.defaultTextSize,
    fontFamily: "inherit",
    padding: "8px 16px",
    color: cta ? "#ffffff" : ctx.theme.linkColor,
    backgroundColor: cta ? ctx.theme.linkColor : "transparent",
    borderTop: grouped ? `1px solid ${ctx.theme.dividerColor}` : undefined,
    borderRadius: pill ? 18 : cta ? 10 : 0,
    textAlign: "center",
  };
  const action = component.action;
  return (
    <button key={key} style={style} onClick={action && ctx.onAction ? () => ctx.onAction!(action) : undefined}>
      {button.text}
    </button>
  );
}

function renderDivider(ctx: Ctx, key: number): ReactNode {
  if (ctx.axis === "row") {
    return <div key={key} style={{ alignSelf: "stretch", width: 1, backgroundColor: ctx.theme.dividerColor }} />;
  }
  return <div key={key} style={{ width: "100%", height: 1, backgroundColor: ctx.theme.dividerColor }} />;
}

function hAlign(a: Container_HorizontalAlignment): CSSProperties["alignItems"] {
  switch (a) {
    case Container_HorizontalAlignment.LEFT:
      return "flex-start";
    case Container_HorizontalAlignment.CENTER_HORIZONTAL:
      return "center";
    case Container_HorizontalAlignment.RIGHT:
      return "flex-end";
    default:
      return "stretch";
  }
}

function vAlign(a: Container_VerticalAlignment): CSSProperties["alignItems"] {
  switch (a) {
    case Container_VerticalAlignment.CENTER_VERTICAL:
      return "center";
    case Container_VerticalAlignment.BOTTOM:
      return "flex-end";
    default:
      return "flex-start";
  }
}

function clampIndex(i: number, len: number): number {
  return Math.max(0, Math.min(i, len));
}

// ImageReference.preview_color is a packed ARGB int.
function argbToCss(argb: number): string {
  if (!argb) return "transparent";
  const a = ((argb >>> 24) & 0xff) / 255;
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  return `rgba(${r},${g},${b},${a})`;
}
