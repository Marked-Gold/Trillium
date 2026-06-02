import korlibs.korge.view.Views
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop/JVM clipboard via AWT.
 *
 * The write is marshalled onto the AWT Event Dispatch Thread. KorGE invokes click handlers on its
 * own render/coroutine thread, but the system clipboard bottoms out in native AppKit (NSPasteboard)
 * on macOS, which must be touched only from the AppKit main thread (the EDT). Calling it off-thread
 * is a hard native crash that aborts the JVM rather than throwing. `invokeLater` is exactly what
 * KorGE's own GameWindow.clipboardWrite does for this reason.
 */
actual fun Views.copyTextToClipboard(text: String) {
    EventQueue.invokeLater {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
