package com.kewu.app

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/** iOS：文件选择尚未接入（需 PHPicker / UIDocumentPicker 桥接），先返回空列表。 */
actual fun pickFiles(images: Boolean, onResult: (List<PickedFile>) -> Unit) = onResult(emptyList())

actual fun openUrl(url: String) {
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
}
