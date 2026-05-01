import Foundation
import HealthKit

enum HealthAccessManager {
    static let store = HKHealthStore()

    static func requestAuthorization(completion: @escaping (Bool, Error?) -> Void) {
        guard HKHealthStore.isHealthDataAvailable() else {
            completion(false, NSError(
                domain: "HealthAccessManager",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "HealthKit not available on this device"]
            ))
            return
        }

        let readTypes: Set<HKObjectType> = [
            HKObjectType.quantityType(forIdentifier: .heartRate)!,
            HKObjectType.quantityType(forIdentifier: .stepCount)!,
            HKObjectType.quantityType(forIdentifier: .activeEnergyBurned)!,
            HKObjectType.workoutType(),
            HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!,
        ]

        store.requestAuthorization(toShare: nil, read: readTypes) { success, error in
            DispatchQueue.main.async { completion(success, error) }
        }
    }
}
