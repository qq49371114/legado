package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogHttpTtsEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HttpTtsEditDialog() : BaseDialogFragment(R.layout.dialog_http_tts_edit, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogHttpTtsEditBinding::bind)
    private val viewModel by viewModels<HttpTtsEditViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.tvUrl.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        binding.tvLoginUrl.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        binding.tvLoginUi.addJsonPattern()
        binding.tvLoginCheckJs.addJsPattern()
        binding.tvHeaders.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        viewModel.initData(arguments) {
            initView(httpTTS = it)
        }
        initMenu()
    }

    fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.speak_engine_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
    }

    fun initView(httpTTS: HttpTTS) {
        binding.tvName.setText(httpTTS.name)
        binding.tvUrl.setText(httpTTS.url)
        binding.tvContentType.setText(httpTTS.contentType)
        binding.tvEngineType.setText(httpTTS.engineType)
        binding.tvVoiceModel.setText(httpTTS.voiceModel)
        binding.tvVoiceName.setText(httpTTS.voiceName)
        binding.tvApiFormat.setText(httpTTS.apiFormat)
        binding.swStreamMode.isChecked = httpTTS.streamMode
        binding.swMultiRole.isChecked = httpTTS.multiRoleEnabled
        binding.swAudioDrama.isChecked = httpTTS.audioDramaEnabled
        binding.swDuckBackground.isChecked = httpTTS.duckBackground
        binding.tvBackgroundVolume.setText(httpTTS.backgroundVolume.toString())
        binding.tvSceneHoldSeconds.setText(httpTTS.sceneHoldSeconds.toString())
        binding.tvNarratorVoice.setText(httpTTS.narratorVoice ?: "zh-CN-YunyangNeural")
        binding.tvConcurrentRate.setText(httpTTS.concurrentRate)
        binding.tvLoginUrl.setText(httpTTS.loginUrl)
        binding.tvLoginUi.setText(httpTTS.loginUi)
        binding.tvLoginCheckJs.setText(httpTTS.loginCheckJs)
        binding.tvHeaders.setText(httpTTS.header)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> viewModel.save(dataFromView()) {
                toastOnUi("保存成功")
            }
            R.id.menu_login -> dataFromView().let { httpTts ->
                // 自定义登录UI（如Azure Key+Region、OpenAI Key）不需要loginUrl
                if (httpTts.loginUrl.isNullOrBlank() && httpTts.loginUi.isNullOrBlank()) {
                    toastOnUi("登录url和登录UI不能同时为空")
                } else {
                    viewModel.save(httpTts) {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "httpTts")
                            putExtra("key", httpTts.id.toString())
                        }
                    }
                }
            }
            R.id.menu_show_login_header -> alert {
                setTitle(R.string.login_header)
                dataFromView().getLoginHeader()?.let { loginHeader ->
                    setMessage(loginHeader)
                }
            }
            R.id.menu_del_login_header -> dataFromView().removeLoginHeader()
            R.id.menu_copy_source -> dataFromView().let {
                context?.sendToClip(GSON.toJson(it))
            }
            R.id.menu_paste_source -> viewModel.importFromClip {
                initView(it)
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("httpTTSHelp")
        }
        return true
    }

    private fun dataFromView(): HttpTTS {
        val original = viewModel.originalHttpTTS
        return HttpTTS(
            id = viewModel.id ?: System.currentTimeMillis(),
            name = binding.tvName.text.toString(),
            url = binding.tvUrl.text.toString(),
            contentType = binding.tvContentType.text?.toString(),
            concurrentRate = binding.tvConcurrentRate.text?.toString(),
            loginUrl = binding.tvLoginUrl.text?.toString(),
            loginUi = binding.tvLoginUi.text?.toString(),
            loginCheckJs = binding.tvLoginCheckJs.text?.toString(),
            header = binding.tvHeaders.text?.toString(),
            // AI扩展字段未显示在旧编辑页，必须原样保留，不能因点击保存而重置为http引擎
            jsLib = original?.jsLib,
            enabledCookieJar = original?.enabledCookieJar ?: false,
            lastUpdateTime = original?.lastUpdateTime ?: System.currentTimeMillis(),
            engineType = binding.tvEngineType.text?.toString()?.trim().orEmpty().ifBlank { "http" },
            voiceModel = binding.tvVoiceModel.text?.toString()?.trim()?.ifBlank { null },
            voiceName = binding.tvVoiceName.text?.toString()?.trim()?.ifBlank { null },
            apiFormat = binding.tvApiFormat.text?.toString()?.trim().orEmpty().ifBlank { "mp3" },
            streamMode = binding.swStreamMode.isChecked,
            ssmlSupport = original?.ssmlSupport ?: false,
            maxCharLimit = original?.maxCharLimit ?: 0,
            speedRange = original?.speedRange,
            pitchRange = original?.pitchRange,
            emotionTags = original?.emotionTags,
            multiRoleEnabled = binding.swMultiRole.isChecked,
            narratorVoice = binding.tvNarratorVoice.text?.toString()?.trim()?.ifBlank { null },
            audioDramaEnabled = binding.swAudioDrama.isChecked,
            backgroundVolume = binding.tvBackgroundVolume.text?.toString()?.toIntOrNull()?.coerceIn(0, 40) ?: 12,
            sceneHoldSeconds = binding.tvSceneHoldSeconds.text?.toString()?.toIntOrNull()?.coerceIn(10, 300) ?: 45,
            duckBackground = binding.swDuckBackground.isChecked
        )
    }

}