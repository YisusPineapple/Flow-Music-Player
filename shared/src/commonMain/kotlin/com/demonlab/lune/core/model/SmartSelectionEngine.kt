package com.demonlab.lune.core.model

/**
 * Pure multiplatform helper functions for smart multi-selection in music libraries.
 * Powers range selection, same-album, same-artist, same-folder expansion, and batch actions.
 */
object SmartSelectionEngine {

    /**
     * Toggles an item ID in a set of selected IDs.
     */
    fun toggle(selected: Set<Long>, id: Long): Set<Long> {
        return if (id in selected) selected - id else selected + id
    }

    /**
     * Selects a contiguous range of IDs between an anchor ID and a target ID based on the ordered items list.
     */
    fun selectRange(
        orderedIds: List<Long>,
        currentSelected: Set<Long>,
        anchorId: Long?,
        targetId: Long
    ): Set<Long> {
        if (anchorId == null) return currentSelected + targetId
        val anchorIndex = orderedIds.indexOf(anchorId)
        val targetIndex = orderedIds.indexOf(targetId)
        if (anchorIndex == -1 || targetIndex == -1) return currentSelected + targetId

        val start = minOf(anchorIndex, targetIndex)
        val end = maxOf(anchorIndex, targetIndex)
        val rangeIds = orderedIds.subList(start, end + 1).toSet()
        return currentSelected + rangeIds
    }

    /**
     * Selects all items belonging to the same album as the currently selected items.
     */
    fun <T> selectSameAlbum(
        allItems: List<T>,
        currentSelectedIds: Set<Long>,
        idSelector: (T) -> Long,
        albumSelector: (T) -> String
    ): Set<Long> {
        val selectedAlbums = allItems
            .filter { idSelector(it) in currentSelectedIds }
            .map { albumSelector(it).trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        if (selectedAlbums.isEmpty()) return currentSelectedIds

        return allItems
            .filter { albumSelector(it).trim().lowercase() in selectedAlbums }
            .map { idSelector(it) }
            .toSet()
    }

    /**
     * Selects all items belonging to the same artist(s) as the currently selected items.
     */
    fun <T> selectSameArtist(
        allItems: List<T>,
        currentSelectedIds: Set<Long>,
        idSelector: (T) -> Long,
        artistsSelector: (T) -> List<String>
    ): Set<Long> {
        val selectedArtists = allItems
            .filter { idSelector(it) in currentSelectedIds }
            .flatMap { artistsSelector(it) }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        if (selectedArtists.isEmpty()) return currentSelectedIds

        return allItems
            .filter { item ->
                artistsSelector(item).any { it.trim().lowercase() in selectedArtists }
            }
            .map { idSelector(it) }
            .toSet()
    }

    /**
     * Selects all items belonging to the same folder as the currently selected items.
     */
    fun <T> selectSameFolder(
        allItems: List<T>,
        currentSelectedIds: Set<Long>,
        idSelector: (T) -> Long,
        folderSelector: (T) -> String
    ): Set<Long> {
        val selectedFolders = allItems
            .filter { idSelector(it) in currentSelectedIds }
            .map { folderSelector(it).trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        if (selectedFolders.isEmpty()) return currentSelectedIds

        return allItems
            .filter { folderSelector(it).trim().lowercase() in selectedFolders }
            .map { idSelector(it) }
            .toSet()
    }

    /**
     * Inverts the current selection over all available item IDs.
     */
    fun invert(allIds: List<Long>, currentSelected: Set<Long>): Set<Long> {
        val allSet = allIds.toSet()
        return allSet - currentSelected
    }
}
