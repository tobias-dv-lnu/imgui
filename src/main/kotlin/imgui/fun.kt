package imgui

import glm_.BYTES
import glm_.f
import glm_.glm
import glm_.i
import glm_.vec2.Vec2
import glm_.vec2.Vec2i
import imgui.ImGui.buttonBehavior
import imgui.ImGui.currentWindowRead
import imgui.ImGui.setHoveredId
import imgui.internal.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import imgui.Context as g


fun logRenderedText(refPos: Vec2, text: String, textEnd: Int = 0): Nothing = TODO()

//static void             PushMultiItemsWidths(int components, float w_full = 0.0f);

fun getDraggedColumnOffset(columnIndex: Int): Float {
    /*  Active (dragged) column always follow mouse. The reason we need this is that dragging a column to the right edge
        of an auto-resizing window creates a feedback loop because we store normalized positions. So while dragging we
        enforce absolute positioning.   */

    val window = currentWindowRead!!
    /*  We cannot drag column 0. If you get this assert you may have a conflict between the ID of your columns and
        another widgets.    */
    assert(columnIndex > 0)
    assert(g.activeId == window.dc.columnsSetId + columnIndex)

    var x = IO.mousePos.x - g.activeIdClickOffset.x - window.pos.x
    x = glm.clamp(x, ImGui.getColumnOffset(columnIndex - 1) + Style.columnsMinSpacing,
            ImGui.getColumnOffset(columnIndex + 1) - Style.columnsMinSpacing)

    return x.i.f
}

val defaultFont get() = IO.fontDefault ?: IO.fonts.fonts[0]

//-----------------------------------------------------------------------------
// Internal API exposed in imgui_internal.h
//-----------------------------------------------------------------------------

/** Find window given position, search front-to-back
FIXME: Note that we have a lag here because WindowRectClipped is updated in Begin() so windows moved by user via
SetWindowPos() and not SetNextWindowPos() will have that rectangle lagging by a frame at the time
FindHoveredWindow() is called, aka before the next Begin(). Moving window thankfully isn't affected.    */
fun findHoveredWindow(pos: Vec2, excludingChilds: Boolean): Window? {
    for (i in g.windows.size - 1 downTo 0) {
        val window = g.windows[i]
        if (!window.active)
            continue
        if (window.flags has WindowFlags.NoInputs)
            continue
        if (excludingChilds && window.flags has WindowFlags.ChildWindow)
            continue

        // Using the clipped AABB so a child window will typically be clipped by its parent.
        val bb = Rect(window.windowRectClipped.min - Style.touchExtraPadding, window.windowRectClipped.max + Style.touchExtraPadding)
        if (bb contains pos)
            return window
    }
    return null
}

fun createNewWindow(name: String, size: Vec2, flags: Int) = Window(name).apply {
    // Create window the first time

    this.flags = flags

    if (flags has WindowFlags.NoSavedSettings) {
        // User can disable loading and saving of settings. Tooltip and child windows also don't store settings.
        sizeFull = size
        this.size = size
    } else {
        /*  Retrieve settings from .ini file
            Use SetWindowPos() or SetNextWindowPos() with the appropriate condition flag to change the initial position
            of a window.    */
        posF put 60
        pos put posF

        var settings = findWindowSettings(name)
        if (settings == null)
            settings = addWindowSettings(name)
        else {
            setWindowPosAllowFlags = setWindowPosAllowFlags and SetCond.FirstUseEver.i.inv()
            setWindowSizeAllowFlags = setWindowSizeAllowFlags and SetCond.FirstUseEver.i.inv()
            setWindowCollapsedAllowFlags = setWindowCollapsedAllowFlags and SetCond.FirstUseEver.i.inv()
        }

        if (settings.pos.x != Int.MAX_VALUE) {
            posF put settings.pos
            pos put posF
            collapsed = settings.collapsed
        }

        if (settings.size.lengthSqr() > 0.00001f && flags hasnt WindowFlags.NoResize)
            size put settings.size
        sizeFull = size
        this.size = size
    }

    if (flags has WindowFlags.AlwaysAutoResize) {
        autoFitFrames put 2
        autoFitOnlyGrows = false
    } else {
        if (size.x <= 0.0f)
            autoFitFrames.x = 2
        if (size.y <= 0.0f)
            autoFitFrames.y = 2
        autoFitOnlyGrows = autoFitFrames.x > 0 || autoFitFrames.y > 0
    }

    if (flags has WindowFlags.NoBringToFrontOnFocus)
        g.windows.add(0, this) // Quite slow but rare and only once
    else
        g.windows.add(this)
}


