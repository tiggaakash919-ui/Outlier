package com.example.domain

/**
 * Pure rotation selection, kept out of [ApiKeyManager] so it can be unit tested
 * without Android dependencies.
 */
object KeyRotation {
  /**
   * Returns the next slot after [activeSlot] (circular) that [isAvailable]
   * accepts, or null when no other slot can take over.
   */
  fun nextSlot(
    slots: List<Int>,
    activeSlot: Int,
    isAvailable: (Int) -> Boolean
  ): Int? {
    if (slots.isEmpty()) return null
    val ordered = slots.sorted()
    val startIndex = ordered.indexOf(activeSlot)
    val from = if (startIndex >= 0) startIndex + 1 else 0
    for (offset in 0 until ordered.size) {
      val slot = ordered[(from + offset) % ordered.size]
      if (slot == activeSlot) continue
      if (isAvailable(slot)) return slot
    }
    return null
  }
}
