import SharedKit
import SwiftUI

@main
struct iOSApp: App {
    private let dependencies = IosDependenciesKt.createIosDependencies()

    var body: some Scene {
        WindowGroup { ContentView(dependencies: dependencies) }
    }
}