//static void             ClearSetNextWindowData();

/** Save and compare stack sizes on Begin()/End() to detect usage errors    */
fun checkStacksSize(window: Window, write: Boolean) {
    /*  NOT checking: DC.ItemWidth, DC.AllowKeyboardFocus, DC.ButtonRepeat, DC.TextWrapPos (per window) to allow user to
        conveniently push once and not pop (they are cleared on Begin)  */
    val backup = window.dc.stackSizesBackup
    var ptr = 0

    run {
        // Too few or too many PopID()/TreePop()
        val current = window.idStack.size
        if (write) backup[ptr] = current
        else assert(backup[ptr] == current, { "PushID/PopID or TreeNode/TreePop Mismatch!" })
        ptr++
    }
    run {
        // Too few or too many EndGroup()
        val current = window.dc.groupStack.size
        if (write) backup[ptr] = current
        else assert(backup[ptr] == current, { "BeginGroup/EndGroup Mismatch!" })
        ptr++
    }
    run {
        // Too few or too many EndMenu()/EndPopup()
        val current = g.currentPopupStack.size
        if (write) backup[ptr] = current
        else assert(backup[ptr] == current, { "BeginMenu/EndMenu or BeginPopup/EndPopup Mismatch" })
        ptr++
    }
    run {
        // Too few or too many PopStyleColor()
        val current = g.colorModifiers.size
        if (write) backup[ptr] = current
        else assert(backup[ptr] == current, { "PushStyleColor/PopStyleColor Mismatch!" })
        ptr++
    }
    run {
        // Too few or too many PopStyleVar()
        val current = g.styleModifiers.size
        if (write) backup[ptr] = current
        else assert(backup[ptr] == current, { "PushStyleVar/PopStyleVar Mismatch!" })
        ptr++
    }
    run {
        // Too few or too many PopFont()
        val current = g.fontStack.size
        if (write) backup[ptr] = current
        else assert(backup[ptr] == current, { "PushFont/PopFont Mismatch!" })
        ptr++
    }
    assert(ptr == window.dc.stackSizesBackup.size)
}

/** Vertical scrollbar
 *  The entire piece of code below is rather confusing because:
 *  - We handle absolute seeking (when first clicking outside the grab) and relative manipulation (afterward or when
 *          clicking inside the grab)
 *  - We store values as normalized ratio and in a form that allows the window content to change while we are holding on
 *          a scrollbar
 *  - We handle both horizontal and vertical scrollbars, which makes the terminology not ideal. */
