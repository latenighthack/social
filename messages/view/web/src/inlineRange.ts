/** Half-open UTF-16 range, shared with the Kotlin and Swift renderers. */
export function inlineRange(source: string, offset: number, length: number): [number, number] {
  const clamp = (value: number) => Math.max(0, Math.min(Math.trunc(value), source.length));
  let start = clamp(offset);
  if (length <= 0) return [start, start];
  let end = Math.max(start, clamp(offset + length));
  if (start === end) return [start, end];
  const high = (code: number) => code >= 0xd800 && code <= 0xdbff;
  const low = (code: number) => code >= 0xdc00 && code <= 0xdfff;
  if (start > 0 && low(source.charCodeAt(start)) && high(source.charCodeAt(start - 1))) start--;
  if (end < source.length && low(source.charCodeAt(end)) && high(source.charCodeAt(end - 1))) end++;
  return [start, end];
}
