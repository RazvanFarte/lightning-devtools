package acr.browser.lightning.js

import com.anthonycr.mezzanine.FileStream

/**
 * The DevTools page agent, injected at document start when recording is enabled.
 */
@FileStream("src/main/js/DevToolsAgent.js")
interface DevToolsAgent {

    fun provideJs(): String

}
