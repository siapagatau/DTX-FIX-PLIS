package com.kendang.realpads

import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect

/**
 * Pemicu pad berbasis event sentuh MENTAH (MotionEvent) yang dibaca di MainActivity.dispatchTouchEvent
 * SEBELUM Compose memprosesnya (v1.8.3).
 *
 * Kenapa: jalur lama (pointerInput Compose + hit-test LayoutCoordinates) bergantung pada banyak langkah
 * perantara (hit-test node Compose, pass event, flag consumed, state pointer). Saat ketukan sangat cepat,
 * ada ketukan yang tidak sampai ke onPadDown (tidak bunyi, tidak menyala). Di sini satu ACTION_DOWN /
 * ACTION_POINTER_DOWN = satu pukulan, titik. Tidak ada state yang bisa basi: sentuhan dilacak per
 * pointerId MotionEvent dan dibersihkan di setiap UP/CANCEL.
 *
 * Event TIDAK dikonsumsi/diubah - dispatch normal tetap jalan (chip tempo di pad loop, panel, dll).
 * Semua dialog = window terpisah, jadi tidak pernah lewat sini.
 *
 * Koordinat: MotionEvent di Activity.dispatchTouchEvent = koordinat window, sama dengan
 * boundsInWindow() Compose.
 */
object PadTouchRouter {
    private val padRects = HashMap<Int, Rect>()
    private val chipRects = HashMap<Int, Rect>()
    // pointerId MotionEvent -> pad yang sedang ditempati jari itu (-1 = jari tidak memukul pad, mis. di chip tempo).
    private val pointerPad = HashMap<Int, Int>()
    private var onHit: ((Int) -> Unit)? = null

    /** false = abaikan sentuhan (mis. tutorial sedang tampil di atas grid). */
    @Volatile var enabled = true
    /** Toleransi snap ke pad terdekat kalau sentuhan jatuh persis di celah antar pad. */
    @Volatile var snapPx = 36f

    fun attach(callback: (Int) -> Unit) { onHit = callback }
    fun detach() { onHit = null; padRects.clear(); chipRects.clear(); pointerPad.clear() }

    fun setPad(id: Int, r: Rect) { padRects[id] = r }
    fun removePad(id: Int) { padRects.remove(id) }
    fun setChip(id: Int, r: Rect) { chipRects[id] = r }
    fun removeChip(id: Int) { chipRects.remove(id) }

    private fun hitPad(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((id, r) in padRects) {
            val dx = when { x < r.left -> r.left - x; x > r.right -> x - r.right; else -> 0f }
            val dy = when { y < r.top -> r.top - y; y > r.bottom -> y - r.bottom; else -> 0f }
            val d = dx * dx + dy * dy
            if (d == 0f) return id
            if (d < bestD) { bestD = d; best = id }
        }
        return if (best >= 0 && bestD <= snapPx * snapPx) best else -1
    }

    private fun inChip(x: Float, y: Float): Boolean {
        for ((_, r) in chipRects) if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        return false
    }

    private fun fire(pad: Int) {
        val cb = onHit ?: return
        try { cb(pad) } catch (e: Throwable) { android.util.Log.e("PadTouchRouter", "onPadDown gagal", e) }
    }

    fun onMotionEvent(ev: MotionEvent) {
        if (onHit == null) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val pid = ev.getPointerId(i)
                if (!enabled) { pointerPad[pid] = -1; return }
                val x = ev.getX(i)
                val y = ev.getY(i)
                // Sentuhan di chip tempo (−/+) pad yang lagi loop = urusan tombolnya, bukan pukulan pad.
                if (inChip(x, y)) { pointerPad[pid] = -1; return }
                val pad = hitPad(x, y)
                if (pad < 0) { pointerPad[pid] = -1; return }
                pointerPad[pid] = pad
                fire(pad)
            }
            MotionEvent.ACTION_MOVE -> {
                // Geser pindah pad (setelan "Geser Pindah Pad", default nonaktif).
                if (!enabled || !PadSlideRollState.enabled) return
                for (i in 0 until ev.pointerCount) {
                    val pid = ev.getPointerId(i)
                    val prev = pointerPad[pid] ?: continue
                    if (prev < 0) continue
                    val pad = hitPad(ev.getX(i), ev.getY(i))
                    if (pad >= 0 && pad != prev) { pointerPad[pid] = pad; fire(pad) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> pointerPad.remove(ev.getPointerId(ev.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerPad.clear()
        }
    }
}