fun scrollbar(window: Window, horizontal: Boolean) {

    val id = window.getId(if (horizontal) "#SCROLLX" else "#SCROLLY")

    // Render background
    val otherScrollbar = if (horizontal) window.scrollbar.y else window.scrollbar.x
    val otherScrollbarSizeW = if (otherScrollbar) Style.scrollbarSize else 0f
    val windowRect = window.rect()
    val borderSize = window.borderSize
    val bb =
            if (horizontal)
                Rect(window.pos.x + borderSize, windowRect.max.y - Style.scrollbarSize,
                        windowRect.max.x - otherScrollbarSizeW - borderSize, windowRect.max.y - borderSize)
            else
                Rect(windowRect.max.x - Style.scrollbarSize, window.pos.y + borderSize,
                        windowRect.max.x - borderSize, windowRect.max.y - otherScrollbarSizeW - borderSize)
    if (!horizontal)
        bb.min.y += window.titleBarHeight() + if (window.flags has WindowFlags.MenuBar) window.menuBarHeight() else 0f
    if (bb.width <= 0f || bb.height <= 0f) return

    val windowRounding = if (window.flags has WindowFlags.ChildWindow) Style.childWindowRounding else Style.windowRounding
    val windowRoundingCorners =
            if (horizontal)
                Corner.BottomLeft or if (otherScrollbar) Corner.All else Corner.BottomRight
            else
                (
                        if (window.flags has WindowFlags.NoTitleBar && window.flags hasnt WindowFlags.MenuBar)
                            Corner.TopRight
                        else Corner.All) or if (otherScrollbar) Corner.All else Corner.BottomRight
    window.drawList.addRectFilled(bb.min, bb.max, ImGui.getColorU32(Col.ScrollbarBg), windowRounding, windowRoundingCorners)
    bb.reduce(Vec2(
            glm.clamp(((bb.max.x - bb.min.x - 2.0f) * 0.5f).i.f, 0.0f, 3.0f),
            glm.clamp(((bb.max.y - bb.min.y - 2.0f) * 0.5f).i.f, 0.0f, 3.0f)))

    // V denote the main axis of the scrollbar
    val scrollbarSizeV = if (horizontal) bb.width else bb.height
    var scrollV = if (horizontal) window.scroll.x else window.scroll.y
    val winSizeAvailV = (if (horizontal) window.size.x else window.size.y) - otherScrollbarSizeW
    val winSizeContentsV = if (horizontal) window.sizeContents.x else window.sizeContents.y

    /*  The grabable box size generally represent the amount visible (vs the total scrollable amount)
        But we maintain a minimum size in pixel to allow for the user to still aim inside.  */
    val grabHPixels = glm.min(
            glm.max(scrollbarSizeV * saturate(winSizeAvailV / glm.max(winSizeContentsV, winSizeAvailV)), Style.grabMinSize),
            scrollbarSizeV)
    val grabHNorm = grabHPixels / scrollbarSizeV

    // Handle input right away. None of the code of Begin() is relying on scrolling position before calling Scrollbar().
    val previouslyHeld = g.activeId == id
    val (_, hovered, held) = buttonBehavior(bb, id)

    val scrollMax = glm.max(1f, winSizeContentsV - winSizeAvailV)
    var scrollRatio = saturate(scrollV / scrollMax)
    var grabVNorm = scrollRatio * (scrollbarSizeV - grabHPixels) / scrollbarSizeV
    if (held && grabHNorm < 1f) {
        val scrollbarPosV = if (horizontal) bb.min.x else bb.min.y
        val mousePosV = if (horizontal) IO.mousePos.x else IO.mousePos.y
        var clickDeltaToGrabCenterV = if (horizontal) g.scrollbarClickDeltaToGrabCenter.x else g.scrollbarClickDeltaToGrabCenter.y

        // Click position in scrollbar normalized space (0.0f->1.0f)
        val clickedVNorm = saturate((mousePosV - scrollbarPosV) / scrollbarSizeV)
        setHoveredId(id)

        var seekAbsolute = false
        if (!previouslyHeld)
        // On initial click calculate the distance between mouse and the center of the grab
            if (clickedVNorm >= grabVNorm && clickedVNorm <= grabVNorm + grabHNorm)
                clickDeltaToGrabCenterV = clickedVNorm - grabVNorm - grabHNorm * 0.5f
            else {
                seekAbsolute = true
                clickDeltaToGrabCenterV = 0f
            }

        /*  Apply scroll
            It is ok to modify Scroll here because we are being called in Begin() after the calculation of SizeContents
            and before setting up our starting position */
        val scrollVNorm = saturate((clickedVNorm - clickDeltaToGrabCenterV - grabHNorm * 0.5f) / (1f - grabHNorm))
        scrollV = (0.5f + scrollVNorm * scrollMax).i.f  //(winSizeContentsV - win_size_v));
        if (horizontal)
            window.scroll.x = scrollV
        else
            window.scroll.y = scrollV

        // Update values for rendering
        scrollRatio = saturate(scrollV / scrollMax)
        grabVNorm = scrollRatio * (scrollbarSizeV - grabHPixels) / scrollbarSizeV

        // Update distance to grab now that we have seeked and saturated
        if (seekAbsolute)
            clickDeltaToGrabCenterV = clickedVNorm - grabVNorm - grabHNorm * 0.5f

        if (horizontal)
            g.scrollbarClickDeltaToGrabCenter.x = clickDeltaToGrabCenterV
        else
            g.scrollbarClickDeltaToGrabCenter.y = clickDeltaToGrabCenterV
    }

    // Render
    val grabCol = ImGui.getColorU32(if (held) Col.ScrollbarGrabActive else if (hovered) Col.ScrollbarGrabHovered else Col.ScrollbarGrab)
    if (horizontal)
        window.drawList.addRectFilled(
                Vec2(lerp(bb.min.x, bb.max.x, grabVNorm), bb.min.y),
                Vec2(lerp(bb.min.x, bb.max.x, grabVNorm) + grabHPixels, bb.max.y),
                grabCol, Style.scrollbarRounding)
    else
        window.drawList.addRectFilled(
                Vec2(bb.min.x, lerp(bb.min.y, bb.max.y, grabVNorm)),
                Vec2(bb.max.x, lerp(bb.min.y, bb.max.y, grabVNorm) + grabHPixels),
                grabCol, Style.scrollbarRounding)
}


