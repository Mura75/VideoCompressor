package com.lalongooo.videocompressor.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.util.Log

import com.lalongooo.videocompressor.Config
import wseemann.media.FFmpegMediaMetadataRetriever

import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

public class MediaController {

    private var videoConvertFirstWrite = true

    private fun didWriteData() {
        val firstWrite = videoConvertFirstWrite
        if (firstWrite) {
            videoConvertFirstWrite = false
        }
    }

    class VideoConvertRunnable private constructor(private val videoPath: String) : Runnable {

        override fun run() {
            MediaController.instance.convertVideo(videoPath)
        }

        companion object {

            fun runConversion(videoPath: String) {
                Thread(Runnable {
                    try {
                        val wrapper = VideoConvertRunnable(videoPath)
                        val th = Thread(wrapper, "VideoConvertRunnable")
                        th.start()
                        th.join()
                    } catch (e: Exception) {
                        Log.e("tmessages", e.message)
                    }
                }).start()
            }
        }
    }

    fun scheduleVideoConvert(path: String) {
        startVideoConvertFromQueue(path)
    }

    private fun startVideoConvertFromQueue(path: String) {
        VideoConvertRunnable.runConversion(path)
    }

    @Throws(Exception::class)
    private fun readAndWriteTrack(extractor: MediaExtractor, mediaMuxer: MP4Builder, info: MediaCodec.BufferInfo, start: Long, end: Long, file: File, isAudio: Boolean): Long {
        val trackIndex = selectTrack(extractor, isAudio)
        if (trackIndex >= 0) {
            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val muxerTrackIndex = mediaMuxer.addTrack(trackFormat, isAudio)
            val maxBufferSize = trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            var inputDone = false
            if (start > 0) {
                extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            var startTime: Long = -1

            while (!inputDone) {

                var eof = false
                val index = extractor.sampleTrackIndex
                if (index == trackIndex) {
                    info.size = extractor.readSampleData(buffer, 0)

                    if (info.size < 0) {
                        info.size = 0
                        eof = true
                    } else {
                        info.presentationTimeUs = extractor.sampleTime
                        if (start > 0 && startTime == -1L) {
                            startTime = info.presentationTimeUs
                        }
                        if (end < 0 || info.presentationTimeUs < end) {
                            info.offset = 0
                            info.flags = extractor.sampleFlags
                            if (mediaMuxer.writeSampleData(muxerTrackIndex, buffer, info, isAudio)) {
                                didWriteData();
                            }
                            extractor.advance()
                        } else {
                            eof = true
                        }
                    }
                } else if (index == -1) {
                    eof = true
                }
                if (eof) {
                    inputDone = true
                }
            }

            extractor.unselectTrack(trackIndex)
            return startTime
        }
        return -1
    }

    private fun selectTrack(extractor: MediaExtractor, audio: Boolean): Int {
        val numTracks = extractor.trackCount
        for (i in 0..numTracks - 1) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i
                }
            }
        }
        return -5
    }

    fun convertVideo(path: String): Boolean {
        val mediaRetriever = MediaMetadataRetriever()
        mediaRetriever.setDataSource(path)
        val width = mediaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val height = mediaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)

        val ffpMpegRetriever = FFmpegMediaMetadataRetriever()
        ffpMpegRetriever.setDataSource(path);
        val rotation = ffpMpegRetriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val duration = ffpMpegRetriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION)
        ffpMpegRetriever.release();

        val startTime: Long = -1L
        val endTime: Long = -1L

        var resultWidth = 640
        var resultHeight = 360

        var rotationValue = Integer.valueOf(rotation)!!
        val originalWidth = Integer.valueOf(width)!!
        val originalHeight = Integer.valueOf(height)!!
        val originalDuration = duration.toLong()

        val bitrate = 400000
        var rotateRender = 0

        val cacheFile = File("${Environment.getExternalStorageDirectory()}${File.separator}${Config.VIDEO_COMPRESSOR_APPLICATION_DIR_NAME}${Config.VIDEO_COMPRESSOR_COMPRESSED_VIDEOS_DIR}VIDEO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")

        if (Build.VERSION.SDK_INT < 18 && resultHeight > resultWidth && resultWidth != originalWidth && resultHeight != originalHeight) {
            val temp = resultHeight
            resultHeight = resultWidth
            resultWidth = temp
            rotationValue = 90
            rotateRender = 270
        } else if (Build.VERSION.SDK_INT > 20) {
            if (rotationValue == 90) {
                val temp = resultHeight
                resultHeight = resultWidth
                resultWidth = temp
                rotationValue = 0
                rotateRender = 270
            } else if (rotationValue == 180) {
                rotateRender = 180
                rotationValue = 0
            } else if (rotationValue == 270) {
                val temp = resultHeight
                resultHeight = resultWidth
                resultWidth = temp
                rotationValue = 0
                rotateRender = 90
            }
        }


        val inputFile = File(path)
        if (!inputFile.canRead()) {
            didWriteData()
            return false
        }

        videoConvertFirstWrite = true
        var error = false
        var videoStartTime = startTime

        val time = System.currentTimeMillis()

        if (resultWidth != 0 && resultHeight != 0) {
            var mediaMuxer: MP4Builder? = null
            var extractor: MediaExtractor? = null

            try {
                val info = MediaCodec.BufferInfo()
                val movie = Mp4Movie()
                movie.cacheFile = cacheFile
                movie.setRotation(rotationValue)
                movie.setSize(resultWidth, resultHeight)
                mediaMuxer = MP4Builder().createMovie(movie)
                extractor = MediaExtractor()
                extractor.setDataSource(inputFile.toString())


                if (resultWidth != originalWidth || resultHeight != originalHeight) {
                    val videoIndex: Int
                    videoIndex = selectTrack(extractor, false)

                    if (videoIndex >= 0) {
                        var decoder: MediaCodec? = null
                        var encoder: MediaCodec? = null
                        var inputSurface: InputSurface? = null
                        var outputSurface: OutputSurface? = null

                        try {
                            var videoTime: Long = -1
                            var outputDone = false
                            var inputDone = false
                            var decoderDone = false
                            var swapUV = 0
                            var videoTrackIndex = -5

                            var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                            var processorType = PROCESSOR_TYPE_OTHER
                            val manufacturer = Build.MANUFACTURER.toLowerCase()
                            if (Build.VERSION.SDK_INT < 18) {
                                val codecInfo = selectCodec(MIME_TYPE)

                                codecInfo?.let {
                                    colorFormat = selectColorFormat(codecInfo, MIME_TYPE)
                                    if (colorFormat == 0) {
                                        throw RuntimeException("no supported color format")
                                    }
                                    val codecName = codecInfo.name
                                    if (codecName.contains("OMX.qcom.")) {
                                        processorType = PROCESSOR_TYPE_QCOM
                                        if (Build.VERSION.SDK_INT == 16) {
                                            if (manufacturer == "lge" || manufacturer == "nokia") {
                                                swapUV = 1
                                            }
                                        }
                                    } else if (codecName.contains("OMX.Intel.")) {
                                        processorType = PROCESSOR_TYPE_INTEL
                                    } else if (codecName == "OMX.MTK.VIDEO.ENCODER.AVC") {
                                        processorType = PROCESSOR_TYPE_MTK
                                    } else if (codecName == "OMX.SEC.AVC.Encoder") {
                                        processorType = PROCESSOR_TYPE_SEC
                                        swapUV = 1
                                    } else if (codecName == "OMX.TI.DUCATI1.VIDEO.H264E") {
                                        processorType = PROCESSOR_TYPE_TI
                                    }
                                    Log.e("tmessages", "codec = " + codecInfo.name + " manufacturer = " + manufacturer + "device = " + Build.MODEL)
                                }

                            }
                            Log.e("tmessages", "colorFormat = " + colorFormat)

                            var resultHeightAligned = resultHeight
                            var padding = 0
                            var bufferSize = resultWidth * resultHeight * 3 / 2
                            if (processorType == PROCESSOR_TYPE_OTHER) {
                                if (resultHeight % 16 != 0) {
                                    resultHeightAligned += (16 - (resultHeight % 16))
                                    padding = resultWidth * (resultHeightAligned - resultHeight)
                                    bufferSize += padding * 5 / 4
                                }
                            } else if (processorType == PROCESSOR_TYPE_QCOM) {
                                if (manufacturer.toLowerCase() != "lge") {
                                    val uvoffset = (resultWidth * resultHeight + 2047) and 2047.inv()
                                    padding = uvoffset - (resultWidth * resultHeight)
                                    bufferSize += padding
                                }
                            } else if (processorType == PROCESSOR_TYPE_TI) {
                                //resultHeightAligned = 368;
                                //bufferSize = resultWidth * resultHeightAligned * 3 / 2;
                                //resultHeightAligned += (16 - (resultHeight % 16));
                                //padding = resultWidth * (resultHeightAligned - resultHeight);
                                //bufferSize += padding * 5 / 4;
                            } else if (processorType == PROCESSOR_TYPE_MTK) {
                                if (manufacturer == "baidu") {
                                    resultHeightAligned += (16 - (resultHeight % 16))
                                    padding = resultWidth * (resultHeightAligned - resultHeight)
                                    bufferSize += padding * 5 / 4
                                }
                            }

                            // setup input and output formats
                            val inputFormat = buildInputFormat(extractor, startTime, videoIndex)
                            val outputFormat = buildOutputFormat(bitrate, colorFormat, resultHeight, resultWidth)

                            // start the encoder and create the input surface if needed
                            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
                            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            if (Build.VERSION.SDK_INT >= 18) {
                                inputSurface = InputSurface(encoder.createInputSurface())
                                inputSurface.makeCurrent()
                            }
                            encoder.start()

                            if (Build.VERSION.SDK_INT >= 18) {
                                outputSurface = OutputSurface()
                            } else {
                                outputSurface = OutputSurface(resultWidth, resultHeight, rotateRender)
                            }

                            // start decoder
                            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME))
                            decoder.configure(inputFormat, outputSurface.surface, null, 0)
                            decoder.start()

                            var decoderInputBuffers: Array<ByteBuffer>? = null;
                            var encoderOutputBuffers: Array<ByteBuffer>? = null;
                            var encoderInputBuffers: Array<ByteBuffer>? = null;
                            if (Build.VERSION.SDK_INT < 21) {
                                decoderInputBuffers = decoder.inputBuffers
                                encoderOutputBuffers = encoder.outputBuffers

                                if (Build.VERSION.SDK_INT < 18) {
                                    encoderInputBuffers = encoder.inputBuffers
                                }
                            }

                            val TIMEOUT_USEC = 2500
                            while (!outputDone) {
                                if (!inputDone) {
                                    var eof = false
                                    val index = extractor.sampleTrackIndex
                                    if (index == videoIndex) {
                                        val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                                        if (inputBufIndex >= 0) {
                                            Log.d("cenas", "Time: ${extractor.sampleTime / 1000000} /  ${originalDuration / 1000}")
                                            val inputBuf: ByteBuffer
                                            if (Build.VERSION.SDK_INT < 21) {
                                                inputBuf = decoderInputBuffers!![inputBufIndex]
                                            } else {
                                                inputBuf = decoder.getInputBuffer(inputBufIndex);
                                            }

                                            val chunkSize = extractor.readSampleData(inputBuf, 0)
                                            if (chunkSize < 0) {
                                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                                inputDone = true
                                            } else {
                                                decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, extractor.sampleTime, 0)
                                                extractor.advance()
                                            }
                                        }
                                    } else if (index == -1) {
                                        eof = true
                                    }
                                    if (eof) {
                                        val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                                        if (inputBufIndex >= 0) {
                                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            inputDone = true
                                        }
                                    }
                                }

                                var decoderOutputAvailable = !decoderDone
                                var encoderOutputAvailable = true
                                while (decoderOutputAvailable || encoderOutputAvailable) {
                                    val encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        encoderOutputAvailable = false
                                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                        if (Build.VERSION.SDK_INT < 21) {
                                            encoderOutputBuffers = encoder.outputBuffers
                                        }
                                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                        val newFormat = encoder.outputFormat
                                        if (videoTrackIndex == -5) {
                                            videoTrackIndex = mediaMuxer!!.addTrack(newFormat, false)
                                        }
                                    } else if (encoderStatus < 0) {
                                        throw RuntimeException("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus)
                                    } else {
                                        val encodedData: ByteBuffer?
                                        if (Build.VERSION.SDK_INT < 21) {
                                            encodedData = encoderOutputBuffers!![encoderStatus]
                                        } else {
                                            encodedData = encoder.getOutputBuffer(encoderStatus)
                                        }
                                        if (encodedData == null) {
                                            throw RuntimeException("encoderOutputBuffer $encoderStatus was null")
                                        }
                                        if (info.size > 1) {
                                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                                if (mediaMuxer!!.writeSampleData(videoTrackIndex, encodedData, info, false)) {
                                                    didWriteData()
                                                }
                                            } else if (videoTrackIndex == -5) {
                                                val csd = ByteArray(info.size)
                                                encodedData.limit(info.offset + info.size)
                                                encodedData.position(info.offset)
                                                encodedData.get(csd)
                                                var sps: ByteBuffer? = null
                                                var pps: ByteBuffer? = null
                                                for (a in info.size - 1 downTo 0) {
                                                    if (a > 3) {
                                                        if (csd[a].toInt() == 1 && csd[a - 1].toInt() == 0 && csd[a - 2].toInt() == 0 && csd[a - 3].toInt() == 0) {
                                                            sps = ByteBuffer.allocate(a - 3)
                                                            pps = ByteBuffer.allocate(info.size - (a - 3))
                                                            sps!!.put(csd, 0, a - 3).position(0)
                                                            pps!!.put(csd, a - 3, info.size - (a - 3)).position(0)
                                                            break
                                                        }
                                                    } else {
                                                        break
                                                    }
                                                }

                                                val newFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight)
                                                if (sps != null && pps != null) {
                                                    newFormat.setByteBuffer("csd-0", sps)
                                                    newFormat.setByteBuffer("csd-1", pps)
                                                }
                                                videoTrackIndex = mediaMuxer!!.addTrack(newFormat, false)
                                            }
                                        }
                                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                        encoder.releaseOutputBuffer(encoderStatus, false)
                                    }
                                    if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        continue
                                    }

                                    if (!decoderDone) {
                                        val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                                        if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                            decoderOutputAvailable = false
                                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                            val newFormat = decoder.outputFormat
                                            Log.e("tmessages", "newFormat = " + newFormat)
                                        } else if (decoderStatus < 0) {
                                            throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus)
                                        } else {
                                            var doRender: Boolean
                                            if (Build.VERSION.SDK_INT >= 18) {
                                                doRender = info.size != 0
                                            } else {
                                                doRender = info.size != 0 || info.presentationTimeUs != 0L
                                            }
                                            if (endTime > 0 && info.presentationTimeUs >= endTime) {
                                                inputDone = true
                                                decoderDone = true
                                                doRender = false
                                                info.flags = info.flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            }
                                            if (startTime > 0 && videoTime == -1L) {
                                                if (info.presentationTimeUs < startTime) {
                                                    doRender = false
                                                    Log.e("tmessages", "drop frame startTime = " + startTime + " present time = " + info.presentationTimeUs)
                                                } else {
                                                    videoTime = info.presentationTimeUs
                                                }
                                            }
                                            decoder.releaseOutputBuffer(decoderStatus, doRender)
                                            if (doRender) {
                                                var errorWait = false
                                                try {
                                                    outputSurface.awaitNewImage()
                                                } catch (e: Exception) {
                                                    errorWait = true
                                                    Log.e("tmessages", e.message)
                                                }

                                                if (!errorWait) {
                                                    if (Build.VERSION.SDK_INT >= 18) {
                                                        outputSurface.drawImage(false)
                                                        inputSurface!!.setPresentationTime(info.presentationTimeUs * 1000)
                                                        inputSurface.swapBuffers()
                                                    } else {
                                                        val inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                                                        if (inputBufIndex >= 0) {
                                                            outputSurface.drawImage(true)
                                                            val rgbBuf = outputSurface.frame
                                                            val yuvBuf = encoderInputBuffers!![inputBufIndex]
                                                            yuvBuf.clear()
                                                            convertVideoFrame(rgbBuf, yuvBuf, colorFormat, resultWidth, resultHeight, padding, swapUV)
                                                            encoder.queueInputBuffer(inputBufIndex, 0, bufferSize, info.presentationTimeUs, 0)
                                                        } else {
                                                            Log.e("tmessages", "input buffer not available")
                                                        }
                                                    }
                                                }
                                            }
                                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                                decoderOutputAvailable = false
                                                Log.e("tmessages", "decoder stream end")
                                                if (Build.VERSION.SDK_INT >= 18) {
                                                    encoder.signalEndOfInputStream()
                                                } else {
                                                    val inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                                                    if (inputBufIndex >= 0) {
                                                        encoder.queueInputBuffer(inputBufIndex, 0, 1, info.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (videoTime != -1L) {
                                videoStartTime = videoTime
                            }
                        } catch (e: Exception) {
                            Log.e("tmessages", e.message)
                            error = true
                        }

                        extractor.unselectTrack(videoIndex)

                        outputSurface?.release()
                        inputSurface?.release()

                        decoder?.let {
                            it.stop()
                            it.release()
                        }

                        encoder?.let {
                            it.stop()
                            it.release()
                        }
                    }
                } else {
                    val videoTime = readAndWriteTrack(extractor, mediaMuxer, info, startTime, endTime, cacheFile, false)
                    if (videoTime != -1L) {
                        videoStartTime = videoTime
                    }
                }
                if (!error) {
                    readAndWriteTrack(extractor, mediaMuxer, info, videoStartTime, endTime, cacheFile, true)
                }
            } catch (e: Exception) {
                Log.e("tmessages", e.message)
            } finally {
                if (extractor != null) {
                    extractor.release()
                }
                if (mediaMuxer != null) {
                    try {
                        mediaMuxer.finishMovie()
                    } catch (e: Exception) {
                        Log.e("tmessages", e.message)
                    }

                }
                Log.e("tmessages", "time = " + (System.currentTimeMillis() - time))
            }
        } else {
            didWriteData()
            return false
        }
        didWriteData()

        inputFile.delete()
        return true
    }

    private fun buildInputFormat(extractor: MediaExtractor, startTime: Long, videoIndex: Int): MediaFormat {
        with(extractor) {
            selectTrack(videoIndex)
            if (startTime > 0) {
                seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            } else {
                seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
        }

        return extractor.getTrackFormat(videoIndex)
    }

    private fun buildOutputFormat(bitrate: Int, colorFormat: Int, resultHeight: Int, resultWidth: Int): MediaFormat? {
        val outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight)
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, if (bitrate != 0) bitrate else 921600)
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        if (Build.VERSION.SDK_INT < 18) {
            outputFormat.setInteger("stride", resultWidth + 32)
            outputFormat.setInteger("slice-height", resultHeight)
        }
        return outputFormat
    }

    companion object {

        val MIME_TYPE = "video/avc"
        private val PROCESSOR_TYPE_OTHER = 0
        private val PROCESSOR_TYPE_QCOM = 1
        private val PROCESSOR_TYPE_INTEL = 2
        private val PROCESSOR_TYPE_MTK = 3
        private val PROCESSOR_TYPE_SEC = 4
        private val PROCESSOR_TYPE_TI = 5

        val instance: MediaController = MediaController()

        @SuppressLint("NewApi")
        fun selectColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Int {
            val capabilities = codecInfo.getCapabilitiesForType(mimeType)
            var lastColorFormat = 0
            for (i in capabilities.colorFormats.indices) {
                val colorFormat = capabilities.colorFormats[i]
                if (isRecognizedFormat(colorFormat)) {
                    lastColorFormat = colorFormat
                    if (!(codecInfo.name == "OMX.SEC.AVC.Encoder" && colorFormat == 19)) {
                        return colorFormat
                    }
                }
            }
            return lastColorFormat
        }

        private fun isRecognizedFormat(colorFormat: Int): Boolean = arrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar).contains(colorFormat)
    }


    external fun convertVideoFrame(src: ByteBuffer, dest: ByteBuffer, destFormat: Int, width: Int, height: Int, padding: Int, swap: Int): Int

    fun selectCodec(mimeType: String): MediaCodecInfo? {
        val numCodecs = MediaCodecList.getCodecCount()
        var lastCodecInfo: MediaCodecInfo? = null
        for (i in 0..numCodecs - 1) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (!codecInfo.isEncoder) {
                continue
            }
            val types = codecInfo.supportedTypes
            for (type in types) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    lastCodecInfo = codecInfo
                    if (lastCodecInfo!!.name != "OMX.SEC.avc.enc") {
                        return lastCodecInfo
                    } else if (lastCodecInfo.name == "OMX.SEC.AVC.Encoder") {
                        return lastCodecInfo
                    }
                }
            }
        }
        return lastCodecInfo
    }
}