package com.nitsuya.quickding

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.VirtualDeviceParams
import android.content.IntentSender
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.hardware.input.VirtualTouchEvent
import android.hardware.input.VirtualTouchscreen
import android.hardware.input.VirtualTouchscreenConfig
import android.media.AudioAttributes
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.topjohnwu.superuser.Shell

class QuickDingActivity : AppCompatActivity() {

    private var mAssociationInfo : AssociationInfo? = null
    private var mVirtualDevice : VirtualDeviceManager.VirtualDevice? = null
    private var mVirtualDisplay : VirtualDisplay? = null
    private var mVirtualTouchscreen : VirtualTouchscreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_quick_ding)
        setShowWhenLocked(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)

        val sharedPreferences = getSharedPreferences("Settings", MODE_PRIVATE)
        var corpId = sharedPreferences.getString("CorpId", "")
        if(corpId.isBlank()){
            val et = EditText(this)
            AlertDialog.Builder(this).setTitle("CorpId").setView(et).setCancelable(false).setPositiveButton("Setting") { _, _ ->
                corpId = et.text.toString()
                sharedPreferences.edit().apply {
                    putString("CorpId", corpId)
                }.commit()
                quickDing(corpId)
            }.show()
        } else {
            quickDing(corpId)
        }
    }

    private fun quickDing(corpId: String) {
        if(corpId.isBlank()){
            finish()
            return
        }
        val tvDisplay = findViewById<TextureView>(R.id.tvDisplay)
        var vibrator = getSystemService(Vibrator::class.java)
        Shell.cmd("cmd role add-role-holder android.app.role.COMPANION_DEVICE_APP_STREAMING $packageName").exec()
        createAssociate { associationInfo ->
            mAssociationInfo = associationInfo
            mVirtualDevice = createVirtualDevice(associationInfo.id)
            tvDisplay.post {
                mVirtualDisplay = createVirtualDisplay(
                    mVirtualDevice!!,
                    tvDisplay.width,
                    tvDisplay.height,
                    resources.displayMetrics.densityDpi
                )
                mVirtualTouchscreen =
                    createVirtualTouchscreen(mVirtualDevice!!, tvDisplay.width, tvDisplay.height)
                mVirtualDisplay!!.surface = Surface(tvDisplay.surfaceTexture)
                var vibrating = true
                tvDisplay.setOnTouchListener { _, event ->
                    if (vibrating) {
                        vibrating = false
                        vibrator.cancel()
                    }
                    var action: Int = event.action and 255
                    action = when (action) {
                        MotionEvent.ACTION_POINTER_DOWN -> VirtualTouchEvent.ACTION_DOWN
                        MotionEvent.ACTION_POINTER_UP -> VirtualTouchEvent.ACTION_UP
                        else -> action
                    }
                    for (i in 0 until event.pointerCount) {
                        try {
                            val build = VirtualTouchEvent.Builder()
                                .setAction(action)
                                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                                .setPointerId(event.getPointerId(i))
                                .setX(event.getX(i))
                                .setY(event.getY(i))
                                .setPressure(1.0f)
                                .setEventTimeNanos(event.eventTimeNanos)
                                .build()
                            mVirtualTouchscreen?.sendTouchEvent(build)
                        } catch (e: Throwable) {
                        }
                    }
                    true
                }
                Shell.cmd(
                    "am start -a android.intent.action.VIEW -d \"dingtalk://dingtalkclient/page/link?url=127.0.0.1\" --display ${mVirtualDisplay!!.display.displayId} " +
                    "&& sleep 3 " +
                    "&& am start -a android.intent.action.VIEW -d \"dingtalk://dingtalkclient/page/link?url=https://attend.dingtalk.com/attend/index.html?corpId=$corpId\" --display ${mVirtualDisplay!!.display.displayId}"
                ).exec()
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Shell.cmd("am force-stop com.alibaba.android.rimet").exec()
        if(mVirtualTouchscreen != null){
            mVirtualTouchscreen!!.close()
            mVirtualTouchscreen = null
        }

        if(mVirtualDisplay != null){
            mVirtualDisplay!!.release()
            mVirtualDisplay = null
        }

        if(mVirtualDevice != null){
            mVirtualDevice!!.close()
            mVirtualDevice = null
        }
        if(mAssociationInfo != null) {
            getSystemService(CompanionDeviceManager::class.java).disassociate(mAssociationInfo!!.id)
            mAssociationInfo = null
        }
        Shell.cmd("am stack remove \$(dumpsys activity recents | grep \"* Recent\" | grep \"Task{\" | grep \":com.alibaba.android.rimet}\" | grep \"#[0-9]*\" -o | sed -n '2p' | grep \"[0-9]*\" -o)").exec()
        //shell remove role force close app
        Shell.cmd("cmd role remove-role-holder android.app.role.COMPANION_DEVICE_APP_STREAMING $packageName && am stack remove \$(dumpsys activity recents | grep \"* Recent\" | grep \"Task{\" | grep \":$packageName}\" | grep \"#[0-9]*\" -o | sed -n '2p' | grep \"[0-9]*\" -o)").exec()
        //be of no effect
        finish()
    }


    private fun createAssociate(callback : (associationInfo : AssociationInfo) -> Unit){
        try {
            getSystemService(CompanionDeviceManager::class.java).associate(
                AssociationRequest.Builder()
                    .setDisplayName("QuickDing-GAL")
                    .setSelfManaged(true)
                    .setForceConfirmation(false)
                    .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_APP_STREAMING)
                    .build()
                , object: CompanionDeviceManager.Callback() {
                    override fun onAssociationCreated(associationInfo: AssociationInfo) = callback(associationInfo)
                    override fun onFailure(error: CharSequence?) {
                        AlertDialog.Builder(this@QuickDingActivity).setTitle("Associate onFailure").setMessage(error ?: "WTF?").setCancelable(false).setPositiveButton("OK") { _, _ ->
                            finish()
                        }.show()
                    }
                    override fun onAssociationPending(intentSender: IntentSender?) {
                        AlertDialog.Builder(this@QuickDingActivity).setTitle("Associate onAssociationPending").setMessage("WTF?").setCancelable(false).setPositiveButton("OK") { _, _ ->
                            finish()
                        }.show()
                    }
                }
                , null
            )
        } catch (e : Throwable){
            AlertDialog.Builder(this).setTitle("Associate Exception").setMessage(e.message).setCancelable(false).setPositiveButton("OK") { _, _ ->
                finish()
            }.show()
        }
    }

    private fun createVirtualDevice(associationInfoId : Int) = getSystemService(VirtualDeviceManager::class.java).createVirtualDevice(
        associationInfoId,
        VirtualDeviceParams.Builder().apply {
            setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
            setDevicePolicy(VirtualDeviceParams.POLICY_TYPE_RECENTS, VirtualDeviceParams.DEVICE_POLICY_DEFAULT)
//            setDevicePolicy(VirtualDeviceParams.POLICY_TYPE_RECENTS, VirtualDeviceParams.DEVICE_POLICY_CUSTOM)
            setBlockedCrossTaskNavigations(mutableSetOf())
            setBlockedActivities(mutableSetOf())
        }.build()
    )

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay(virtualDevice : VirtualDeviceManager.VirtualDevice, width : Int, height : Int, dpi : Int) = virtualDevice.createVirtualDisplay(
        VirtualDisplayConfig.Builder("VirtualDevice_" + mVirtualDevice!!.deviceId, width, height, dpi)
            .setFlags(
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
            )
            .build()
        , mainExecutor
        , object : VirtualDisplay.Callback() {
            override fun onPaused() {
            }

            override fun onResumed() {
            }

            override fun onStopped() {
            }
        }
    )

    private fun createVirtualTouchscreen(virtualDevice : VirtualDeviceManager.VirtualDevice, width : Int, height : Int) = virtualDevice.createVirtualTouchscreen(
        VirtualTouchscreenConfig.Builder(width, height)
            .setAssociatedDisplayId(mVirtualDisplay!!.display.displayId)
            .setInputDeviceName("QuickDing-IN")
            .build()
    )

}