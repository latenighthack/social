import type { Text } from "./gen/messages/v1/components_pb.js";

/** Display and accessibility text must both conceal redacted UTF-16 ranges. */
export function redactedText(text: Text): string {
  const units = text.text.split("");
  for (const inline of text.inlines) {
    if (inline.rule?.contents.case !== "redaction") continue;
    const start = Math.max(0, Math.min(inline.offset, units.length));
    const end = Math.max(start, Math.min(inline.offset + Math.max(0, inline.length), units.length));
    units.fill("█", start, end);
  }
  return units.join("");
}
