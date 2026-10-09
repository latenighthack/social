import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fromBinary } from "@bufbuild/protobuf";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ComponentSchema } from "../.test-build/gen/messages/v1/components_pb.js";
import { findPreviewText } from "../.test-build/MessagePreview.js";
import { MessageComponent } from "../.test-build/MessageComponent.js";
import { inlineRange } from "../.test-build/inlineRange.js";

test("shared binary Unicode fixture uses UTF-16 ranges", () => {
  const fixture = fromBinary(ComponentSchema, readFileSync(new URL("../../demo/bundles/unicode-rich.pb", import.meta.url)));
  assert.equal(findPreviewText(fixture), "😀 café é ██████");
  const html = renderToStaticMarkup(React.createElement(MessageComponent, { component: fixture }));
  assert.match(html, /font-weight:700[^>]*>café</);
  assert.match(html, /font-style:italic[^>]*>é</);
  assert.ok(!html.includes("secret"));
  assert.deepEqual(inlineRange("😀x", 1, 1), [0, 2]);
  assert.deepEqual(inlineRange("😀x", 1, 0), [1, 1]);
});
