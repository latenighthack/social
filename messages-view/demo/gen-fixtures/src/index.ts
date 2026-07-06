// Authors the canonical binary Component fixtures shared by all three demos.
// One set of bytes -> three renderers -> screenshots that should agree.
//
// Each fixture is a serialized messages.v1.Component written to demo/bundles/<name>.pb,
// plus a manifest.json describing how each should be rendered (incoming vs outgoing,
// full message vs single-line preview).

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { create, toBinary, type MessageInitShape } from "@bufbuild/protobuf";
import {
  ComponentSchema,
  Button_Style,
  Container_Grid_Style,
  Container_HorizontalAlignment,
  Container_VerticalAlignment,
  Image_Style,
  Text_Style,
} from "@social/messages-view";

type ComponentInit = MessageInitShape<typeof ComponentSchema>;
type Mode = "message" | "preview";

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(__dirname, "../../bundles");

// ---- builders -------------------------------------------------------------

const comp = (contents: ComponentInit["contents"]): ComponentInit => ({ contents });

const text = (value: string, style: Text_Style = Text_Style.DEFAULT, inlines: TextInline[] = []): ComponentInit =>
  comp({ case: "text", value: { text: value, style, inlines } });

const bubble = (...children: ComponentInit[]): ComponentInit =>
  comp({ case: "container", value: { children, contents: { case: "bubble", value: {} } } });

const box = (...children: ComponentInit[]): ComponentInit =>
  comp({ case: "container", value: { children, contents: { case: "box", value: {} } } });

const vstack = (alignment: Container_HorizontalAlignment, ...children: ComponentInit[]): ComponentInit =>
  comp({ case: "container", value: { children, contents: { case: "verticalStack", value: { alignment } } } });

const hstack = (alignment: Container_VerticalAlignment, ...children: ComponentInit[]): ComponentInit =>
  comp({ case: "container", value: { children, contents: { case: "horizontalStack", value: { alignment } } } });

const overlay = (...children: ComponentInit[]): ComponentInit =>
  comp({ case: "container", value: { children, contents: { case: "overlay", value: {} } } });

const quote = (...children: ComponentInit[]): ComponentInit =>
  comp({ case: "container", value: { children, contents: { case: "quote", value: {} } } });

const grid = (columns: number[], style: Container_Grid_Style, ...children: ComponentInit[]): ComponentInit =>
  comp({
    case: "container",
    value: { children, contents: { case: "grid", value: { columns: columns.map((weight) => ({ weight })), style } } },
  });

const divider = (): ComponentInit => comp({ case: "divider", value: {} });

const button = (label: string, style: Button_Style): ComponentInit =>
  comp({ case: "button", value: { text: label, style } });

const image = (previewColor: number, aspectRatio: number, style: Image_Style, url = ""): ComponentInit =>
  // preview_color is a signed int32; coerce packed ARGB into range with `| 0`.
  comp({ case: "image", value: { image: { previewColor: previewColor | 0, aspectRatio, url, alternateText: "Photo", previewData: new Uint8Array() }, style } });

// Inline helper: locate `sub` inside `base` and attach a rule over that range.
type TextInline = NonNullable<Extract<ComponentInit["contents"], { case: "text" }>["value"]>["inlines"] extends (infer I)[] ? I : never;
type Rule = NonNullable<TextInline["rule"]>["contents"];
const span = (base: string, sub: string, rule: Rule): TextInline => {
  const offset = base.indexOf(sub);
  return { offset, length: sub.length, rule: { contents: rule } };
};

// ---- fixtures -------------------------------------------------------------

const RICH = "This is bold, italic, struck, a link, @mention and hidden.";

