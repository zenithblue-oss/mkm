package com.mkm.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsBinderShellSafetyTest {
    @Test fun acceptsRealLayerNames() {
        assertTrue(SAFE_LAYER_REGEX.matches("SurfaceView[com.game/com.game.MainActivity]#828"))
        assertTrue(SAFE_LAYER_REGEX.matches("SurfaceView[com.game/com.game.Unity Player](BLAST)#912"))
        assertTrue(SAFE_LAYER_REGEX.matches("com.game/com.game.MainActivity#824"))
    }

    @Test fun rejectsInjectedTitles() {
        assertFalse(SAFE_LAYER_REGEX.matches("SurfaceView[x'; reboot; echo ']#1"))
        assertFalse(SAFE_LAYER_REGEX.matches("SurfaceView[x\"]#1"))
        assertFalse(SAFE_LAYER_REGEX.matches("com.game/Act#1; reboot"))
        assertFalse(SAFE_LAYER_REGEX.matches("SurfaceView[a]#1\nreboot"))
    }

    @Test fun shellQuoteKeepsOneLiteralWord() {
        assertEquals("'plain'", shellQuote("plain"))
        assertEquals("'a'\\''b'", shellQuote("a'b"))
    }
}
