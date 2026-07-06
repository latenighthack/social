// Visual configuration for the message renderer. Mirrors the Android `MessageTheme`
// interface from the reference client: colours/sizes are supplied here rather than
// pulled from a global, so the renderer stays a pure, importable component.

export interface MessageTheme {
  fontFamily: string;
  // Text colours by Text.Style.
  textColor: string;
  titleColor: string;
  subtitleColor: string;
  descriptionColor: string;
  // Text colours when nested inside an Overlay container (drawn over a dark scrim).
  overlayTextColor: string;
  // Inline rule colours.
  linkColor: string;
  redactionColor: string;
  // Container colours.
  bubbleColor: string;
  dividerColor: string;
  // Text sizes (px) by Text.Style.
  defaultTextSize: number;
  titleTextSize: number;
  subtitleTextSize: number;
  descriptionTextSize: number;
  // Bubble geometry.
  bubbleRadius: number;
  bubbleMaxWidth: number;
}

const BASE: Omit<MessageTheme, "textColor" | "bubbleColor"> = {
  fontFamily:
    '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
  titleColor: "#ffffff",
  subtitleColor: "rgba(255,255,255,0.7)",
  descriptionColor: "rgba(255,255,255,0.5)",
  overlayTextColor: "#ffffff",
  linkColor: "#8E84FA",
  redactionColor: "rgba(0,0,0,0.85)",
  dividerColor: "rgba(255,255,255,0.15)",
  defaultTextSize: 16,
  titleTextSize: 18,
  subtitleTextSize: 15,
  descriptionTextSize: 13,
  bubbleRadius: 18,
  bubbleMaxWidth: 260,
};

// Message from someone else: neutral dark bubble.
export const incomingTheme: MessageTheme = {
  ...BASE,
  textColor: "#ffffff",
  bubbleColor: "#262532",
};

// Our own message: Roll purple bubble.
export const outgoingTheme: MessageTheme = {
  ...BASE,
  textColor: "#ffffff",
  bubbleColor: "#7924FF",
};

// Compact preview used in a room/conversation list row.
export const previewTheme: MessageTheme = {
  ...BASE,
  textColor: "rgba(255,255,255,0.6)",
  titleColor: "rgba(255,255,255,0.6)",
  subtitleColor: "rgba(255,255,255,0.6)",
  descriptionColor: "rgba(255,255,255,0.6)",
  bubbleColor: "transparent",
};
