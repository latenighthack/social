import test from "node:test";
import assert from "node:assert/strict";
import { create } from "@bufbuild/protobuf";
import { ComponentSchema } from "../.test-build/gen/messages/v1/components_pb.js";
import { MessageComponent } from "../.test-build/MessageComponent.js";

function descendants(node) {
  if (!node || typeof node !== "object") return [];
  const children = node.props?.children;
  return [node, ...[children].flat(Infinity).flatMap(descendants)];
}
test("attached actions and inline actions support keyboard activation without bubbling", () => {
  const component = create(ComponentSchema, { action: {}, contents: { case: "text", value: {
    text: "link", inlines: [{ offset: 0, length: 4, rule: { contents: { case: "tappable", value: { action: {} } } } }],
  } } });
  const actions = [];
  const tree = MessageComponent({ component, onAction: (action) => actions.push(action) });
  const controls = descendants(tree).filter((node) => node.props?.role === "button");
  assert.equal(controls.length, 2);
  for (const control of controls) {
    assert.equal(control.props.tabIndex, 0);
    let stopped = false;
    control.props.onKeyDown({ key: "Enter", preventDefault() {}, stopPropagation() { stopped = true; } });
    assert.ok(stopped);
  }
  assert.equal(actions.length, 2);
});
