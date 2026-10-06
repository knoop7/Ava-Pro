package com.example.ava.appwindow

import java.io.DataOutputStream

/**
 * Encoder for the subset of the scrcpy control protocol we use to drive an app
 * running on a virtual display. Wire format verified byte-for-byte against
 * scrcpy-server v3.3.4 (`control/ControlMessageReader.java`).
 */
object ScrcpyControl {
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_INJECT_SCROLL_EVENT = 3
    private const val TYPE_START_APP = 16

    /** MotionEvent / KeyEvent action constants (scrcpy forwards these straight through). */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val KEYCODE_BACK = 4
    const val POINTER_ID_MOUSE = -1L
    const val BUTTON_SECONDARY = 2

    /**
     * INJECT_KEYCODE, 14 bytes after type:
     * type(1) action(1) keycode(4) repeat(4) metastate(4).
     */
    fun writeKeycode(
        out: DataOutputStream,
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ) {
        out.writeByte(TYPE_INJECT_KEYCODE)
        out.writeByte(action)
        out.writeInt(keycode)
        out.writeInt(repeat)
        out.writeInt(metaState)
        out.flush()
    }

    /**
     * INJECT_TOUCH_EVENT, 32 bytes:
     * type(1) action(1) pointerId(8) x(4) y(4) w(2) h(2) pressure(2) actionButton(4) buttons(4).
     *
     * [x]/[y] are expressed in the same coordinate space as [screenW]/[screenH]
     * (the virtual-display resolution); the server rescales them to the real
     * display. Finger touches carry no mouse buttons — contact is signalled by
     * [pressure] (1 while down, 0 on release).
     */
    fun writeTouch(
        out: DataOutputStream,
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ) {
        out.writeByte(TYPE_INJECT_TOUCH_EVENT)
        out.writeByte(action)
        out.writeLong(pointerId)
        out.writeInt(x)
        out.writeInt(y)
        out.writeShort(screenW and 0xffff)
        out.writeShort(screenH and 0xffff)
        out.writeShort(encodeU16FixedPoint(pressure))
        out.writeInt(actionButton)
        out.writeInt(buttons)
        out.flush()
    }

    /**
     * INJECT_SCROLL_EVENT, 21 bytes:
     * type(1) x(4) y(4) w(2) h(2) hScroll(2) vScroll(2) buttons(4).
     * Scroll axes are i16 fixed-point in [-1, 1], matching scrcpy-server 3.3.4.
     */
    fun writeScroll(
        out: DataOutputStream,
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        hScroll: Float,
        vScroll: Float,
    ) {
        out.writeByte(TYPE_INJECT_SCROLL_EVENT)
        out.writeInt(x)
        out.writeInt(y)
        out.writeShort(screenW and 0xffff)
        out.writeShort(screenH and 0xffff)
        out.writeShort(encodeI16FixedPoint(hScroll))
        out.writeShort(encodeI16FixedPoint(vScroll))
        out.writeInt(0)
        out.flush()
    }

    /**
     * START_APP: type(1) len(1) utf8(name). A `+` prefix force-stops the app
     * first; a `?` prefix matches by display name. We pass the package name.
     */
    fun writeStartApp(out: DataOutputStream, name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        val len = bytes.size.coerceAtMost(255)
        out.writeByte(TYPE_START_APP)
        out.writeByte(len)
        out.write(bytes, 0, len)
        out.flush()
    }

    /** Maps [-1, 1] to signed i16, matching scrcpy `sc_float_to_i16fp`. */
    private fun encodeI16FixedPoint(value: Float): Int {
        val v = value.coerceIn(-1f, 1f)
        return (v * 32768f).toInt().coerceIn(-0x8000, 0x7fff)
    }

    /** 1.0 -> 0xffff (server special-cases it); otherwise round(p * 65536). */
    private fun encodeU16FixedPoint(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        if (v >= 1f) return 0xffff
        return (v * 65536f).toInt().coerceIn(0, 0xffff)
    }
}
