package com.mammonrn.phoneaikiosk.media

/** One video: a [Track] (its id says local or NAS) and what the phone's index knows of its picture. */
data class Video(val track: Track, val width: Int = 0, val height: Int = 0, val mime: String? = null)
