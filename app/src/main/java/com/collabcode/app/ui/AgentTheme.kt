package com.collabcode.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The colours of the design (emerald on slate, light and dark). */
data class AgentPalette(
    val bg: Color, val sf: Color, val s2: Color, val bd: Color,
    val tx: Color, val mu: Color, val ac: Color, val ac2: Color,
    val ok: Color, val wn: Color, val er: Color,
)

val LightAgent = AgentPalette(
    bg = Color(0xFFF8FAFC), sf = Color(0xFFFFFFFF), s2 = Color(0xFFF1F5F9), bd = Color(0xFFE2E8F0),
    tx = Color(0xFF0F172A), mu = Color(0xFF64748B), ac = Color(0xFF059669), ac2 = Color(0xFF14B8A6),
    ok = Color(0xFF15803D), wn = Color(0xFFB45309), er = Color(0xFFDC2626),
)

val DarkAgent = AgentPalette(
    bg = Color(0xFF0F172A), sf = Color(0xFF1E293B), s2 = Color(0xFF172033), bd = Color(0xFF334155),
    tx = Color(0xFFF1F5F9), mu = Color(0xFF94A3B8), ac = Color(0xFF10B981), ac2 = Color(0xFF14B8A6),
    ok = Color(0xFF34D399), wn = Color(0xFFFBBF24), er = Color(0xFFF87171),
)

val LocalAgent = staticCompositionLocalOf { LightAgent }
