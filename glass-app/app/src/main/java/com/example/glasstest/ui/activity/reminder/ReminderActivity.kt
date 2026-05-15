package com.example.glasstest.ui.activity.reminder

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.glasstest.databinding.ActivityReminderBinding
import com.example.glasstest.network.ReminderApiClient
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ReminderActivity"

/**
 * 服药提醒展示页（双目）。
 *
 * 启动参数：
 *   通过 Intent extras 传入消息字段（避免依赖 PollingService 单例）
 *
 * 交互：
 *   - 单击 → POST /ackReminder 并 finish()
 *   - 双击 → 直接 finish()，不 ack，下一轮轮询会再次拉起
 */
class ReminderActivity : BaseMirrorActivity<ActivityReminderBinding>() {

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_UNIT = "unit"
        const val EXTRA_TIMES_PER_DAY = "times_per_day"
        const val EXTRA_SCHEDULED_TIME = "scheduled_time"
        const val EXTRA_IS_TEST = "is_test"
        const val EXTRA_PHONE_HOST = "phone_host"
        const val EXTRA_PHONE_PORT = "phone_port"
    }

    private val api = ReminderApiClient()

    private var messageId: String = ""
    private var phoneHost: String = ""
    private var phonePort: Int = 8080
    private var acking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val amount = intent.getStringExtra(EXTRA_AMOUNT).orEmpty()
        val unit = intent.getStringExtra(EXTRA_UNIT).orEmpty()
        val timesPerDay = intent.getStringExtra(EXTRA_TIMES_PER_DAY).orEmpty()
        val scheduledTime = intent.getStringExtra(EXTRA_SCHEDULED_TIME).orEmpty()
        val isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false)
        phoneHost = intent.getStringExtra(EXTRA_PHONE_HOST).orEmpty()
        phonePort = intent.getIntExtra(EXTRA_PHONE_PORT, 8080)

        Log.i(TAG, "提醒页启动：$name @ $scheduledTime  messageId=$messageId  isTest=$isTest")

        mBindingPair.updateView {
            tvReminderTitle.text = if (isTest) "服药提醒（测试）" else "服药提醒"
            tvReminderTime.text = scheduledTime
            tvReminderName.text = name
            tvReminderDosage.text = buildString {
                append("一天 ").append(timesPerDay).append(" 次  每次 ")
                append(amount).append(" ").append(unit)
            }
        }

        setupTempleActions()
    }

    private fun setupTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> confirmAndFinish()
                        is TempleAction.DoubleClick -> {
                            Log.i(TAG, "用户双击稍后：$messageId")
                            FToast.show("稍后提醒")
                            finish()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun confirmAndFinish() {
        if (acking) return
        acking = true
        Log.i(TAG, "用户单击确认：$messageId")
        FToast.show("已确认服药")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (phoneHost.isNotEmpty() && messageId.isNotEmpty()) {
                    api.ack(phoneHost, phonePort, messageId)
                }
            }
            finish()
        }
    }
}
