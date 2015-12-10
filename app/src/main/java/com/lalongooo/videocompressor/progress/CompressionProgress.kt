package com.lalongooo.videocompressor.progress

data class CompressionProgress(val processedDuration: Long, val totalDuration: Long) {

    val percentage: Long
        get() = if (totalDuration != 0L)
            (processedDuration / totalDuration.toFloat() * 100).toLong()
        else 0L

}