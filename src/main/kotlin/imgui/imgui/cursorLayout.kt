package imgui.imgui

import glm_.glm
import glm_.vec2.Vec2
import imgui.ImGui.currentWindow
import imgui.ImGui.currentWindowRead
import imgui.ImGui.itemAdd
import imgui.ImGui.itemSize
import imgui.Style
import imgui.internal.GroupData
import imgui.internal.Rect
import imgui.Context as g

interface imgui_cursorLayout {


//    IMGUI_API void          Separator();                                                        // horizontal line

    /** Call between widgets or groups to layout them horizontally
     *  Gets back to previous line and continue with horizontal layout
     *      posX == 0      : follow right after previous item
     *      posX != 0      : align to specified x position (relative to window/group left)
     *      spacingW < 0   : use default spacing if posX == 0, no spacing if posX != 0
     *      spacingW >= 0  : enforce spacing amount    */
    fun sameLine(posX: Float = 0f, spacingW: Float = -1f) {

        val window = currentWindow
        if (window.skipItems) return

        with(window) {
            if (posX != 0f)
                dc.cursorPos.put(
                        pos.x - scroll.x + posX + glm.max(0f, spacingW) + dc.groupOffsetX + dc.columnsOffsetX,
                        dc.cursorPosPrevLine.y)
            else
                dc.cursorPos.put(
                        dc.cursorPosPrevLine.x + if (spacingW < 0f) Style.itemSpacing.x else spacingW,
                        dc.cursorPosPrevLine.y)
            dc.currentLineHeight = dc.prevLineHeight
            dc.currentLineTextBaseOffset = dc.prevLineTextBaseOffset
        }
    }
//    IMGUI_API void          NewLine();                                                          // undo a SameLine()
//    IMGUI_API void          Spacing();                                                          // add vertical spacing
//    IMGUI_API void          Dummy(const ImVec2& size);                                          // add a dummy item of given size
//    IMGUI_API void          Indent(float indent_w = 0.0f);                                      // move content position toward the right, by style.IndentSpacing or indent_w if >0
//    IMGUI_API void          Unindent(float indent_w = 0.0f);                                    // move content position back to the left, by style.IndentSpacing or indent_w if >0


    /** Lock horizontal starting position + capture group bounding box into one "item" (so you can use IsItemHovered()
     *  or layout primitives such as SameLine() on whole group, etc.)   */
    fun beginGroup() {

        with(currentWindow) {
            dc.groupStack.add(
                    GroupData().apply {
                        backupCursorPos put dc.cursorPos
                        backupCursorMaxPos put dc.cursorMaxPos
                        backupIndentX = dc.indentX
                        backupGroupOffsetX = dc.groupOffsetX
                        backupCurrentLineHeight = dc.currentLineHeight
                        backupCurrentLineTextBaseOffset = dc.currentLineTextBaseOffset
                        backupLogLinePosY = dc.logLinePosY
                        backupActiveIdIsAlive = g.activeIdIsAlive
                        advanceCursor = true
                    })
            dc.groupOffsetX = dc.cursorPos.x - pos.x - dc.columnsOffsetX
            dc.indentX = dc.groupOffsetX
            dc.cursorMaxPos put dc.cursorPos
            dc.currentLineHeight = 0f
            dc.logLinePosY = dc.cursorPos.y - 9999f
        }
    }

