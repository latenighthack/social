import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { create } from "@bufbuild/protobuf";
import { ComponentSchema } from "../.test-build/gen/messages/v1/components_pb.js";
import { findPreviewText, MessagePreview } from "../.test-build/MessagePreview.js";
import { MessageComponent } from "../.test-build/MessageComponent.js";

const component = create(ComponentSchema, { contents: { case: "text", value: {
  text: "Before secret after", inlines: [{ offset: 7, length: 6, rule: { contents: { case: "redaction", value: {} } } }],
} } });
test("redaction is concealed in previews, rendered text, and accessibility markup", () => {
  assert.equal(findPreviewText(component), "Before ██████ after");
  for (const view of [MessagePreview, MessageComponent]) {
    const html = renderToStaticMarkup(React.createElement(view, { component }));
    assert.ok(!html.includes("secret"));
    assert.ok(html.includes("██████"));
  }
});
