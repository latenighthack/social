// @social/messages-view — React renderer for the messages.v1.Component tree.
//
//   <MessageComponent component={component} incoming />
//   <MessagePreview component={component} prefix="You: " />
//
// The generated protobuf-es types/schemas are re-exported so consumers (and the
// fixtures generator) can decode/encode Component bytes without a second dependency.

export { MessageComponent } from "./MessageComponent.js";
export type { MessageComponentProps, ActionHandler } from "./MessageComponent.js";
export { MessagePreview, findPreviewText } from "./MessagePreview.js";
export type { MessagePreviewProps } from "./MessagePreview.js";
export {
  incomingTheme,
  outgoingTheme,
  previewTheme,
  type MessageTheme,
} from "./theme.js";

export * from "./gen/messages/v1/components_pb.js";
export * from "./gen/messages/v1/model_pb.js";
