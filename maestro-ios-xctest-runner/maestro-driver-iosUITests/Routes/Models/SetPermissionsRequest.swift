import Foundation
import MaestroDriverLib

struct SetPermissionsRequest: Codable {
    let permissions: [String : PermissionValue]
}