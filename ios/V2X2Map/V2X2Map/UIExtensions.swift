import SwiftUI
import UIKit

extension UIColor {
    /// Builds a UIColor from a packed ARGB value (as stored in `MsgType.colorARGB`).
    convenience init(argb: UInt32) {
        self.init(
            red:   CGFloat((argb >> 16) & 0xFF) / 255.0,
            green: CGFloat((argb >> 8) & 0xFF) / 255.0,
            blue:  CGFloat(argb & 0xFF) / 255.0,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255.0
        )
    }
}

extension Color {
    /// Builds a Color from a packed ARGB value (as stored in `MsgType.colorARGB`).
    init(argb: UInt32) {
        self.init(
            .sRGB,
            red:   Double((argb >> 16) & 0xFF) / 255.0,
            green: Double((argb >> 8) & 0xFF) / 255.0,
            blue:  Double(argb & 0xFF) / 255.0,
            opacity: Double((argb >> 24) & 0xFF) / 255.0
        )
    }
}

extension MsgType {
    var color: Color { Color(argb: colorARGB) }
}

extension BleState {
    var title: String {
        switch self {
        case .idle:       return "Getrennt"
        case .scanning:   return "Suche…"
        case .connecting: return "Verbinde…"
        case .connected:  return "Verbunden"
        case .error:      return "Fehler"
        }
    }

    var color: Color {
        switch self {
        case .connected:            return .green
        case .scanning, .connecting: return .orange
        case .error:                return .red
        case .idle:                 return .gray
        }
    }
}
