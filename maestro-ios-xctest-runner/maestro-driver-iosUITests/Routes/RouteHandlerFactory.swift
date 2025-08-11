import Foundation
import FlyingFox

class RouteHandlerFactory {
    @MainActor class func createRouteHandler(route: Route) -> HTTPHandler {
        switch route {
        case .runningApp:
            return RunningAppRouteHandler()
        case .swipe:
            return SwipeRouteHandler()
        case .swipeV2:
            return SwipeRouteHandlerV2()
        case .inputText:
            return InputTextRouteHandler()
        case .touch:
            return TouchRouteHandler()
        case .screenshot:
            return ScreenshotHandler()
        case .isScreenStatic:
            return IsScreenStaticHandler()
        case .pressKey:
            return PressKeyHandler()
        case .pressButton:
            return PressButtonHandler()
        case .eraseText:
            return EraseTextHandler()
        case .deviceInfo:
            return DeviceInfoHandler()
        case .setOrientation:
            return SetOrientationHandler()
        case .setPermissions:
            return SetPermissionsHandler()
        case .viewHierarchy:
            return ViewHierarchyHandler()
        case .status:
            return StatusHandler()
        case .keyboard:
            return KeyboardRouteHandler()
        case .terminateApp:
            return TerminateAppHandler()
        case .launchApp:
             return LaunchAppHandler()
        }
    }
}
