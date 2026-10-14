import SwiftUI
import KewuApp

@main
struct KewuIosApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        // Compose Multiplatform iOS 支持接入点（正式 UI 后续接入 ComposeUIViewController）
        VStack {
            KewuPlaceholder()
        }
    }
}

struct KewuPlaceholder: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // 后续替换为 MainViewControllerKt.MainViewController()
        UIViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
