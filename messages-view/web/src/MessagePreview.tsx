import React from "react";
import type { CSSProperties } from "react";
import { type Component, type Text, Text_Style } from "./gen/messages/v1/components_pb.js";
import { previewTheme, type MessageTheme } from "./theme.js";

export interface MessagePreviewProps {
  /** The message to preview, or null for an empty room. */
  component: Component | null | undefined;
  theme?: MessageTheme;
  /** Optional lead-in (e.g. "You: "). */
  prefix?: string;
  /** Shown when there is no message. */
  emptyText?: string;
  style?: CSSProperties;
}

/** The single-line text preview used in a room/conversation list row. */
export function MessagePreview(props: MessagePreviewProps): React.JSX.Element {
  const theme = props.theme ?? previewTheme;
  const text = props.component ? findPreviewText(props.component) : null;
  const empty = text === null;
  const content = empty ? (props.emptyText ?? "No messages yet") : `${props.prefix ?? ""}${text}`;
  const style: CSSProperties = {
    margin: 0,
    fontFamily: theme.fontFamily,
    color: theme.textColor,
    fontSize: theme.defaultTextSize,
    fontStyle: empty ? "italic" : "normal",
    whiteSpace: "nowrap",
    overflow: "hidden",
    textOverflow: "ellipsis",
    ...props.style,
  };
  return <p style={style}>{content}</p>;
}

// Depth-first search for renderable preview text, mirroring the reference client's
// findPreviewText: prefer the first Text node, ordered DEFAULT > TITLE > SUBTITLE >
// DESCRIPTION; fall back to an image's alternate text.
export function findPreviewText(component: Component): string | null {
  const found: Text[] = [];
  const images: string[] = [];
  collect(component, found, images);
  if (found.length > 0) {
    const priority = [Text_Style.DEFAULT, Text_Style.TITLE, Text_Style.SUBTITLE, Text_Style.DESCRIPTION];
    for (const style of priority) {
      const match = found.find((t) => t.style === style);
      if (match && match.text.trim().length > 0) return match.text.trim();
    }
    const any = found.find((t) => t.text.trim().length > 0);
    if (any) return any.text.trim();
  }
  if (images.length > 0) return images[0];
  return null;
}

function collect(component: Component, texts: Text[], images: string[]): void {
  const c = component.contents;
  switch (c.case) {
    case "text":
      texts.push(c.value);
      break;
    case "image":
      images.push(c.value.image?.alternateText || "Photo");
      break;
    case "container":
      for (const child of c.value.children) collect(child, texts, images);
      break;
    default:
      break;
  }
}
