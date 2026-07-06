import UIKit
import MessagesView

struct Fixture {
    let name: String
    let incoming: Bool
    let mode: String
    let bytes: Data
}

enum Fixtures {
    static func load(bundle: Bundle) -> [Fixture] {
        guard let url = bundle.url(forResource: "manifest", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let entries = json["fixtures"] as? [[String: Any]] else {
            return []
        }
        return entries.compactMap { entry in
            guard let name = entry["name"] as? String,
                  let incoming = entry["incoming"] as? Bool,
                  let mode = entry["mode"] as? String,
                  let pb = bundle.url(forResource: name, withExtension: "pb"),
                  let bytes = try? Data(contentsOf: pb) else {
                return nil
            }
            return Fixture(name: name, incoming: incoming, mode: mode, bytes: bytes)
        }
    }

    static func render(_ fixture: Fixture) -> UIView {
        let component = (try? MessageComponent(serializedBytes: fixture.bytes)) ?? MessageComponent()
        if fixture.mode == "preview" {
            return MessagePreviewView(component: component)
        }
        let theme: MessageTheme = fixture.incoming ? .incoming : .outgoing
        return MessageComponentView(component: component, theme: theme)
    }
}
