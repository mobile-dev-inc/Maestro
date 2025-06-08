
import Foundation
import os

extension Logger {
    func measure<T>(message: String, _ block: () throws -> T) rethrows -> T {
        let start = Date()
        info("\(message) - start")

        let result = try block()

        let duration = Date().timeIntervalSince(start)
        NSLog("\(message) - duration \(duration)")

        return result
    }
}
