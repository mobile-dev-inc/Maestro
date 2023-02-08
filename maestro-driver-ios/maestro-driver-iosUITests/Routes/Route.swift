enum Route: String {
    case subTree = "/subTree?appId=*"
    case runningApp = "/runningApp"
    case swipe = "/swipe?appId=*"
    case inputText = "/inputText?appId=*"
    case touch = "/touch?appId=*"
    case screenshot
    case isScreenStatic
}
