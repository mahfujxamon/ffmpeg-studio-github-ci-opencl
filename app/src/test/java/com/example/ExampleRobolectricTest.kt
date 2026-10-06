package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.NativeFfmpegEngine
import com.example.data.media.AssetResolutionResult
import com.example.data.media.AssetResolver
import com.example.data.media.MediaProbe
import com.example.data.media.MediaResolver
import com.example.domain.model.RenderProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `test 1 app launches and reads app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Hybrid Video Editor", appName)
    }

    @Test
    fun `test 2 media resolver creates safe cache output destination`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = MediaResolver(context)
        val outFile = resolver.createOutputFile("test_video.mp4")
        assertNotNull(outFile)
        assertTrue(outFile.name.startsWith("test_video_"))
        assertTrue(outFile.name.endsWith(".mp4"))
        assertTrue(resolver.getOutputsDir().exists())
    }

    @Test
    fun `test 3 media probe safely uses MediaMetadataRetriever without invoking native FFmpeg`() = runBlocking {
        val probe = MediaProbe()
        val missingFile = File("/nonexistent/video.mp4")
        val result = probe.probeFile(missingFile, "video.mp4")
        // Fails gracefully via MediaMetadataRetriever without any native FFmpegKit call
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `test 4 native ffmpeg engine correctly detects encoder options`() {
        val nativeEngine = NativeFfmpegEngine()
        assertEquals("Native FFmpegKit", nativeEngine.name)
        assertNotNull(nativeEngine.processingMode)
        assertTrue(nativeEngine.processingMode.contains("Processing: CPU"))

        val hwEncoder = nativeEngine.detectEncoderFromCommand("ffmpeg -i in.mp4 -c:v h264_mediacodec out.mp4")
        assertEquals("MediaCodec AVC", hwEncoder)

        val swEncoder = nativeEngine.detectEncoderFromCommand("ffmpeg -i in.mp4 -c:v libx264 out.mp4")
        assertEquals("Software", swEncoder)

        val copyEncoder = nativeEngine.detectEncoderFromCommand("ffmpeg -i in.mp4 -c copy out.mp4")
        assertEquals("Stream Copy", copyEncoder)
    }

    @Test
    fun `test 5 opencl commands automatically receive explicit hardware device options`() {
        val nativeEngine = NativeFfmpegEngine()
        val plan = nativeEngine.prepareCommandForRuntime(
            "ffmpeg -i {input} -vf \"hwupload,unsharp_opencl=lx=5:ly=5:la=1.5,hwdownload,format=yuv420p\" -c:v libx264 {output}"
        )

        assertTrue(plan.openClRequested)
        assertTrue(plan.openClUsed)
        assertTrue(plan.command.startsWith("ffmpeg -init_hw_device opencl=ocl:0.0 -filter_hw_device ocl "))
        assertEquals(1, Regex("-init_hw_device\\s+opencl=ocl:0\\.0").findAll(plan.command).count())
        assertEquals(1, Regex("-filter_hw_device\\s+ocl").findAll(plan.command).count())
    }

    @Test
    fun `test 6 opencl device options are not duplicated when already present`() {
        val nativeEngine = NativeFfmpegEngine()
        val command =
            "ffmpeg -init_hw_device opencl=ocl:0.0 -filter_hw_device ocl -i in.mp4 -vf \"hwupload,unsharp_opencl\" -c:v libx264 out.mp4"
        val plan = nativeEngine.prepareCommandForRuntime(command)

        assertEquals(command, plan.command)
        assertTrue(plan.openClRequested)
        assertTrue(plan.openClUsed)
    }

    @Test
    fun `test 7 plain commands do not become opencl commands because a filename contains opencl`() {
        val nativeEngine = NativeFfmpegEngine()
        val command = "ffmpeg -i /tmp/opencl_demo.mp4 -vf scale=1280:720 -c:v libx264 out.mp4"
        val plan = nativeEngine.prepareCommandForRuntime(command)

        assertTrue(!plan.openClRequested)
        assertEquals(command, plan.command)
    }

    @Test
    fun `test 8 opencl command is defensively recognized when execute receives it without device options`() {
        val nativeEngine = NativeFfmpegEngine()
        val command = "-f lavfi -i color=c=black:s=16x16:r=1 -vf \"hwupload,unsharp_opencl,hwdownload\" -frames:v 1 -f null -"
        assertTrue(nativeEngine.isOpenClRequested(command))
    }

    @Test
    fun `test 9 render progress formatting displays real metrics correctly`() {
        val progress = RenderProgress(
            frame = 1821,
            fps = 29.8f,
            speed = 1.31f,
            bitrateKbps = 8200f,
            timeMs = 42000L,
            sizeBytes = 10485760L,
            progressRatio = 0.64f,
            etaSeconds = 24L
        )
        assertEquals("1.31x", progress.formattedSpeed)
        assertEquals("29.8", progress.formattedFps)
        assertEquals("8200 kbps", progress.formattedBitrate)
        assertEquals("00:42.0", progress.formattedTime)
        assertEquals("10.0 MB", progress.formattedSize)
    }

    @Test
    fun `test 10 session cancellation responds cleanly`() {
        val nativeEngine = NativeFfmpegEngine()
        var completedCalled = false

        val session = nativeEngine.execute(
            command = "-version",
            totalDurationMs = 0L,
            onLog = {},
            onStatistics = {},
            onComplete = { _, _, _, _ -> completedCalled = true }
        )

        assertNotNull(session)
        // Verify session can be cancelled without error
        session.cancel()
    }

    @Test
    fun `test 11 checkNativeRuntime never crashes and returns structured status`() {
        val nativeEngine = NativeFfmpegEngine()
        val status = nativeEngine.checkNativeRuntime()
        assertNotNull(status)
    }

    @Test
    fun `test 12 media publisher validates source file existence`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val publisher = com.example.data.media.MediaPublisher(context)
        val nonExistentFile = File("/tmp/nonexistent_test.mp4")
        val result = publisher.publishVideoToGallery(nonExistentFile, "test.mp4")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `test 13 asset resolver detects missing asset cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetResolver = com.example.data.media.AssetResolver(context)
        val cmd = "ffmpeg -i in.mp4 -filter_complex \"movie=missing_watermark.png [wm]; [0:v][wm] overlay\" out.mp4"
        val result = assetResolver.resolveAssetsInCommand(cmd)

        assertTrue(result is com.example.data.media.AssetResolutionResult.MissingAsset)
        val missing = (result as com.example.data.media.AssetResolutionResult.MissingAsset).assetPath
        assertEquals("missing_watermark.png", missing)
    }

    @Test
    fun `test 14 asset resolver inserts optimal filter threading without modifying user options`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetResolver = com.example.data.media.AssetResolver(context)

        // Case A: complex filtergraph without threads specified -> adds -filter_complex_threads
        val cmdA = "ffmpeg -i in.mp4 -filter_complex \"[0:v]boxblur=2:1[v]\" -map \"[v]\" out.mp4"
        val resolvedA = assetResolver.applyOptimalFilterThreading(cmdA)
        assertTrue(resolvedA.contains("-filter_complex_threads"))
        assertTrue(resolvedA.contains("[0:v]boxblur=2:1[v]"))

        // Case B: user already specified -filter_complex_threads 2 -> preserved untouched
        val cmdB = "ffmpeg -i in.mp4 -filter_complex_threads 2 -filter_complex \"[0:v]boxblur=2:1[v]\" out.mp4"
        val resolvedB = assetResolver.applyOptimalFilterThreading(cmdB)
        assertEquals(cmdB, resolvedB)
    }
}
