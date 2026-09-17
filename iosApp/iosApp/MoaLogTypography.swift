import SwiftUI

enum MoaLogColor {
    static let canvas = Color(red: 0.984, green: 0.976, blue: 0.961)
    static let homeCanvas = Color(red: 0.969, green: 0.980, blue: 0.961)
    static let linen = Color(red: 0.961, green: 0.953, blue: 0.929)
    static let surface = Color.white
    static let surfaceLow = Color(red: 0.945, green: 0.957, blue: 0.941)
    static let homeBorder = Color(red: 0.878, green: 0.890, blue: 0.875)
    static let ink = Color(red: 0.098, green: 0.110, blue: 0.102)
    static let mutedInk = Color(red: 0.333, green: 0.380, blue: 0.373)
    static let outline = Color(red: 0.439, green: 0.475, blue: 0.467)
    static let cardBorder = Color(red: 0.918, green: 0.902, blue: 0.875)
    static let teal = Color(red: 0.118, green: 0.306, blue: 0.290)
    static let pressedTeal = Color(red: 0.075, green: 0.243, blue: 0.227)
    static let sage = Color(red: 0.902, green: 0.949, blue: 0.941)
    static let secondaryContainer = Color(red: 0.851, green: 0.898, blue: 0.890)
    static let tertiary = Color(red: 0.000, green: 0.220, blue: 0.173)
    static let divider = Color(red: 0.925, green: 0.937, blue: 0.918)
    static let error = Color(red: 0.725, green: 0.102, blue: 0.102)
    static let errorSurface = Color(red: 1.000, green: 0.855, blue: 0.839)
}

enum MoaLogFont {
    static func regular(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Pretendard-Regular", size: size, relativeTo: style)
    }

    static func medium(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Pretendard-Medium", size: size, relativeTo: style)
    }

    static func semibold(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Pretendard-SemiBold", size: size, relativeTo: style)
    }

    static func bold(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Pretendard-Bold", size: size, relativeTo: style)
    }
}
