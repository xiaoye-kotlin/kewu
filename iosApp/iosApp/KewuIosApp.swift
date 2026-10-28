import SwiftUI
import UIKit
import KewuApp

@main
struct KewuIosApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose 自己处理状态栏 / 键盘避让（statusBarsPadding / imePadding），这里铺满全屏
                .ignoresSafeArea(.all)
        }
    }
}

/// 承载 Compose Multiplatform 界面（App() 由 MainViewController.kt 提供）
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
