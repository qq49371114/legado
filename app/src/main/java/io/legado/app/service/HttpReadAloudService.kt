package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.AiTtsEngineFactory
import io.legado.app.help.tts.MultiRoleNarrator
import io.legado.app.help.tts.SmartSegmenter
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            val httpTts = ReadAloud.httpTTS
            // AI TTS 引擎走独立流程
            if (httpTts != null && AiTtsEngineFactory.isAiEngine(httpTts)) {
                // Edge-TTS统一走完整音频合成：兼容旧版数据库里streamMode=true的预设，
                // 避免临时文件扩展名/播放时序导致点击按钮后看起来没有启动。
                if (httpTts.engineType == io.legado.app.help.tts.AiTtsEngine.TYPE_EDGE) {
                    downloadAndPlayAudiosAi()
                } else if (httpTts.streamMode) {
                    downloadAndPlayAudiosAiStream()
                } else {
                    downloadAndPlayAudiosAi()
                }
            } else if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val fileName = md5SpeakFileName(text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) {
                                createSpeakFile(fileName, inputStream)
                            } else {
                                createSilentSound(fileName)
                            }
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> pauseReadAloud()
                            }
                            return@execute
                        }
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Main) {
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }
                preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            coroutineContext.ensureActive()
            val fileName = md5SpeakFileName(content)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    val fileName = md5SpeakFileName(text)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            coroutineContext.ensureActive()
            val fileName = md5SpeakFileName(content)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException -> Unit
                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = Uri.parse(fileName)
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    headerMapF = httpTts.getHeaderMap(true),
                    readTimeout = 300 * 1000L,
                    coroutineContext = coroutineContext
                )
                var response = analyzeUrl.getResponseAwait()
                coroutineContext.ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val ct = httpTts.contentType
                    if (contentType == "application/json") {
                        throw NoStackTraceException(response.body!!.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException("TTS服务器返回错误：" + response.body!!.string())
                        }
                    }
                }
                coroutineContext.ensureActive()
                response.body!!.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun md5SpeakFileName(content: String): String {
        val tts = ReadAloud.httpTTS
        // 缓存Key包含引擎类型/模型/音色/格式，避免不同AI音色共用错误缓存
        val engineKey = listOf(
            "ai-cache-v3", // 缓存协议版本：变更后自动避开旧版损坏音频
            tts?.engineType ?: "http",
            tts?.url ?: "",
            tts?.voiceModel ?: "",
            tts?.voiceName ?: "",
            tts?.apiFormat ?: "mp3",
            tts?.multiRoleEnabled?.toString() ?: "false",
            tts?.narratorVoice ?: "",
            speechRate.toString()
        ).joinToString("-|-" )
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
            MD5Utils.md5Encode16("$engineKey-|-$content")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    /** 校验缓存是否为可播放音频，坏缓存直接删除并重新合成 */
    private fun hasSpeakFile(name: String): Boolean {
        val file = File("${ttsFolderPath}$name.mp3")
        if (!file.exists() || file.length() < 128L) return false
        val valid = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(3)
                val read = input.read(head)
                read >= 2 && (
                    // MP3帧同步字节 FF Ex
                    ((head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) ||
                    // 带ID3标签的MP3
                    (read >= 3 && head[0] == 'I'.code.toByte() &&
                        head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) ||
                    // WAV
                    (read >= 3 && head[0] == 'R'.code.toByte() &&
                        head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte())
                )
            }
        }.getOrDefault(false)
        if (!valid) file.delete()
        return valid
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3").apply {
            // 多角色模式可能在传统createSpeakFile之前直接写入，必须先创建缓存目录
            parentFile?.mkdirs()
        }
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    if (pageIndex < textChapter.pageSize) {
                        ReadBook.moveToNextPage()
                        upTtsProgress(readAloudNumber + i.toInt())
                    }
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        // 多角色模式中，同一段落会有多个角色媒体项；只有切到下一段的first时才推进文本进度。
        val mediaId = mediaItem?.mediaId.orEmpty()
        if (mediaId.startsWith("ai-role:") && mediaId.endsWith(":next")) {
            return
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.playWhenReady = true
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    inner class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

    // ===== AI TTS 引擎合成方法 =====

    /**
     * AI引擎朗读：每个角色片段生成后立即加入播放队列，避免整段全部合成完才出声。
     */
    private fun downloadAndPlayAudiosAi() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val engine = AiTtsEngineFactory.create(httpTts)
                val speed = (AppConfig.speechRatePlay + 5) / 10.0f
                val narrator = MultiRoleNarrator(
                    narratorVoice = httpTts.narratorVoice ?: "zh-CN-YunyangNeural",
                    defaultVoice = httpTts.voiceName ?: "zh-CN-XiaoxiaoNeural"
                )
                var playbackStarted = false

                contentList.forEachIndexed { paragraphIndex, content ->
                    ensureActive()
                    if (paragraphIndex < nowSpeak) return@forEachIndexed
                    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) return@forEachIndexed

                    val roleSegments = if (httpTts.multiRoleEnabled) {
                        narrator.analyze(speakText)
                    } else {
                        SmartSegmenter.segment(
                            speakText,
                            httpTts.maxCharLimit.takeIf { it > 0 } ?: 5000
                        ).map {
                            MultiRoleNarrator.RoleSegment(
                                it, "默认", httpTts.voiceName ?: "zh-CN-XiaoxiaoNeural",
                                SmartSegmenter.detectEmotion(it), false
                            )
                        }
                    }

                    roleSegments.forEachIndexed { roleIndex, seg ->
                        ensureActive()
                        val fileName = md5SpeakFileName(
                            "role-v2|${seg.speaker}|${seg.voice}|${seg.emotion}|${seg.text}"
                        )
                        if (!hasSpeakFile(fileName)) {
                            val bytes = try {
                                engine.synthesize(
                                    seg.text,
                                    seg.voice,
                                    speed,
                                    options = mapOf(
                                        "emotion" to seg.emotion,
                                        "speaker" to seg.speaker
                                    )
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (primaryError: Exception) {
                                // 角色音色失效或临时不可用时，自动回退到默认音色，不中断整章朗读
                                val fallbackVoice = httpTts.voiceName ?: "zh-CN-XiaoxiaoNeural"
                                if (seg.voice == fallbackVoice) {
                                    throw NoStackTraceException(
                                        "角色[${seg.speaker}] 音色[${seg.voice}]合成失败: ${primaryError.localizedMessage}"
                                    )
                                }
                                try {
                                    engine.synthesize(
                                        seg.text,
                                        fallbackVoice,
                                        speed,
                                        options = mapOf("emotion" to seg.emotion, "speaker" to seg.speaker)
                                    )
                                } catch (fallbackError: Exception) {
                                    throw NoStackTraceException(
                                        "角色[${seg.speaker}]音色失败且回退失败: ${fallbackError.localizedMessage}"
                                    )
                                }
                            }
                            if (!isPlayableAudio(bytes)) {
                                throw NoStackTraceException(
                                    "角色[${seg.speaker}] 音色[${seg.voice}]返回无效音频"
                                )
                            }
                            getSpeakFileAsMd5(fileName).writeBytes(bytes)
                        }

                        val isFirstRole = roleIndex == 0
                        val mediaId = "ai-role:$paragraphIndex:${if (isFirstRole) "first" else "next"}"
                        val mediaItem = MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(Uri.fromFile(getSpeakFileAsMd5(fileName)))
                            .build()
                        launch(Main) {
                            exoPlayer.addMediaItem(mediaItem)
                            // 第一小句生成完立即开播，不等待全段或整页合成。
                            if (!playbackStarted) {
                                playbackStarted = true
                                exoPlayer.prepare()
                                exoPlayer.playWhenReady = true
                            }
                        }
                    }
                }
            }
        }.onError {
            val message = "AI多人朗读失败\n${it.localizedMessage}"
            AppLog.put(message, it, true)
            toastOnUi(message)
            pauseReadAloud()
        }
    }

    private fun isPlayableAudio(bytes: ByteArray): Boolean {
        if (bytes.size < 128) return false
        return ((bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xE0) == 0xE0) ||
            (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte()) ||
            (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte())
    }

    /**
     * AI 引擎流式合成（边合成边播放）
     */
    private fun downloadAndPlayAudiosAiStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val engine = AiTtsEngineFactory.create(httpTts)
                val speed = (AppConfig.speechRatePlay + 5) / 10.0f

                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed

                    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        createSilentSound(md5SpeakFileName(content))
                        return@forEachIndexed
                    }

                    val fileName = "ai_stream_${System.currentTimeMillis()}_$index"
                    val tempFile = File(ttsFolderPath, "$fileName.${httpTts.apiFormat}").apply {
                        parentFile?.mkdirs()
                    }

                    runCatching {
                        // 流式接收音频块并写入临时文件；完成后交给ExoPlayer播放
                        // 普通FileDataSource不会等待文件追加，不能在首块时就播放，否则容易提前EOF
                        engine.synthesizeStream(speakText, httpTts.voiceName, speed).collect { chunk ->
                            tempFile.appendBytes(chunk)
                        }

                        if (tempFile.exists() && tempFile.length() > 0) {
                            val mediaItem = MediaItem.fromUri(Uri.fromFile(tempFile))
                            launch(Main) {
                                exoPlayer.addMediaItem(mediaItem)
                                if (!exoPlayer.isPlaying) {
                                    exoPlayer.prepare()
                                    exoPlayer.playWhenReady = true
                                }
                            }
                        } else {
                            createSilentSound(md5SpeakFileName(speakText))
                        }
                    }.onFailure {
                        when (it) {
                            is CancellationException -> Unit
                            else -> {
                                AppLog.put("AI TTS 流式合成失败: ${it.localizedMessage}", it)
                                pauseReadAloud()
                            }
                        }
                        return@execute
                    }
                }
                // 预下载下一章内容
                preDownloadAudiosAi(engine, httpTts, speed)
            }
        }.onError {
            AppLog.put("AI TTS 流式朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * AI 引擎预下载下一章
     */
    private suspend fun preDownloadAudiosAi(
        engine: io.legado.app.help.tts.AiTtsEngine,
        httpTts: HttpTTS,
        speed: Float
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val nextContents = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(5)
            .toList()
        nextContents.forEach { content ->
            coroutineContext.ensureActive()
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(md5SpeakFileName(content))
                return@forEach
            }
            val fileName = md5SpeakFileName(speakText)
            if (!hasSpeakFile(fileName)) {
                runCatching {
                    val audio = engine.synthesize(speakText, httpTts.voiceName, speed)
                    if (audio.isNotEmpty()) {
                        val file = getSpeakFileAsMd5(fileName)
                        file.outputStream().use { it.write(audio) }
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

}