fun findWindowSettings(name: String): IniData? {
    val id = hash(name, 0)
    return g.settings.firstOrNull { it.id == id }
}

fun addWindowSettings(name: String): IniData {
    val ini = IniData()
    g.settings.add(ini)
    ini.name = name
    ini.id = hash(name, 0)
    ini.collapsed = false
    ini.pos = Vec2i(Int.MAX_VALUE)
    ini.size = Vec2()
    return ini
}

fun loadIniSettingsFromDisk(iniFilename: String?) {

    if (iniFilename == null) return

    var settings: IniData? = null

    Files.lines(Paths.get(iniFilename)).filter { it.isNotEmpty() }.forEach {
        if (it[0] == '[' && it.last() == ']') {
            val name = it.substring(1, it.lastIndex)
            settings = findWindowSettings(name) ?: addWindowSettings(name)
        } else if (settings != null) when {
            it.startsWith("Pos") -> settings!!.pos.put(it.substring(4).split(","))
            it.startsWith("Size") -> settings!!.size put glm.max(Vec2i(it.substring(5).split(",")), Style.windowMinSize)
            it.startsWith("Collapsed") -> settings!!.collapsed = it.substring(10).toBoolean()
        }
    }
}

fun saveIniSettingsToDisk(iniFilename: String?) {

    g.settingsDirtyTimer = 0f
    if (iniFilename == null) return

    // Gather data from windows that were active during this session
    for (window in g.windows) {

        if (window.flags has WindowFlags.NoSavedSettings) continue
        val settings = findWindowSettings(window.name)!!
        settings.pos = window.pos
        settings.size = window.sizeFull
        settings.collapsed = window.collapsed
    }

    /*  Write .ini file
        If a window wasn't opened in this session we preserve its settings     */
    File(Paths.get(iniFilename).toUri()).printWriter().use {
        for (setting in g.settings) {
            if (setting.pos.x == Int.MAX_VALUE) continue
            // Skip to the "###" marker if any. We don't skip past to match the behavior of GetID()
            val name = setting.name.substringBefore("###")
            it.println("[$name]")
            it.println("Pos=${setting.pos.x},${setting.pos.y}")
            it.println("Size=${setting.size.x},${setting.size.y}")
            it.println("Collapsed=${setting.collapsed.i}")
            it.println()
        }
    }
}

fun markIniSettingsDirty() {
    if (g.settingsDirtyTimer <= 0f)
        g.settingsDirtyTimer = IO.iniSavingRate
}

//
//static void             PushColumnClipRect(int column_index = -1);

fun getVisibleRect(): Rect {
    if (IO.displayVisibleMin.x != IO.displayVisibleMax.x && IO.displayVisibleMin.y != IO.displayVisibleMax.y)
        return Rect(IO.displayVisibleMin, IO.displayVisibleMax)
    return Rect(0f, 0f, IO.displaySize.x.f, IO.displaySize.y.f)
}

//
//static bool             BeginPopupEx(const char* str_id, ImGuiWindowFlags extra_flags);

