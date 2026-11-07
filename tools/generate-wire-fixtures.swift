import Foundation
@main struct Fixture {
 static func main() throws {
  let encoder = JSONEncoder()
  let fixtures: [String: Data] = [
   "pointer": try encoder.encode(InputEvent.pointer(.leftDown, x: 0.25, y: 0.75)),
   "scroll": try encoder.encode(InputEvent.scroll(x: 0.25, y: 0.75, dx: -0.1, dy: 0.2)),
   "phase": try encoder.encode(InputEvent.scrollGesture(.began, x: 0.25, y: 0.75)),
   "text": try encoder.encode(InputEvent.text("你好🌍")),
   "key": try encoder.encode(InputEvent.key(hidUsage: 41, down: true, modifiers: 1048576))
  ]
  var result: [String:Any] = [:]
  for (k,v) in fixtures { result[k] = try JSONSerialization.jsonObject(with:v) }
  let message = StreamMessage(kind: .input, timestamp: 1234.5, isKeyframe: false, payload: Data([1,2,3]))
  result["headerHex"] = message.serialized().map { String(format:"%02x",$0) }.joined()
  let output = try JSONSerialization.data(withJSONObject:result, options:[.sortedKeys,.prettyPrinted])
  FileHandle.standardOutput.write(output)
 }
}
