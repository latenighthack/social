import React from "react";
import { createRoot } from "react-dom/client";
import { fromBinary } from "@bufbuild/protobuf";
import {
  ComponentSchema,
  MessageComponent,
  MessagePreview,
  type Component,
} from "@social/messages-view";

interface Entry {
  name: string;
  incoming: boolean;
  mode: "message" | "preview";
}

interface Loaded extends Entry {
  component: Component;
}

const CAPTURE_WIDTH = 360;

async function load(): Promise<Loaded[]> {
  const manifest: { fixtures: Entry[] } = await fetch("/bundles/manifest.json").then((r) => r.json());
  const params = new URLSearchParams(location.search);
  const only = params.get("only");
  const entries = manifest.fixtures.filter((e) => !only || e.name === only);
  return Promise.all(
    entries.map(async (e) => {
      const bytes = new Uint8Array(await fetch(`/bundles/${e.name}.pb`).then((r) => r.arrayBuffer()));
      return { ...e, component: fromBinary(ComponentSchema, bytes) };
    }),
  );
}

function Fixture({ item }: { item: Loaded }): React.JSX.Element {
  return (
    <div
      id={`fixture-${item.name}`}
      style={{ width: CAPTURE_WIDTH, boxSizing: "border-box", padding: 16, background: "#0f0e17" }}
    >
      {item.mode === "preview" ? (
        <MessagePreview component={item.component} />
      ) : (
        <div style={{ display: "flex", justifyContent: item.incoming ? "flex-start" : "flex-end" }}>
          <MessageComponent component={item.component} incoming={item.incoming} />
        </div>
      )}
    </div>
  );
}

function App({ items }: { items: Loaded[] }): React.JSX.Element {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, alignItems: "flex-start" }}>
      {items.map((item) => (
        <Fixture key={item.name} item={item} />
      ))}
    </div>
  );
}

load().then((items) => {
  createRoot(document.getElementById("root")!).render(<App items={items} />);
});