fun closeInactivePopups() {

    if (g.openPopupStack.empty())
        return

    /*  When popups are stacked, clicking on a lower level popups puts focus back to it and close popups above it.
        Don't close our own child popup windows */
    var n = 0
    if (g.focusedWindow != null)
        while (n < g.openPopupStack.size) {
            val popup = g.openPopupStack[n]
            if (popup.window == null)
                continue
            assert(popup.window!!.flags has WindowFlags.Popup)
            if (popup.window!!.flags has WindowFlags.ChildWindow)
                continue

            var hasFocus = false
            var m = n
            while (m < g.openPopupStack.size && !hasFocus) {
                hasFocus = g.openPopupStack[m].window != null && g.openPopupStack[m].window!!.rootWindow == g.focusedWindow!!.rootWindow
                m++
            }
            if (!hasFocus)
                break
            n++
        }

    if (n < g.openPopupStack.size)   // This test is not required but it allows to set a useful breakpoint on the line below
        TODO() //g.OpenPopupStack.resize(n)
}

//static void             ClosePopupToLevel(int remaining);
//static void             ClosePopup(ImGuiID id);
//static bool             IsPopupOpen(ImGuiID id);
fun getFrontMostModalRootWindow(): Window? {
    for (n in g.openPopupStack.size - 1 downTo 0) {
        val frontMostPopup = g.openPopupStack[n].window
        if (frontMostPopup != null && frontMostPopup.flags has WindowFlags.Modal)
            return frontMostPopup
    }
    return null
}

fun findBestPopupWindowPos(basePos: Vec2, window: Window, rInner: Rect): Vec2 {

    val size = window.size

    /*  Clamp into visible area while not overlapping the cursor. Safety padding is optional if our popup size won't fit
        without it. */
    val safePadding = Style.displaySafeAreaPadding
    val rOuter = Rect(getVisibleRect())
    rOuter.reduce(Vec2(if (size.x - rOuter.width > safePadding.x * 2) safePadding.x else 0f,
            if (size.y - rOuter.height > safePadding.y * 2) safePadding.y else 0f))
    val basePosClamped = glm.clamp(basePos, rOuter.min, rOuter.max - size)

    var n = if (window.autoPosLastDirection != -1) -1 else 0
    while (n < 4)   // Last, Right, down, up, left. (Favor last used direction).
    {
        val dir = if (n == -1) window.autoPosLastDirection else n
        n++
        val rect = Rect(
                if (dir == 0) rInner.max.x else rOuter.min.x, if (dir == 1) rInner.max.y else rOuter.min.y,
                if (dir == 3) rInner.min.x else rOuter.max.x, if (dir == 2) rInner.min.y else rOuter.max.y)
        if (rect.width < size.x || rect.height < size.y) continue
        window.autoPosLastDirection = dir
        return Vec2(if (dir == 0) rInner.max.x else if (dir == 3) rInner.min.x - size.x else basePosClamped.x,
                if (dir == 1) rInner.max.y else if (dir == 2) rInner.min.y - size.y else basePosClamped.y)
    }

    // Fallback, try to keep within display
    window.autoPosLastDirection = -1
    return Vec2(basePos).also {
        it.x = glm.max(glm.min(it.x + size.x, rOuter.max.x) - size.x, rOuter.min.x)
        it.y = glm.max(glm.min(it.y + size.y, rOuter.max.y) - size.y, rOuter.min.y)
    }
}

