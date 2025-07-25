import Foundation

struct TimeoutHelper {
    private init() {}
    
    static func repeatUntil(timeout: TimeInterval, delta: TimeInterval, block: () -> Bool) async throws {
        guard delta >= 0 else {
            throw NSError(domain: "Invalid value", code: 1, userInfo: [NSLocalizedDescriptionKey: "Delta cannot be negative"])
        }
        
        let timeout = Date().addingTimeInterval(timeout)
        
        while Date() < timeout {
            do {
                try await Task.sleep(nanoseconds: UInt64(1_000_000_000 * delta))
            } catch {
                throw NSError(domain: "Failed to sleep task", code: 2, userInfo: [NSLocalizedDescriptionKey: "Task could not be put to sleep"])
            }
            
            if (block()) {
                break
            }
        }
    }
}
