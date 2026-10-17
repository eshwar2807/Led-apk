package com.eshwar.reelplay.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The project being edited, with undo/redo. Every change goes through [commit]; slider drags
 * use [live] while moving and [settle] when released, so one drag is one undo step.
 */
class EditorState {

    var project by mutableStateOf(Project())
        private set
    var selectedId by mutableStateOf<Long?>(null)

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var liveStart: Project? = null

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val selectedClip: Clip? get() = project.clips.firstOrNull { it.id == selectedId }
    val isDirty: Boolean get() = undoStack.isNotEmpty() || liveStart != null

    private var nextId = 1L
    fun newId(): Long = nextId++

    fun commit(next: Project) {
        if (next == project) return
        settle()
        push(project)
        project = next
    }

    fun live(next: Project) {
        if (liveStart == null) liveStart = project
        project = next
    }

    fun settle() {
        val start = liveStart ?: return
        liveStart = null
        if (start != project) push(start)
    }

    fun undo() {
        settle()
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        project = prev
        fixSelection()
        refresh()
    }

    fun redo() {
        settle()
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        project = next
        fixSelection()
        refresh()
    }

    fun updateClip(id: Long, live: Boolean = false, change: (Clip) -> Clip) {
        val next = project.copy(clips = project.clips.map { if (it.id == id) change(it) else it })
        if (live) live(next) else commit(next)
    }

    fun updateAllClips(change: (Clip) -> Clip) {
        commit(project.copy(clips = project.clips.map(change)))
    }

    private fun push(p: Project) {
        undoStack.addLast(p)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        refresh()
    }

    private fun fixSelection() {
        if (project.clips.none { it.id == selectedId }) selectedId = null
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