    fun endGroup() {

        val window = currentWindow

        assert(window.dc.groupStack.isNotEmpty())    // Mismatched BeginGroup()/EndGroup() calls

        val groupData = window.dc.groupStack.last()

        val groupBb = Rect(groupData.backupCursorPos, window.dc.cursorMaxPos)
        groupBb.max.y -= Style.itemSpacing.y      // Cancel out last vertical spacing because we are adding one ourselves.
        groupBb.max = glm.max(groupBb.min, groupBb.max)

        with(window.dc) {
            cursorPos put groupData.backupCursorPos
            cursorMaxPos put glm.max(groupData.backupCursorMaxPos, cursorMaxPos)
            currentLineHeight = groupData.backupCurrentLineHeight
            currentLineTextBaseOffset = groupData.backupCurrentLineTextBaseOffset
            indentX = groupData.backupIndentX
            groupOffsetX = groupData.backupGroupOffsetX
            logLinePosY = cursorPos.y - 9999f
        }

        if (groupData.advanceCursor) {
            window.dc.currentLineTextBaseOffset = glm.max(window.dc.prevLineTextBaseOffset, groupData.backupCurrentLineTextBaseOffset)      // FIXME: Incorrect, we should grab the base offset from the *first line* of the group but it is hard to obtain now.
            itemSize(groupBb.size, groupData.backupCurrentLineTextBaseOffset)
            itemAdd(groupBb)
        }

        /*  If the current ActiveId was declared within the boundary of our group, we copy it to LastItemId so
            IsItemActive() will function on the entire group.
            It would be be neater if we replaced window.DC.LastItemId by e.g. 'bool LastItemIsActive', but if you
            search for LastItemId you'll notice it is only used in that context.    */
        val activeIdWithinGroup = !groupData.backupActiveIdIsAlive && g.activeIdIsAlive && g.activeId != 0 &&
                g.activeIdWindow!!.rootWindow == window.rootWindow
        if (activeIdWithinGroup)
            window.dc.lastItemId = g.activeId
        if (activeIdWithinGroup && g.hoveredId == g.activeId) {
            window.dc.lastItemHoveredRect = true
            window.dc.lastItemHoveredAndUsable = true
        }

        window.dc.groupStack.pop() // TODO last() on top -> pop?

        //window->DrawList->AddRect(groupBb.Min, groupBb.Max, IM_COL32(255,0,255,255));   // Debug
    }

    /** cursor position is relative to window position  */
    var cursorPos get() = with(currentWindowRead!!) { dc.cursorPos - pos + scroll }
        set(value) = with(currentWindowRead!!) {
            dc.cursorPos put (pos - scroll + value)
            dc.cursorMaxPos = glm.max(dc.cursorMaxPos, dc.cursorPos)
        }

    /** cursor position is relative to window position  */
    var cursorPosX get() = with(currentWindowRead!!) { dc.cursorPos.x - pos.x + scroll.x }
        set(value) = with(currentWindowRead!!) {
            dc.cursorPos.x = pos.x - scroll.x + value
            dc.cursorMaxPos.x = glm.max(dc.cursorMaxPos.x, dc.cursorPos.x)
        }

    /** cursor position is relative to window position  */
    var cursorPosY get() = with(currentWindowRead!!) { dc.cursorPos.y - pos.y + scroll.y }
        set(value) = with(currentWindowRead!!) {
            dc.cursorPos.y = pos.y - scroll.y + value
            dc.cursorMaxPos.y = glm.max(dc.cursorMaxPos.y, dc.cursorPos.y)
        }

    /** initial cursor position */
    val cursorStartPos get() = with(currentWindowRead!!) { dc.cursorStartPos - pos }

    /** cursor position in absolute screen coordinates [0..io.DisplaySize] (useful to work with ImDrawList API) */
    var cursorScreenPos get() = currentWindowRead!!.dc.cursorPos
        set(value) = with(currentWindowRead!!.dc) { cursorPos put value; cursorPos max_ cursorMaxPos }

    /** call once if the first item on the line is a Text() item and you want to vertically lower it to match
     *  subsequent (bigger) widgets */
    fun alignFirstTextHeightToWidgets() {
        val window = currentWindow
        if (window.skipItems) return
        /*  Declare a dummy item size to that upcoming items that are smaller will center-align on the newly expanded
            line height.         */
        itemSize(Vec2(0f, g.fontSize + Style.framePadding.y * 2), Style.framePadding.y)
        sameLine(0f, 0f)
    }

    /** height of font == GetWindowFontSize()   */
    val textLineHeight get() = g.fontSize

    /** distance (in pixels) between 2 consecutive lines of text == GetWindowFontSize() + GetStyle().ItemSpacing.y  */
    val textLineHeightWithSpacing get() = g.fontSize + Style.itemSpacing.y

    /** distance (in pixels) between 2 consecutive lines of standard height widgets ==
     *  GetWindowFontSize() + GetStyle().FramePadding.y*2 + GetStyle().ItemSpacing.y    */
    val itemsLineHeightWithSpacing get() = g.fontSize + Style.framePadding.y * 2f + Style.itemSpacing.y

}