// Return false to discard a character.
//fun inputTextFilterCharacter(unsigned int* p_char, ImGuiInputTextFlags flags, ImGuiTextEditCallback callback, void* user_data): Boolean{
//
//    unsigned int c = *p_char;
//
//    if (c < 128 && c != ' ' && !isprint((int)(c & 0xFF)))
//    {
//        bool pass = false;
//        pass |= (c == '\n' && (flags & ImGuiInputTextFlags_Multiline));
//        pass |= (c == '\t' && (flags & ImGuiInputTextFlags_AllowTabInput));
//        if (!pass)
//            return false;
//    }
//
//    if (c >= 0xE000 && c <= 0xF8FF) // Filter private Unicode range. I don't imagine anybody would want to input them. GLFW on OSX seems to send private characters for special keys like arrow keys.
//        return false;
//
//    if (flags & (ImGuiInputTextFlags_CharsDecimal | ImGuiInputTextFlags_CharsHexadecimal | ImGuiInputTextFlags_CharsUppercase | ImGuiInputTextFlags_CharsNoBlank))
//    {
//        if (flags & ImGuiInputTextFlags_CharsDecimal)
//        if (!(c >= '0' && c <= '9') && (c != '.') && (c != '-') && (c != '+') && (c != '*') && (c != '/'))
//            return false;
//
//        if (flags & ImGuiInputTextFlags_CharsHexadecimal)
//        if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F'))
//            return false;
//
//        if (flags & ImGuiInputTextFlags_CharsUppercase)
//        if (c >= 'a' && c <= 'z')
//        *p_char = (c += (unsigned int)('A'-'a'));
//
//        if (flags & ImGuiInputTextFlags_CharsNoBlank)
//        if (ImCharIsSpace(c))
//            return false;
//    }
//
//    if (flags & ImGuiInputTextFlags_CallbackCharFilter)
//    {
//        ImGuiTextEditCallbackData callback_data;
//        memset(&callback_data, 0, sizeof(ImGuiTextEditCallbackData));
//        callback_data.EventFlag = ImGuiInputTextFlags_CallbackCharFilter;
//        callback_data.EventChar = (ImWchar)c;
//        callback_data.Flags = flags;
//        callback_data.UserData = user_data;
//        if (callback(&callback_data) != 0)
//        return false;
//        *p_char = callback_data.EventChar;
//        if (!callback_data.EventChar)
//            return false;
//    }
//
//    return true;
//}
//static int              InputTextCalcTextLenAndLineCount(const char* text_begin, const char** out_text_end);

fun inputTextCalcTextSizeW(text: String, textEnd: Int, /*const ImWchar** remaining = NULL, */ outOffset: Vec2? = null,
                           stopOnNewLine: Boolean = false): Vec2 {

    val font = g.font
    val lineHeight = g.fontSize
    val scale = lineHeight / font.fontSize

    val textSize = Vec2()
    var lineWidth = 0f

    var s = 0
    while (s < textEnd) {
        val c = text[s++]
        if (c == '\n') {
            textSize.x = glm.max(textSize.x, lineWidth)
            textSize.y += lineHeight
            lineWidth = 0f
            if (stopOnNewLine)
                break
            continue
        }
        if (c == '\r') continue

        val charWidth = font.getCharAdvance(c) * scale
        lineWidth += charWidth
    }

    if (textSize.x < lineWidth)
        textSize.x = lineWidth

    // offset allow for the possibility of sitting after a trailing \n
    outOffset?.let {
        it.x = lineWidth
        it.y = textSize.y + lineHeight
    }

    if (lineWidth > 0 || textSize.y == 0f)  // whereas size.y will ignore the trailing \n
        textSize.y += lineHeight

//    if (remaining) TODO check if needed
//    *remaining = s

    return textSize
}
//
//static inline void      DataTypeFormatString(ImGuiDataType data_type, void* data_ptr, const char* display_format, char* buf, int buf_size);

/** JVM Imgui, dataTypeFormatString replacement */
fun FloatArray.format(dataType: DataType, decimalPrecision: Int) = when (dataType) {

    DataType.Int ->
        if (decimalPrecision < 0) "%d".format(Style.locale, this[0])
        else "%.${decimalPrecision}d".format(Style.locale, this[0])
    DataType.Float ->
        /*  Ideally we'd have a minimum decimal precision of 1 to visually denote that it is a float, while hiding
            non-significant digits?         */
        if (decimalPrecision < 0) "%f".format(Style.locale, this[0])
        else "%.${decimalPrecision}f".format(Style.locale, this[0])
    else -> throw Error("unsupported format data type")
}
//static void             DataTypeApplyOp(ImGuiDataType data_type, int op, void* value1, const void* value2);
//static bool             DataTypeApplyOpFromText(const char* buf, const char* initial_value_buf, ImGuiDataType data_type, void* data_ptr, const char* scalar_format);

//-----------------------------------------------------------------------------
// Platform dependent default implementations
//-----------------------------------------------------------------------------

//static const char*      GetClipboardTextFn_DefaultImpl(void* user_data);
//static void             SetClipboardTextFn_DefaultImpl(void* user_data, const char* text);
//static void             ImeSetInputScreenPosFn_DefaultImpl(int x, int y);