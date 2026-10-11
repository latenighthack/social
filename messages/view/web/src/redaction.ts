import { inlineRange } from "./inlineRange.js";
import type { Text } from "./gen/messages/v1/components_pb.js";

/** Display and accessibility text must both conceal redacted UTF-16 ranges. */
export function redactedText(text: Text): string {
  const units = text.text.split("");
  for (const inline of text.inlines) {
    if (inline.rule?.contents.case !== "redaction") continue;
    const [start, end] = inlineRange(text.text, inline.offset, inline.length);
    units.fill("█", start, end);
  }
  return units.join("");
}