const fixtures: { name: string; incoming: boolean; mode: Mode; component: ComponentInit }[] = [
  { name: "incoming-text", incoming: true, mode: "message", component: bubble(text("Hey! Are we still on for tonight?")) },
  { name: "outgoing-text", incoming: false, mode: "message", component: bubble(text("Yeah — 7pm works for me.")) },
  {
    name: "text-styles",
    incoming: true,
    mode: "message",
    component: bubble(
      vstack(
        Container_HorizontalAlignment.LEFT,
        text("Title style", Text_Style.TITLE),
        text("Subtitle style", Text_Style.SUBTITLE),
        text("Default body copy that wraps across a couple of lines to show the regular weight."),
        text("Description style", Text_Style.DESCRIPTION),
      ),
    ),
  },
  {
    name: "inline-rules",
    incoming: true,
    mode: "message",
    component: bubble(
      text(RICH, Text_Style.DEFAULT, [
        span(RICH, "bold", { case: "bold", value: {} }),
        span(RICH, "italic", { case: "italic", value: {} }),
        span(RICH, "struck", { case: "strikethrough", value: {} }),
        span(RICH, "a link", { case: "tappable", value: { style: 0, action: { action: { case: "link", value: { url: "https://example.com" } } } } }),
        span(RICH, "@mention", { case: "userLink", value: { identifier: "u_123" } }),
        span(RICH, "hidden", { case: "redaction", value: {} }),
      ]),
    ),
  },
  { name: "image", incoming: true, mode: "message", component: bubble(image(0xff3b5bdb, 1.5, Image_Style.DEFAULT)) },
  {
    name: "buttons",
    incoming: true,
    mode: "message",
    component: bubble(
      vstack(
        Container_HorizontalAlignment.CENTER_HORIZONTAL,
        text("Join the game?"),
        button("Accept", Button_Style.CTA),
        button("Maybe later", Button_Style.GROUPED),
        button("Snooze", Button_Style.PILL),
      ),
    ),
  },
  {
    name: "divider",
    incoming: true,
    mode: "message",
    component: bubble(vstack(Container_HorizontalAlignment.LEFT, text("Above the line"), divider(), text("Below the line"))),
  },
  {
    name: "hstack",
    incoming: true,
    mode: "message",
    component: bubble(
      hstack(Container_VerticalAlignment.CENTER_VERTICAL, text("12"), divider(), text("wins"), divider(), text("3 losses")),
    ),
  },
  {
    name: "grid",
    incoming: true,
    mode: "message",
    component: box(
      grid(
        [1, 1],
        Container_Grid_Style.STRIPED,
        text("Player", Text_Style.SUBTITLE),
        text("Score", Text_Style.SUBTITLE),
        text("Ada"),
        text("42"),
        text("Grace"),
        text("37"),
      ),
    ),
  },
  {
    name: "overlay",
    incoming: true,
    mode: "message",
    component: box(overlay(text("Live now", Text_Style.TITLE), text("Tap to watch the match", Text_Style.SUBTITLE))),
  },
  {
    name: "quote",
    incoming: false,
    mode: "message",
    component: bubble(
      vstack(
        Container_HorizontalAlignment.LEFT,
        quote(text("Are we still on for tonight?", Text_Style.SUBTITLE)),
        text("Yes! See you at 7."),
      ),
    ),
  },
  {
    name: "preview",
    incoming: true,
    mode: "preview",
    component: bubble(text("Hey! Are we still on for tonight? I can bring snacks if you want.")),
  },
];

// ---- emit -----------------------------------------------------------------

mkdirSync(OUT, { recursive: true });

for (const fixture of fixtures) {
  const message = create(ComponentSchema, fixture.component);
  writeFileSync(join(OUT, `${fixture.name}.pb`), toBinary(ComponentSchema, message));
}

writeFileSync(
  join(OUT, "manifest.json"),
  JSON.stringify(
    { fixtures: fixtures.map(({ name, incoming, mode }) => ({ name, incoming, mode })) },
    null,
    2,
  ),
);

console.log(`wrote ${fixtures.length} fixtures to ${OUT}`);
