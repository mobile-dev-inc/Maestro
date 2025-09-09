
import Foundation
import XCTest

struct PressButtonRequest: Codable {
    enum Button: String, Codable {
        #if !os(tvOS)
        case home = "Home"
        case lock = "Lock"
        #else
        case volumeUp = "Volume Up"
        case volumeDown = "Volume Down"
        case remoteDpadUp = "Remote Dpad Up"
        case remoteDpadDown = "Remote Dpad Down"
        case remoteDpadLeft = "Remote Dpad Left"
        case remoteDpadRight = "Remote Dpad Right"
        case remoteDpadCenter = "Remote Dpad Center"
        case remoteMediaPlayPause = "Remote Media Play Pause"
        /**
        case remoteMediaNext = "Remote Media Next"
        case remoteMediaPrevious = "Remote Media Previous"
        case remoteMediaRewind = "Remote Media Rewind"
        case remoteMediaFastForward = "Remote Media Fast Forward"
        case RemoteSystemNavigationUp = "Remote System Navigation Up"
        case RemoteSystemNavigationDown = "Remote System Navigation Down"
         **/
        case RemoteMenu = "Remote Menu"
        /**
        case tvInput = "TV Input"
        case tvInputHDMIOne = "TV Input HDMI 1"
        case tvInputHDMITwo = "TV Input HDMI 2"
        case tvInputHDMIThree = "TV Input HDMI 3"
         **/
        #endif
    }

    let button: Button
}
